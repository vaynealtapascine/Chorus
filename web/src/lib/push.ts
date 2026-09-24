// Web Push for this browser (push.rs; L6): subscribe with the server's VAPID key and register
// the subscription like an Android device registers its UnifiedPush endpoint. Needs the built
// app's service worker (dev builds have none) and a browser push service.
import { apiBase } from './sync/device';
import { apiFetch } from './http';
import { sync } from './sync/client';

export function pushSupported(): boolean {
  return typeof navigator !== 'undefined' && 'serviceWorker' in navigator && typeof PushManager !== 'undefined';
}

function bytes(b64url: string): Uint8Array<ArrayBuffer> {
  const s = atob(b64url.replace(/-/g, '+').replace(/_/g, '/'));
  const out = new Uint8Array(new ArrayBuffer(s.length));
  for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i);
  return out;
}

function same(a: ArrayBuffer | null, b: Uint8Array): boolean {
  if (!a || a.byteLength !== b.length) return false;
  const x = new Uint8Array(a);
  return x.every((v, i) => v === b[i]);
}

async function call(method: string, path: string, body?: unknown) {
  const r = await apiFetch(`${apiBase()}${path}`, {
    method,
    headers: { 'content-type': 'application/json', authorization: `Bearer ${sync.device?.session ?? ''}` },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!r.ok) throw new Error((await r.json().catch(() => null))?.error?.message ?? `HTTP ${r.status}`);
  return r.status === 204 ? null : r.json();
}

async function registration(): Promise<ServiceWorkerRegistration | null> {
  if (!pushSupported()) return null;
  return (await navigator.serviceWorker.getRegistration()) ?? null;
}

/** Is this browser subscribed right now? */
export async function pushActive(): Promise<boolean> {
  const reg = await registration();
  return !!(await reg?.pushManager.getSubscription());
}

/** Ask for permission, subscribe and tell the server. Resolves to an error to show, or null. */
export async function enablePush(): Promise<string | null> {
  const reg = await registration();
  if (!reg) return 'This browser (or a development build) has no push support.';
  if ((await Notification.requestPermission()) !== 'granted') return 'Notifications are blocked for this site.';
  try {
    const key = bytes((await call('GET', '/push/vapid')).public_key);
    let sub = await reg.pushManager.getSubscription();
    if (sub && !same(sub.options.applicationServerKey, key)) {
      await sub.unsubscribe(); // made for another server key
      sub = null;
    }
    sub ??= await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: key });
    const j = sub.toJSON();
    await call('PUT', '/devices/push', { endpoint: j.endpoint, p256dh: j.keys?.p256dh, auth: j.keys?.auth });
    return null;
  } catch (e) {
    return e instanceof Error ? e.message : String(e);
  }
}

export async function disablePush(): Promise<void> {
  const reg = await registration();
  await (await reg?.pushManager.getSubscription())?.unsubscribe();
  await call('DELETE', '/devices/push');
}
