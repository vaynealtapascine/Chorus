// The connection lifecycle (D-148, D-152, D-153): the heartbeat, the signed-out state and the
// erasure of the copy it keeps, through SyncClient with a fake socket, replica and storage, and
// the pure schedule behind them.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../http';
import { heartbeat, PING_AFTER_MS, PONG_GRACE_MS, signedOutBy, wipeIn } from './lifecycle';
import type { DeviceRecord } from './persist';

const renew = vi.fn();
vi.mock('./device', () => ({ renew: (...a: unknown[]) => renew(...a), apiBase: () => 'http://chorus.test/api/v1' }));
const device = { device_id: 'd1', short_id: '0a', account_id: 'a1', is_admin: false, session: 's1', expires_at: 0, keys: {} } as unknown as DeviceRecord;
/** what IndexedDB holds */
const disk: { device: DeviceRecord | null } = { device };
const erase = vi.fn(async () => { disk.device = null; });
vi.mock('./erase', () => ({ eraseKeptCopy: () => erase(), erasedHere: () => false, forgetErased() {}, forgetTab() {} }));
vi.mock('./persist', () => ({
  loadHead: async () => ({ device: disk.device, meta: null, hlc: '', snapshot: null }),
  saveDevice: async (d: DeviceRecord) => { disk.device = d; },
  loadOps: async () => [],
  save: async () => {},
  saveSnapshot: async () => {},
  mergeJournal: () => [],
}));
vi.mock('./uploads', () => ({ flushUploads: async () => {}, stageBlob: async () => {} }));
vi.mock('./blobs', () => ({ keepStorage: async () => {}, loadBlob: async () => null }));
vi.mock('./keep', () => ({ keepEverything: async () => false, fillFiles: async () => {} }));
vi.mock('./snapshot', () => ({ readSnapshot: () => null }));
const sessions: string[] = [];
vi.mock('../core/pkg/chorus_wasm.js', () => ({
  newId: () => 'id',
  WebReplica: {
    begin: () => ({
      addOps() {}, indexStep: () => true, adopt: () => true, setWindow() {}, disconnect() {},
      connect: (_clock: string, session: string) => { sessions.push(session); return '{"t":"hello"}'; },
      onFrame: () => '[]', held: () => '[]', takeChanges: () => '{"ops":[],"hlc_last":""}',
      projection: () => '{"rows":{},"sets":{},"fronts":{},"opaque":0}', projectionDelta: () => '{"full":true}', projectionDigest: () => '',
    }),
  },
}));

class FakeSocket {
  static OPEN = 1;
  static all: FakeSocket[] = [];
  readyState = 0;
  sent: { t: string }[] = [];
  closed = false;
  onopen: (() => void) | null = null;
  onmessage: ((e: { data: string }) => void) | null = null;
  onclose: (() => void) | null = null;
  constructor() { FakeSocket.all.push(this); }
  send(s: string) { this.sent.push(JSON.parse(s)); }
  close() { this.closed = true; this.readyState = 3; }
  /** the server's side */
  accept() { this.readyState = 1; this.onopen?.(); }
  frame(f: object) { this.onmessage?.({ data: JSON.stringify(f) }); }
  drop() { this.readyState = 3; this.onclose?.(); }
  pings() { return this.sent.filter((f) => f.t === 'ping').length; }
}

const listeners: Record<string, () => void> = {};
let reloads = 0;
const { SyncClient } = await import('./client');
const last = () => FakeSocket.all[FakeSocket.all.length - 1];
const tick = (ms: number) => vi.advanceTimersByTimeAsync(ms);

async function live(serverAhead = 0) {
  const c = new SyncClient();
  await c.start();
  await tick(0);
  last().accept();
  last().frame({ t: 'welcome', server_time: Date.now() + serverAhead, reconcile: false });
  expect(c.status).toBe('live');
  return c;
}

