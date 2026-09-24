// Reading blobs (avatars, emoji, attachments) so they still show while the server is down:
// a blob still waiting to upload, then this browser's copy, then the server. Blobs are
// content-addressed, so a copy stored under its hash never goes stale.
import { apiBase } from './device';
import { pendingBlob, type DeviceRecord } from './persist';

// Not `chorus-…`: the service worker deletes every other `chorus-` cache when the app updates.
const CACHE = 'blobs-v1';
/** Bigger blobs (videos, archives) are fetched each time rather than kept. */
const MAX_KEPT = 20 * 1024 * 1024;

const inFlight = new Map<string, Promise<Blob | null>>();

const key = (hash: string) => `/blob-cache/${hash}`;

async function cached(hash: string): Promise<Blob | null> {
  try {
    const hit = await (await caches.open(CACHE)).match(key(hash));
    return hit ? await hit.blob() : null;
  } catch {
    return null; // no Cache Storage (non-secure origin, private mode)
  }
}

/** Keep a copy of a blob this browser has (fetched, or uploaded from here). */
export async function keepBlob(hash: string, blob: Blob, mime: string | null): Promise<void> {
  if (blob.size > MAX_KEPT) return;
  try {
    const headers = { 'content-type': mime ?? blob.type ?? 'application/octet-stream' };
    await (await caches.open(CACHE)).put(key(hash), new Response(blob, { headers }));
  } catch {
    // over quota or no Cache Storage: the blob still shows, it just isn't kept
  }
}

async function load(hash: string, device: DeviceRecord | null): Promise<Blob | null> {
  const local = (await pendingBlob(hash)) ?? (await cached(hash));
  if (local || !device) return local ?? null;
  const response = await fetch(`${apiBase()}/blobs/${hash}`, {
    headers: { authorization: `Bearer ${device.session}` },
  }).catch(() => null);
  if (!response?.ok) return null;
  const blob = await response.blob();
  await keepBlob(hash, blob, response.headers.get('content-type'));
  return blob;
}

/** The blob with this hash, or null when it isn't here and the server can't be reached. */
export function loadBlob(hash: string, device: DeviceRecord | null): Promise<Blob | null> {
  let p = inFlight.get(hash);
  if (!p) {
    p = load(hash, device).finally(() => inFlight.delete(hash));
    inFlight.set(hash, p);
  }
  return p;
}

/** Forget every kept blob (with the rest of this device's data). */
export async function forgetBlobs(): Promise<void> {
  try { await caches.delete(CACHE); } catch { /* nothing kept */ }
}

/**
 * Ask the browser not to evict this device's data under storage pressure: the replica and outbox
 * are the only copy of what was done offline. Chrome decides silently (installed apps and
 * engaged sites get it); Firefox asks once. Call it after the device is set up, not before.
 */
export async function keepStorage(): Promise<boolean> {
  try {
    if (!navigator.storage?.persist) return false;
    return (await navigator.storage.persisted()) || (await navigator.storage.persist());
  } catch {
    return false;
  }
}
