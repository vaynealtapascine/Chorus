// IndexedDB persistence for the replica (write-behind; docs/CLIENTS.md §4).
import { openDB, type IDBPDatabase } from 'idb';

export interface DeviceRecord {
  device_id: string;
  short_id: string;
  account_id: string;
  is_admin?: boolean;
  session: string;
  expires_at: number;
  /** Non-extractable CryptoKeyPair (structured-clonable into IndexedDB). */
  keys: CryptoKeyPair;
}

export interface Changes {
  ops: { id: string }[];
  /** ops this device no longer holds (the account lost sight of them; SYNC.md §4.2) */
  removed?: string[];
  meta?: unknown;
  hlc_last: string;
}

export interface BlobUpload {
  hash: string;
  blob: Blob;
  mime: string;
  account_id: string;
}

const DB = 'chorus';
let dbp: Promise<IDBPDatabase> | null = null;

function db(): Promise<IDBPDatabase> {
  if (!dbp) {
    dbp = openDB(DB, 2, {
      upgrade(d, oldVersion) {
        if (oldVersion < 1) {
          d.createObjectStore('ops', { keyPath: 'id' });
          d.createObjectStore('kv');
        }
        if (oldVersion < 2) d.createObjectStore('blob_uploads', { keyPath: 'hash' });
      },
    });
  }
  return dbp;
}

/** The last projection the UI showed and the op copies it came from (CLIENTS.md §4.3). */
export interface Snapshot {
  projection: string;
  /** the core's `projectionDigest()` when it was taken */
  digest: string;
  at: number;
}

export interface Head {
  device: DeviceRecord | null;
  meta: unknown | null;
  hlc: string;
  snapshot: Snapshot | null;
}

/** Everything but the ops: enough to show the app from a snapshot at once. */
export async function loadHead(): Promise<Head> {
  const d = await db();
  const [device, meta, hlc, snapshot] = await Promise.all([
    d.get('kv', 'device'),
    d.get('kv', 'meta'),
    d.get('kv', 'hlc'),
    d.get('kv', 'snapshot'),
  ]);
  return { device: device ?? null, meta: meta ?? null, hlc: hlc ?? '', snapshot: snapshot ?? null };
}

/** Up to `n` persisted ops with ids after `after`, in id order (a slice of the replica). */
export async function loadOps(after: string | undefined, n: number): Promise<{ id: string }[]> {
  const range = after === undefined ? undefined : IDBKeyRange.lowerBound(after, true);
  return (await db()).getAll('ops', range, n);
}

/** A setting that belongs to this device only (never synced). */
export async function deviceSetting<T>(key: string): Promise<T | undefined> {
  return (await db()).get('kv', `setting:${key}`);
}

export async function saveDeviceSetting(key: string, value: unknown): Promise<void> {
  await (await db()).put('kv', value, `setting:${key}`);
}

export async function saveSnapshot(snapshot: Snapshot): Promise<void> {
  await (await db()).put('kv', snapshot, 'snapshot');
}

/** `opsOnly`: a tab that doesn't sync (another tab of this browser does, SYNC §6.1) saves the
 * ops it makes and nothing of the shared metadata, which the syncing tab owns. */
export async function save(ch: Changes, opsOnly = false): Promise<void> {
  if (opsOnly) {
    if (!ch.ops.length) return;
    const tx = (await db()).transaction('ops', 'readwrite');
    for (const o of ch.ops) void tx.store.put(o);
    await tx.done;
    return;
  }
  if (!ch.ops.length && !ch.removed?.length && ch.meta === undefined) {
    const d = await db();
    await d.put('kv', ch.hlc_last, 'hlc');
    return;
  }
  const d = await db();
  const tx = d.transaction(['ops', 'kv'], 'readwrite');
  const ops = tx.objectStore('ops');
  for (const o of ch.ops) void ops.put(o);
  for (const id of ch.removed ?? []) void ops.delete(id);
  const kv = tx.objectStore('kv');
  if (ch.meta !== undefined) void kv.put(ch.meta, 'meta');
  void kv.put(ch.hlc_last, 'hlc');
  await tx.done;
}

export async function saveDevice(dev: DeviceRecord): Promise<void> {
  await (await db()).put('kv', dev, 'device');
}

export async function queueBlob(upload: BlobUpload): Promise<void> {
  await (await db()).put('blob_uploads', upload);
}

export async function pendingBlobs(): Promise<BlobUpload[]> {
  return (await db()).getAll('blob_uploads');
}

export async function pendingBlob(hash: string): Promise<Blob | undefined> {
  return ((await db()).get('blob_uploads', hash) as Promise<BlobUpload | undefined>).then((r) => r?.blob);
}

export async function uploadedBlob(hash: string): Promise<void> {
  await (await db()).delete('blob_uploads', hash);
}

export async function wipe(): Promise<void> {
  const d = await db();
  const tx = d.transaction(['ops', 'kv', 'blob_uploads'], 'readwrite');
  await Promise.all([tx.objectStore('ops').clear(), tx.objectStore('kv').clear(), tx.objectStore('blob_uploads').clear(), tx.done]);
}
