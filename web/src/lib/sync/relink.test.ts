// Linking a signed-out browser again for the same account keeps its copy (D-153): the new device
// opens the old device's ops and metadata the way client.ts opens them, and what was written
// before the sign-out and never sent goes out under the new device. Real core (wasm), no server:
// the server stamps the pushing device on each op (ingest) and acks an op it already has by id.
import { readFileSync } from 'node:fs';
import { beforeAll, describe, expect, it } from 'vitest';
import { loadCore } from '../core';
import { WebReplica } from '../core/pkg/chorus_wasm.js';

beforeAll(async () => loadCore(readFileSync(new URL('../core/pkg/chorus_wasm_bg.wasm', import.meta.url))));

const account = '0192f8c2-0000-7000-8000-0000000000a1';
const scope = `account:${account}`;
const clock = (wall: number) => JSON.stringify({ wall, mono: null, boot_id: null });
const welcome = (epoch: string) => JSON.stringify({ t: 'welcome', server_time: 5000, epoch, account_id: account, offset_ms: 0, scopes: [scope], max_seq: {}, reconcile: false, core_min: '0.0.0' });
type Frame = { t: string; batch?: string; ops?: { id: string; device_id?: string | null; seq?: number | null; hlc: string }[] };

function write(r: WebReplica, entity: string, name: string, now: number): string {
  const out = JSON.parse(r.create(JSON.stringify({ kind: 'member.create', scope, entity_id: entity, payload: { name } }), JSON.stringify({ now, tz_offset_min: 0 }), new Uint8Array(10).fill(now % 251)));
  return out.op.id as string;
}

/** What IndexedDB holds after the replica's changes are saved (persist.ts `save`). */
function saved(r: WebReplica): { ops: { id: string }[]; meta: unknown; hlc: string } {
  const ch = JSON.parse(r.takeChanges());
  return { ops: ch.ops, meta: ch.meta, hlc: ch.hlc_last };
}

/** client.ts `start` + `openOps` for the device enrolled in its place. */
function reopen(device: string, node: number, disk: { ops: { id: string }[]; meta: unknown; hlc: string }): WebReplica {
  const r = WebReplica.begin(device, node, disk.meta ? JSON.stringify(disk.meta) : '', disk.hlc);
  r.addOps(JSON.stringify(disk.ops));
  while (!r.indexStep(1000)) { /* indexing */ }
  r.adopt('');
  return r;
}

describe('linking a signed-out browser again', () => {
  it('sends what was written before the sign-out and never sent, under the new device', () => {
    const old = new WebReplica('device-old', 0x0a);
    const unsent = write(old, '0192f8c2-0000-7000-8000-0000000000b1', 'Kai', 1000);
    const disk = saved(old); // never connected: the op waits in the outbox
    old.free();

    const r = reopen('device-new', 0x0b, disk);
    const hello = JSON.parse(r.connect(clock(5000), 'session-new'));
    expect(hello).toMatchObject({ t: 'hello', device_id: 'device-new', token: 'session-new', outbox: 1 });
    const frames = JSON.parse(r.onFrame(welcome('e1'), 5000)) as Frame[];
    const pushed = frames.filter((f) => f.t === 'push').flatMap((f) => f.ops ?? []);
    expect(pushed.map((o) => o.id)).toEqual([unsent]);
    expect(pushed[0].device_id ?? null).toBeNull(); // the server stamps the device that pushes it

    // confirmed once; nothing waits after that
    const batch = frames.find((f) => f.t === 'push')!.batch!;
    r.onFrame(JSON.stringify({ t: 'ack', batch, results: [{ id: unsent, seq: 1, occurred_at: 1000, received_at: 5000, account_id: account, device_id: 'device-new' }] }), 5000);
    r.disconnect();
    expect(JSON.parse(r.connect(clock(6000), 'session-new')).outbox).toBe(0);

    // what the new device writes sorts after what the old one wrote
    const next = write(r, '0192f8c2-0000-7000-8000-0000000000b2', 'Rin', 900); // a clock behind the old one
    const hlc = (ops: { id: string }[], id: string) => (ops.find((o) => o.id === id) as unknown as { hlc: string }).hlc;
    expect(hlc(saved(r).ops, next) > hlc(disk.ops, unsent)).toBe(true);
  });

  it('confirms an op the server already had from the old device, once', () => {
    const old = new WebReplica('device-old', 0x0a);
    const id = write(old, '0192f8c2-0000-7000-8000-0000000000b3', 'Sol', 1000);
    old.connect(clock(1000), 'session-old');
    const sent = (JSON.parse(old.onFrame(welcome('e1'), 1000)) as Frame[]).filter((f) => f.t === 'push');
    expect(sent.flatMap((f) => f.ops ?? []).map((o) => o.id)).toEqual([id]); // sent, never acked: signed out
    old.disconnect();
    const disk = saved(old);
    old.free();

    const r = reopen('device-new', 0x0b, disk);
    r.connect(clock(5000), 'session-new');
    const again = (JSON.parse(r.onFrame(welcome('e1'), 5000)) as Frame[]).find((f) => f.t === 'push')!;
    expect(again.ops!.map((o) => o.id)).toEqual([id]);
    // the server has it under the old device: it acks the id with the stamp it has
    r.onFrame(JSON.stringify({ t: 'ack', batch: again.batch, results: [{ id, seq: 7, occurred_at: 1000, received_at: 1000, account_id: account, device_id: 'device-old' }] }), 5000);
    const kept = saved(r).ops.find((o) => o.id === id) as unknown as { seq: number; device_id: string };
    expect(kept).toMatchObject({ seq: 7, device_id: 'device-old' });
    r.disconnect();
    expect(JSON.parse(r.connect(clock(6000), 'session-new')).outbox).toBe(0);
  });
});

