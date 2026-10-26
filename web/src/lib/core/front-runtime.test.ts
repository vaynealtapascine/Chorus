import { readFileSync } from 'node:fs';
import { beforeAll, expect, it } from 'vitest';
import { core, loadCore } from './index';
import { WebReplica } from './pkg/chorus_wasm.js';
import { applyDelta, type Delta } from '../sync/delta';
import type { Projection } from '../sync/client';

beforeAll(async () => {
  await loadCore(readFileSync(new URL('./pkg/chorus_wasm_bg.wasm', import.meta.url)));
});

const body = '0192f8c2-0000-7000-8000-0000000000a1';
const source = '0192f8c2-0000-7000-8000-0000000000a2';
const member = '0192f8c2-0000-7000-8000-0000000000b1';

it('keeps runtime named-front deltas equal to the shared history fold across edits and removal', () => {
  const replica = new WebReplica('device', 1);
  let visible: { id: string; [key: string]: unknown }[] = [];
  let client = JSON.parse(replica.projection()) as Projection;
  replica.projectionDelta();
  let n = 0;
  const check = () => {
    const d = JSON.parse(replica.projectionDelta()) as Delta;
    client = d.full ? JSON.parse(replica.projection()) as Projection : applyDelta(client, d);
    expect(client).toEqual(JSON.parse(replica.projection()));
    if (visible.length) {
      const folded = core.foldFrontSystem(body, visible);
      expect(client.front_systems?.[body]).toEqual({ system: folded.system, resolved: folded.resolved });
    } else expect(client.front_systems).toBeUndefined();
  };
  const create = (kind: string, front: string, payload: unknown) => {
    n++;
    const result = JSON.parse(replica.create(JSON.stringify({ kind, scope: `account:${body}`, entity_id: front, payload }),
      JSON.stringify({ now: 1_790_000_000_000 + n * 1000 }), new Uint8Array(10).fill(n)));
    visible.push(result.op);
    check();
    return result.op.id as string;
  };
  try {
    create('front.layer', body, { layer_id: 'quiet', name: 'Fronting', level: -10 });
    const first = create('front.change', source, { action: 'switch', entries: [{ subject_type: 'member', subject_id: member, layer_id: 'quiet' }] });
    create('front.define', body, { follow_source: source, purpose_layers: { mentions: ['quiet'], reading: [] } });
    expect(client.front_systems?.[body].resolved[body].purpose_candidates.mentions.map((e) => e.subject_id)).toEqual([member]);
    create('front.change', body, { action: 'switch', entries: [] });
    expect(client.front_systems?.[body].resolved[body].state).toBe('overridden');
    create('front.change', body, { action: 'resume' });
    create('front.change', source, { action: 'amend', target_op_id: first, note: null, entries: [] });
    expect(client.front_systems?.[body].resolved[body].state).toBe('following');
    const removed = visible.map((o) => o.id);
    visible = [];
    replica.absorb('[]', JSON.stringify(removed));
    check();
  } finally {
    replica.free();
  }
});
