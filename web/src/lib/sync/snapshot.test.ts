import { readFileSync } from 'node:fs';
import { beforeAll, describe, expect, it } from 'vitest';
import { loadCore } from '../core';
import { WebReplica } from '../core/pkg/chorus_wasm.js';
import { readSnapshot } from './snapshot';
import type { Snapshot } from './persist';

beforeAll(async () => {
  await loadCore(readFileSync(new URL('../core/pkg/chorus_wasm_bg.wasm', import.meta.url)));
});

const ops = JSON.stringify([{
  id: '0192f8c2-0000-7000-8000-000000000001', kind: 'member.create',
  scope: 'account:0192f8c2-0000-7000-8000-0000000000a1',
  entity_id: '0192f8c2-0000-7000-8000-0000000000b1',
  hlc: '0000000003e8-0000-00000001', device_at: 1000, seq: 1, payload: { name: 'Kai' },
}]);

function saved(): Snapshot {
  const replica = WebReplica.restore('dev', 1, '', ops, '');
  try {
    return { projection: replica.projection(), digest: replica.projectionDigest(), at: 1000 };
  } finally {
    replica.free();
  }
}

describe('saved projections through the actual wasm core', () => {
  it('shows a compatible snapshot and adopts its indexed operations', () => {
    const snapshot = saved();
    expect(readSnapshot(snapshot)).toEqual(JSON.parse(snapshot.projection));
    const opened = WebReplica.begin('dev', 1, '', '');
    try {
      opened.addOps(ops);
      expect(opened.indexStep(1)).toBe(true);
      expect(opened.adopt(snapshot.digest)).toBe(true);
      expect(JSON.parse(opened.projectionDelta()).full).toBe(false);
      expect(opened.projection()).toBe(snapshot.projection);
    } finally {
      opened.free();
    }
  });

  it('rebuilds unversioned, future or malformed digests with identical operations', () => {
    const snapshot = saved();
    const token = JSON.parse(snapshot.digest);
    for (const digest of [
      JSON.stringify(token.ops),
      JSON.stringify({ ...token, version: 0 }),
      JSON.stringify({ ...token, version: token.version + 1 }),
      JSON.stringify({ ...token, unknown: true }),
      JSON.stringify({ ...token, ops: { count: 1, xor: `aé${'a'.repeat(29)}` } }),
      '{bad json',
    ]) {
      expect(readSnapshot({ ...snapshot, digest })).toBeNull();
      const opened = WebReplica.begin('dev', 1, '', '');
      try {
        opened.addOps(ops);
        expect(opened.adopt(digest)).toBe(false);
        expect(JSON.parse(opened.projectionDelta()).full).toBe(true);
        expect(opened.projection()).toBe(snapshot.projection);
      } finally {
        opened.free();
      }
    }
  });

  it('discards broken projection JSON before the first UI display', () => {
    const snapshot = saved();
    for (const projection of ['{broken', 'null', '[]', '{}', JSON.stringify({ rows: null, sets: {}, fronts: {}, opaque: 0 })]) {
      expect(readSnapshot({ ...snapshot, projection })).toBeNull();
    }
    expect(readSnapshot(null)).toBeNull();
  });
});