beforeEach(() => {
  vi.useFakeTimers();
  FakeSocket.all = [];
  sessions.length = 0;
  renew.mockReset();
  erase.mockClear();
  disk.device = device;
  reloads = 0;
  vi.stubGlobal('WebSocket', FakeSocket);
  vi.stubGlobal('BroadcastChannel', undefined);
  vi.stubGlobal('navigator', { onLine: true });
  vi.stubGlobal('location', { protocol: 'http:', host: 'chorus.test', reload: () => { reloads++; } });
  vi.stubGlobal('localStorage', { getItem: () => null, setItem() {}, removeItem() {} });
  vi.stubGlobal('addEventListener', (type: string, fn: () => void) => { listeners[type] = fn; });
  vi.stubGlobal('document', { hidden: false, addEventListener: (type: string, fn: () => void) => { listeners[`doc:${type}`] = fn; } });
  vi.spyOn(console, 'warn').mockImplementation(() => {});
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('the heartbeat', () => {
  it('pings a silent live socket, then gives it up and reconnects', async () => {
    const c = await live();
    const ws = last();
    await tick(PING_AFTER_MS);
    expect(ws.sent.at(-1)).toMatchObject({ t: 'ping', clock: { wall: expect.any(Number), mono: null, boot_id: null } });
    expect(c.status).toBe('live');
    await tick(PONG_GRACE_MS);
    expect(c.status).toBe('offline');
    expect(ws.closed).toBe(true);
    await tick(1500); // the first backoff
    expect(FakeSocket.all).toHaveLength(2);
    expect(c.status).toBe('connecting');
  });

  it('keeps a socket that answers in time, and a busy one never pings', async () => {
    const c = await live();
    const ws = last();
    for (let i = 0; i < 6; i++) { await tick(10_000); ws.frame({ t: 'ops', scope: 's', ops: [], to: 0 }); }
    expect(ws.pings()).toBe(0);
    await tick(PING_AFTER_MS);
    expect(ws.pings()).toBe(1);
    await tick(PONG_GRACE_MS - 1000);
    ws.frame({ t: 'pong', server_time: Date.now() });
    await tick(PONG_GRACE_MS);
    expect(c.status).toBe('live');
    expect(ws.closed).toBe(false);
    await tick(PING_AFTER_MS);
    expect(ws.pings()).toBe(2);
  });

  it('gives up a socket that never welcomes', async () => {
    const c = new SyncClient();
    await c.start();
    await tick(0);
    last().accept();
    await tick(35_000);
    expect(c.status).toBe('offline');
    expect(last().closed).toBe(true);
  });

  it('is a schedule of silence', () => {
    expect(heartbeat(1000, 1000, null)).toEqual({ act: 'wait', next: 1000 + PING_AFTER_MS });
    expect(heartbeat(PING_AFTER_MS, 0, null)).toEqual({ act: 'ping', next: PING_AFTER_MS + PONG_GRACE_MS });
    expect(heartbeat(PING_AFTER_MS + 5, 0, PING_AFTER_MS)).toEqual({ act: 'wait', next: PING_AFTER_MS + PONG_GRACE_MS });
    expect(heartbeat(PING_AFTER_MS + PONG_GRACE_MS, 0, PING_AFTER_MS).act).toBe('close');
    // heard after the ping: alive again
    expect(heartbeat(PING_AFTER_MS + PONG_GRACE_MS, PING_AFTER_MS + 1, PING_AFTER_MS).act).toBe('wait');
  });
});

describe('renewing a session', () => {
  const unauthenticated = { t: 'error', code: 'unauthenticated', message: 'this device was signed out' };

  const DAY = 86_400_000;
  const signedOut = (at: number, wipe_at: number) =>
    new ApiError(403, 'signed_out', 'this device was signed out', { error: { code: 'signed_out', retry: false }, signed_out: { at, wipe_at } });

  it('ends signed out when the server no longer knows the device, stops connecting, and erases nothing', async () => {
    renew.mockRejectedValue(new ApiError(403, 'forbidden', 'unknown or revoked device'));
    const c = await live();
    last().frame(unauthenticated);
    expect(c.status).toBe('renewing');
    await tick(0);
    expect(c.status).toBe('signed-out');
    expect(c.projection()).toBeNull(); // the kept copy isn't shown
    listeners.online();
    listeners['doc:visibilitychange']();
    await tick(30 * DAY);
    expect(FakeSocket.all).toHaveLength(1);
    expect(renew).toHaveBeenCalledTimes(1);
    expect(erase).not.toHaveBeenCalled(); // no wipe_at: never on a timer
    expect(disk.device).toMatchObject({ device_id: 'd1', signed_out: { at: null, wipe_at: null } });
  });

  it('remembers when the copy goes, and erases it then, by the server clock', async () => {
    const c = await live(60_000); // the server's clock is a minute ahead
    const at = Date.now() + 60_000;
    renew.mockRejectedValue(signedOut(at, at + 2 * DAY));
    last().frame(unauthenticated);
    await tick(0);
    expect(c.status).toBe('signed-out');
    expect(disk.device?.signed_out).toEqual({ at, wipe_at: at + 2 * DAY, offset: 60_000 });
    // `at` is the server's now: two days from here by this clock, not a minute sooner
    await tick(2 * DAY - 1);
    expect(erase).not.toHaveBeenCalled();
    await tick(1);
    expect(erase).toHaveBeenCalledTimes(1);
    expect(c.status).toBe('no-device');
    expect(c.device).toBeNull();
    expect(c.wasErased).toBe(true);
  });

  it('erases at once when that was asked, and on the next start once the time has passed', async () => {
    const c = await live();
    renew.mockRejectedValue(signedOut(Date.now(), Date.now()));
    last().frame(unauthenticated);
    await tick(0);
    expect(erase).toHaveBeenCalledTimes(1);
    expect(c.status).toBe('no-device');

    disk.device = { ...device, signed_out: { at: 0, wipe_at: Date.now() - 1, offset: 0 } };
    const later = new SyncClient();
    await later.start();
    await tick(0);
    expect(erase).toHaveBeenCalledTimes(2);
    expect(later.status).toBe('no-device');
  });

  it('starts signed out without a socket or the copy, until the time comes', async () => {
    disk.device = { ...device, signed_out: { at: Date.now(), wipe_at: Date.now() + DAY, offset: 0 } };
    const c = new SyncClient();
    await c.start();
    await tick(DAY - 1);
    expect(c.status).toBe('signed-out');
    expect(c.projection()).toBeNull();
    expect(FakeSocket.all).toHaveLength(0);
    expect(erase).not.toHaveBeenCalled();
    await tick(1);
    expect(erase).toHaveBeenCalledTimes(1);
  });

  it('erases nothing when another tab linked this browser again meanwhile', async () => {
    disk.device = { ...device, signed_out: { at: Date.now(), wipe_at: Date.now() + DAY, offset: 0 } };
    const c = new SyncClient();
    await c.start();
    disk.device = { ...device, device_id: 'd2' }; // enrolled in another tab
    await tick(DAY);
    expect(erase).not.toHaveBeenCalled();
    expect(reloads).toBe(1);
  });

  it('is signed out by a key that no longer matches, and keeps trying after a stale nonce', async () => {
    renew.mockRejectedValue(new ApiError(401, 'bad_key', 'that key is not this device'));
    const c = await live();
    last().frame(unauthenticated);
    await tick(0);
    expect(c.status).toBe('signed-out');
    renew.mockRejectedValue(new ApiError(401, 'unauthenticated', 'nonce expired'));
    disk.device = device;
    const d = await live();
    last().frame(unauthenticated);
    await tick(0);
    expect(d.status).toBe('offline');
  });

  it('keeps trying after a network error', async () => {
    renew.mockRejectedValue(new TypeError('Failed to fetch'));
    const c = await live();
    last().frame(unauthenticated);
    await tick(0);
    expect(c.status).toBe('offline');
    await tick(1500);
    expect(FakeSocket.all).toHaveLength(2);
    expect(c.status).toBe('connecting');
  });

  it('renews once at a time, and connects only after, with the new session', async () => {
    let done!: (d: typeof device) => void;
    renew.mockReturnValue(new Promise((r) => (done = r)));
    const c = await live();
    const ws = last();
    ws.frame(unauthenticated);
    ws.frame(unauthenticated);
    ws.drop(); // the server closes the socket after its error
    listeners.online();
    await tick(10_000);
    expect(renew).toHaveBeenCalledTimes(1);
    expect(FakeSocket.all).toHaveLength(1);
    expect(c.status).toBe('renewing');
    done({ ...device, session: 's2' });
    await tick(0);
    expect(c.status).toBe('connecting');
    last().accept();
    expect(sessions).toEqual(['s1', 's2']);
    // refused again before a welcome: the next try waits out the backoff
    renew.mockResolvedValue({ ...device, session: 's3' });
    last().frame(unauthenticated);
    await tick(0);
    expect(c.status).toBe('offline');
  });

  it('tells a refusal from a passing failure', () => {
    expect(signedOutBy(signedOut(10, 20))).toEqual({ at: 10, wipe_at: 20 });
    expect(signedOutBy(new ApiError(403, 'signed_out', 'x', {}))).toEqual({ at: null, wipe_at: null });
    expect(signedOutBy(new ApiError(403, 'forbidden', 'x'))).toEqual({ at: null, wipe_at: null });
    expect(signedOutBy(new ApiError(401, 'bad_key', 'x'))).toEqual({ at: null, wipe_at: null });
    expect(signedOutBy(new ApiError(401, 'unauthenticated', 'x'))).toBeNull();
    expect(signedOutBy(new ApiError(503, 'internal', 'x'))).toBeNull();
    expect(signedOutBy(new ApiError(429, 'rate_limited', 'x'))).toBeNull();
    expect(signedOutBy(new TypeError('Failed to fetch'))).toBeNull();
    expect(wipeIn({ wipe_at: null, offset: 0 }, 5)).toBeNull();
    expect(wipeIn({ wipe_at: 100, offset: 30 }, 50)).toBe(20);
  });
});
