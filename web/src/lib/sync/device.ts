// Device identity: a non-extractable P-256 key, enrolment by invite, session renewal by signing a
// server nonce (docs/API.md §2).
import { saveDevice, type DeviceRecord } from './persist';

const b64 = (buf: ArrayBuffer): string => btoa(String.fromCharCode(...new Uint8Array(buf)));

export function apiBase(): string {
  return `${location.origin}/api/v1`;
}

async function post<T>(path: string, body: unknown): Promise<T> {
  const r = await fetch(`${apiBase()}${path}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
  });
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(j?.error?.message ?? `HTTP ${r.status}`);
  return j as T;
}

/** Invite links look like https://host/i/<code>; accept the link or the bare code. */
export function inviteCode(input: string): string {
  const s = input.trim();
  const m = s.match(/\/i\/([A-Za-z0-9_-]+)/);
  return m ? m[1] : s;
}

export async function enrol(
  invite: string,
  account: { display_name?: string; handle?: string } | null,
  deviceName: string,
): Promise<DeviceRecord> {
  const keys = (await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, false, [
    'sign',
    'verify',
  ])) as CryptoKeyPair;
  const spki = await crypto.subtle.exportKey('spki', keys.publicKey);
  const e = await post<{ account_id: string; device_id: string; short_id: string; session: string; expires_at: number }>(
    '/auth/redeem',
    {
      code: inviteCode(invite),
      device: { name: deviceName, platform: 'web', public_key: b64(spki) },
      account,
    },
  );
  const dev: DeviceRecord = { ...e, keys };
  await saveDevice(dev);
  return dev;
}

/** Get a fresh session by signing a challenge with the device key. */
export async function renew(dev: DeviceRecord): Promise<DeviceRecord> {
  const { nonce, instance_id } = await post<{ nonce: string; instance_id: string }>('/auth/challenge', {
    device_id: dev.device_id,
  });
  const msg = new TextEncoder().encode(`chorus-auth\n${nonce}\n${dev.device_id}\n${instance_id}`);
  const sig = await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, dev.keys.privateKey, msg);
  const s = await post<{ session: string; expires_at: number }>('/auth/session', {
    device_id: dev.device_id,
    nonce,
    signature: b64(sig),
  });
  const next = { ...dev, session: s.session, expires_at: s.expires_at };
  await saveDevice(next);
  return next;
}
