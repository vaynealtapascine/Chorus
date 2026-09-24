// "Keep everything on this device" (D-070, CLIENTS.md §4.3). Every op this account may see is
// already kept (the SYNC §6 window for older messages isn't built); this setting adds the files:
// attachments, avatars and custom emoji up to the offline cache's 20 MB limit are fetched into
// it in the background after connecting, so they show with the server down. It is per device:
// on by default in the installed app, off in a browser tab.
import type { Projection } from './client';
import { loadBlob } from './blobs';
import { deviceSetting, saveDeviceSetting, type DeviceRecord } from './persist';

const SETTING = 'keep_everything';
/** Same limit as the offline cache (blobs.ts): bigger files are fetched each time. */
const MAX_KEPT = 20 * 1024 * 1024;

export function installed(): boolean {
  return typeof matchMedia === 'function' && matchMedia('(display-mode: standalone)').matches;
}

export async function keepEverything(): Promise<boolean> {
  return (await deviceSetting<boolean>(SETTING)) ?? installed();
}

export async function setKeepEverything(on: boolean): Promise<void> {
  await saveDeviceSetting(SETTING, on);
}

/** Every file the projection refers to that the offline cache would keep. */
export function filesOf(p: Projection): string[] {
  const out = new Set<string>();
  const str = (v: unknown) => (typeof v === 'string' && v ? v : null);
  for (const row of Object.values(p.rows.attachment ?? {})) {
    if (!row.exists || row.fields.deleted_at != null) continue;
    const size = typeof row.fields.size === 'number' ? row.fields.size : 0;
    const thumb = str(row.fields.thumb_blob_hash);
    if (thumb) out.add(thumb);
    const blob = str(row.fields.blob_hash);
    if (blob && size <= MAX_KEPT) out.add(blob);
  }
  for (const table of ['member', 'member_group']) {
    for (const row of Object.values(p.rows[table] ?? {})) {
      const avatar = row.exists ? str(row.fields.avatar_blob) : null;
      if (avatar) out.add(avatar);
    }
  }
  for (const row of Object.values(p.rows.custom_emoji ?? {})) {
    const blob = row.exists ? str(row.fields.blob_hash) : null;
    if (blob) out.add(blob);
  }
  return [...out].sort();
}

export interface FillProgress { done: number; total: number; missing: number }

/**
 * Bring every file into the offline cache (ones already there cost a cache lookup). Three at a
 * time; `missing` counts files the server couldn't give (offline, or gone).
 */
export async function fillFiles(
  p: Projection,
  device: DeviceRecord | null,
  onProgress: (progress: FillProgress) => void,
  signal?: AbortSignal,
): Promise<FillProgress> {
  const files = filesOf(p);
  const progress: FillProgress = { done: 0, total: files.length, missing: 0 };
  onProgress({ ...progress });
  let next = 0;
  const worker = async () => {
    while (next < files.length && !signal?.aborted) {
      const hash = files[next++];
      if (!(await loadBlob(hash, device))) progress.missing += 1;
      progress.done += 1;
      onProgress({ ...progress });
    }
  };
  await Promise.all([worker(), worker(), worker()]);
  return progress;
}

/** How much this site stores on the device, and how much the browser allows. */
export async function spaceUsed(): Promise<{ usage: number; quota: number } | null> {
  try {
    const e = await navigator.storage?.estimate?.();
    return e && typeof e.usage === 'number' ? { usage: e.usage, quota: e.quota ?? 0 } : null;
  } catch {
    return null;
  }
}
