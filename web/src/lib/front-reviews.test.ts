import { readFileSync } from 'node:fs';
import { beforeAll, expect, it } from 'vitest';
import { loadCore, type FoldedFrontSystem } from './core';
import { WebReplica } from './core/pkg/chorus_wasm.js';
import { namedReviewMerge } from './front-reviews';

beforeAll(async () => loadCore(readFileSync(new URL('./core/pkg/chorus_wasm_bg.wasm', import.meta.url))));
const id = (n: number) => `0192f8c2-0000-7000-8000-${n.toString().padStart(12, '0')}`;
const body = id(101), source = id(102);
const entry = (n: number) => ({ subject_type: 'member', subject_id: id(n), layer_id: 'front', is_primary: false });
const op = (n: number, kind: string, front: string, payload: unknown) => ({
  id: id(n), kind, v: 1, scope: `account:${body}`, entity_id: front, payload,
  hlc: `${(n * 1000).toString(16).padStart(12, '0')}-0000-00000001`, device_at: n * 1000,
  occurred_at: n * 1000, received_at: n * 1000, seq: n, seen_seq: 0,
  account_id: body, device_id: n % 2 ? 'phone' : 'desktop',
});

for (const legacy of [false, true]) for (const state of ['following', 'override', 'independent']) {
  it(`merges an old ${legacy ? 'legacy' : 'named'} switch/add review historically while preserving today's ${state} state`, () => {
    const replica = new WebReplica('review-test', 1);
    const ops = [
      op(1, 'front.define', source, { name: 'Source' }),
      op(2, 'front.define', body, { name: 'Body', follow_source: state === 'independent' ? null : source }),
      legacy ? op(3, 'front.switch', body, { entries: [{ subject_type: 'member', subject_id: id(201), level: 'front' }] })
        : op(3, 'front.change', body, { action: 'switch', entries: [entry(201)] }),
      op(4, 'front.change', body, { action: 'add', entry: entry(202) }),
      op(5, 'front.change', body, { action: 'switch', entries: [entry(203)], note: 'Today' }),
      op(6, 'front.change', source, { action: 'switch', entries: [entry(204)] }),
      ...(state === 'following' ? [op(7, 'front.change', body, { action: 'resume' })] : []),
    ];
    try {
      replica.absorb(JSON.stringify(ops), '[]');
      const before = JSON.parse(replica.frontSystemHistory(body)) as FoldedFrontSystem;
      const review = { switch_a: id(3), switch_b: id(4) };
      const payloads = namedReviewMerge(before.histories[body].switches, review)!;
      for (const [index, payload] of payloads.entries()) {
        replica.create(JSON.stringify({ kind: 'front.change', scope: `account:${body}`, entity_id: body, payload }),
          JSON.stringify({ now: 100_000 + index }), new Uint8Array(10).fill(50 + index));
      }
      const after = JSON.parse(replica.frontSystemHistory(body)) as FoldedFrontSystem;
      expect(after.resolved[body].state).toBe(before.resolved[body].state);
      expect(after.resolved[body].effective).toEqual(before.resolved[body].effective);
      const rows = after.histories[body].switches;
      const replacement = rows.find((row) => !ops.some((o) => o.id === row.id))!;
      expect(replacement.occurred_at).toBe(4000);
      expect(replacement.history_only).toBe(true);
      expect(replacement.resulting_front.map((e) => e.subject_id)).toEqual([id(201), id(202)]);
      expect(rows.filter((row) => [id(3), id(4)].includes(row.id)).every((row) => row.retracted)).toBe(true);
      expect(rows.find((row) => row.id === id(5))?.note).toBe('Today');
      expect(namedReviewMerge(rows, review)).toBeNull();
      expect(namedReviewMerge(rows, { switch_a: id(3), switch_b: 'missing' })).toBeNull();
    } finally { replica.free(); }
  });
}
