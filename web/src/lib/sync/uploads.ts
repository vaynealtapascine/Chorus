// Blobs are persisted before the message op is queued. HEAD gives the resume offset after a
// reload or server restart; the server verifies the content hash on the last PUT.
import { apiBase } from './device';
import { apiFetch, RateLimitedError } from '../http';
import { keepBlob } from './blobs';
import { pendingBlobs, queueBlob, uploadedBlob, type BlobUpload, type DeviceRecord } from './persist';

const CHUNK = 4 * 1024 * 1024;
let running = false;
let retry: ReturnType<typeof setTimeout> | null = null;
let latestDevice: DeviceRecord | null = null;

export async function hashBlob(blob: Blob): Promise<string> {
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', await blob.arrayBuffer()));
  return [...digest].map((b) => b.toString(16).padStart(2, '0')).join('');
}

export async function imageThumbnail(file: File): Promise<Blob | null> {
  if (!file.type.startsWith('image/')) return null;
  try {
    const image = await createImageBitmap(file);
    const scale = Math.min(1, 480 / Math.max(image.width, image.height));
    const canvas = document.createElement('canvas');
    canvas.width = Math.max(1, Math.round(image.width * scale));
    canvas.height = Math.max(1, Math.round(image.height * scale));
    canvas.getContext('2d')?.drawImage(image, 0, 0, canvas.width, canvas.height);
    image.close();
    return await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/webp', 0.8));
  } catch {
    return null; // a file attachment still works when this browser cannot decode its image
  }
}

export async function stageBlob(blob: Blob, mime: string, accountId: string): Promise<BlobUpload> {
  const upload = { hash: await hashBlob(blob), blob, mime: mime || 'application/octet-stream', account_id: accountId };
  await queueBlob(upload);
  return upload;
}

async function send(upload: BlobUpload, dev: DeviceRecord): Promise<void> {
  const url = `${apiBase()}/blobs/${upload.hash}`;
  const auth = { authorization: `Bearer ${dev.session}` };
  const head = await apiFetch(url, { method: 'HEAD', headers: auth });
  // 403: the server has it from another account (e.g. re-sent after a restore, SYNC.md §7.3);
  // an error here would stall this upload and every one queued after it
  if (head.status === 200 || head.status === 403) return;
  if (head.status !== 404 && head.status !== 206) throw new Error(`Blob HEAD: ${head.status}`);
  let offset = head.status === 206 ? Number(head.headers.get('upload-offset')) : 0;
  if (!Number.isSafeInteger(offset) || offset < 0 || offset > upload.blob.size) throw new Error('Invalid upload offset');
  while (offset < upload.blob.size) {
    const end = Math.min(offset + CHUNK, upload.blob.size);
    const response = await apiFetch(url, {
      method: 'PUT',
      headers: { ...auth, 'content-range': `bytes ${offset}-${end - 1}/${upload.blob.size}`, 'content-type': upload.mime },
      body: upload.blob.slice(offset, end),
    });
    if (response.status === 409) {
      // Another tab may have advanced this upload. Re-read its offset on the next attempt.
      throw new Error('Blob upload offset changed');
    }
    if (response.status !== 202 && response.status !== 201 && response.status !== 200) throw new Error(`Blob PUT: ${response.status}`);
    offset = end;
  }
}

export async function flushUploads(dev: DeviceRecord | null): Promise<void> {
  latestDevice = dev;
  if (!dev || running) return;
  if (retry) clearTimeout(retry);
  running = true;
  try {
    for (const upload of await pendingBlobs()) {
      if (upload.account_id !== dev.account_id) continue;
      await send(upload, dev);
      // keep this browser's copy for offline viewing before the queued one goes
      await keepBlob(upload.hash, upload.blob, upload.mime);
      await uploadedBlob(upload.hash);
    }
  } catch (error) {
    console.warn('Blob upload paused; will retry', error);
    // a rate-limited chunk waits as long as the server asked, if that's longer
    const wait = error instanceof RateLimitedError ? Math.max(10_000, error.retryAfterMs) : 10_000;
    retry = setTimeout(() => void flushUploads(latestDevice), wait);
  } finally {
    running = false;
  }
}
