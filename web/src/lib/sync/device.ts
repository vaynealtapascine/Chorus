// Device identity: a non-extractable P-256 key, enrolment by invite, session renewal by signing a
// server nonce (docs/API.md §2).
import { saveDevice, type DeviceRecord } from './persist';
import { ApiError, apiFetch } from '../http';

const b64 = (buf: ArrayBuffer): string => btoa(String.fromCharCode(...new Uint8Array(buf)));

export function apiBase(): string {
  return `${location.origin}/api/v1`;
}

async function post<T>(path: string, body: unknown): Promise<T> {
  const r = await apiFetch(`${apiBase()}${path}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
  });
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw new ApiError(r.status, j?.error?.code ?? '', j?.error?.message ?? `HTTP ${r.status}`, j);
  return j as T;
}

/** Invite links look like https://host/i/<code>; accept the link or the bare code. */
export function inviteCode(input: string): string {
  const s = input.trim();
  const m = s.match(/\/i\/([A-Za-z0-9_-]+)/);
  return m ? m[1] : s;
}

export interface InvitePeek {
  kind: 'system' | 'person' | 'device' | null;
  /** a `device` invite's account (D-153); null from a server that doesn't say */
  account_id: string | null;
}

/** What an invite is for, without using it (D-093): `system`/`person` for a new account (the
 *  invite's suggestion; the person joining picks), `device` for another device of an account,
 *  with that account. Kind null when the server predates the question, so onboarding asks nothing. */
export async function peekInvite(invite: string): Promise<InvitePeek> {
  const r = await apiFetch(`${apiBase()}/auth/invite`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ code: inviteCode(invite) }),
  });
  const j = await r.json().catch(() => ({}));
  if (r.status === 400) throw new Error(j?.error?.message ?? 'That invite has expired or was already used.');
  const kind = r.ok ? j?.kind : null;
  return {
    kind: kind === 'system' || kind === 'person' || kind === 'device' ? kind : null,
    account_id: r.ok && typeof j?.account_id === 'string' ? j.account_id : null,
  };
}

/** What enrolling with an invite does to a signed-out browser's kept copy (D-153): a device link of
 * the same account picks it up; anything else erases it first, once the person has said so. */
export type Relink = 'none' | 'pick-up' | 'other-account' | 'new-account' | 'unknown-account';

export function relinkPlan(keptAccount: string | null, peek: InvitePeek | null): Relink {
  if (!keptAccount) return 'none';
  if (peek?.kind === 'device') return !peek.account_id ? 'unknown-account' : peek.account_id === keptAccount ? 'pick-up' : 'other-account';
  // a new account, or a server too old to say what its invite is for
  return peek?.kind ? 'new-account' : 'unknown-account';
}

export async function enrol(
  invite: string,
  account: { display_name?: string; handle?: string; kind?: 'system' | 'person' } | null,
  deviceName: string,
): Promise<DeviceRecord> {
  const keys = (await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, false, [
    'sign',
    'verify',
  ])) as CryptoKeyPair;
  const spki = await crypto.subtle.exportKey('spki', keys.publicKey);
  const e = await post<{ account_id: string; is_admin: boolean; device_id: string; short_id: string; session: string; expires_at: number }>(
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
  const s = await post<{ session: string; expires_at: number; is_admin: boolean }>('/auth/session', {
    device_id: dev.device_id,
    nonce,
    signature: b64(sig),
  });
  const next = { ...dev, session: s.session, expires_at: s.expires_at, is_admin: s.is_admin };
  await saveDevice(next);
  return next;
}
