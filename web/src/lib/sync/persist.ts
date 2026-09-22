// IndexedDB persistence for the replica (write-behind; docs/CLIENTS.md §4).
import { openDB, type IDBPDatabase } from 'idb';

export interface DeviceRecord {
  device_id: string;
  short_id: string;
  account_id: string;
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

const DB = 'chorus';
let dbp: Promise<IDBPDatabase> | null = null;

function db(): Promise<IDBPDatabase> {
  if (!dbp) {
    dbp = openDB(DB, 1, {
      upgrade(d) {
        d.createObjectStore('ops', { keyPath: 'id' });
        d.createObjectStore('kv');
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

export async function wipe(): Promise<void> {
  const d = await db();
  const tx = d.transaction(['ops', 'kv'], 'readwrite');
  await Promise.all([tx.objectStore('ops').clear(), tx.objectStore('kv').clear(), tx.done]);
}
