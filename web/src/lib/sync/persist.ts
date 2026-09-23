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

export interface Persisted {
  device: DeviceRecord | null;
  meta: unknown | null;
  ops: unknown[];
  hlc: string;
}

export interface Changes {
  ops: { id: string }[];
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

export async function load(): Promise<Persisted> {
  const d = await db();
  const [device, meta, hlc, ops] = await Promise.all([
    d.get('kv', 'device'),
    d.get('kv', 'meta'),
    d.get('kv', 'hlc'),
    d.getAll('ops'),
  ]);
  return { device: device ?? null, meta: meta ?? null, ops, hlc: hlc ?? '' };
}

export async function save(ch: Changes): Promise<void> {
  if (!ch.ops.length && ch.meta === undefined) {
    const d = await db();
    await d.put('kv', ch.hlc_last, 'hlc');
    return;
  }
  const d = await db();
  const tx = d.transaction(['ops', 'kv'], 'readwrite');
  const ops = tx.objectStore('ops');
  for (const o of ch.ops) void ops.put(o);
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
