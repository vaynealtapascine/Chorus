import { readFileSync } from 'node:fs';
import { beforeAll, expect, it } from 'vitest';
import { loadCore } from './core';
import { WebReplica } from './core/pkg/chorus_wasm.js';
import { FrontHistoryCache } from './front-history';
import { applyDelta, type Delta } from './sync/delta';
import type { Projection } from './sync/client';

beforeAll(async () => loadCore(readFileSync(new URL('./core/pkg/chorus_wasm_bg.wasm', import.meta.url))));

it('invalidates offline history for past corrections and removals while reusing it across unrelated deltas', () => {
  const fixture = JSON.parse(readFileSync(new URL('../../../fixtures/front_runtime/channel_pools.json', import.meta.url), 'utf8'));
  const ops = fixture.args[0] as { id: string; entity_id: string; kind: string }[];
  const body = ops[0].entity_id;
  const source = ops[1].entity_id;
  const r = new WebReplica('device', 1);
  let ownReads = 0, effectiveReads = 0;
  const reader = {
    frontSystemHistory: (account: string) => { ownReads++; return r.frontSystemHistory(account); },
    effectiveFrontHistory: (account: string, front: string) => { effectiveReads++; return r.effectiveFrontHistory(account, front); },
  };
  const cache = new FrontHistoryCache();
  try {
    r.absorb(JSON.stringify(ops), '[]');
    let p = JSON.parse(r.projection()) as Projection;
    r.projectionDelta();
    const read = () => cache.read(reader, p, body)!;
    const initial = read();
    expect(initial.effective.current?.state).toBe('following');
    expect(read().saved.switches).toHaveLength(0);
    expect(ownReads).toBe(1); expect(effectiveReads).toBe(1);
    const update = () => { p = applyDelta(p, JSON.parse(r.projectionDelta()) as Delta); };
    r.create(JSON.stringify({ kind: 'member.create', scope: `account:${body}`, entity_id: '0192f8c2-0000-7000-8000-0000000000b1', payload: { name: 'Renamed' } }), '{"now":4000}', new Uint8Array(10).fill(10));
    update(); read();
    expect(read()).toBe(initial);
    expect(ownReads).toBe(1); expect(effectiveReads).toBe(1);
    const before = p.front_systems?.[body];
    const amend = JSON.parse(r.create(JSON.stringify({ kind: 'front.change', scope: `account:${body}`, entity_id: source, payload: { action: 'amend', target_op_id: ops[1].id, note: 'Past correction' } }), '{"now":5000}', new Uint8Array(10).fill(11))).op.id;
    update(); read();
    expect(p.front_systems?.[body]).toEqual(before); // Current presence/configuration did not change.
    expect(ownReads).toBe(2); expect(effectiveReads).toBe(2);
    expect(cache.read(reader, p, body, source)!.saved.switches[0].note).toBe('Past correction');
    expect(ownReads).toBe(2); expect(effectiveReads).toBe(3);
    r.absorb('[]', JSON.stringify([ops[1].id])); update();
    expect(read().effective.current?.effective).toEqual([]);
    expect(ownReads).toBe(3); expect(effectiveReads).toBe(4);
    r.absorb('[]', JSON.stringify([...ops.map((o) => o.id), amend])); update();
    expect(cache.read(reader, p, body)).toBeNull();
    expect(cache.read(reader, p, 'another-account')).toBeNull();
    expect(cache.read(reader, p, body, 'missing-front')).toBeNull();
  } finally { r.free(); }
});
