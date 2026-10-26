import { readFileSync } from 'node:fs';
import { expect, it } from 'vitest';
import { frontView } from './front-view';
import type { Projection } from './sync/client';

it('shows canonical Body layers without exposing an unavailable recovery fallback', () => {
  const p = JSON.parse(readFileSync(new URL('../../../fixtures/front_runtime/channel_pools.json', import.meta.url), 'utf8')).expect as Projection;
  const account = Object.keys(p.front_systems!)[0];
  const view = frontView(p, account);
  expect(view.name).toBe('Body'); expect(view.state).toBe('following');
  expect(view.entries[0].layer_id).toBe('quiet');
  const body = p.front_systems![account].resolved[account];
  body.state = 'unavailable';
  expect(body.effective).not.toHaveLength(0); // Saved fallback exists, but is not confirmed.
  expect(frontView(p, account).entries).toEqual([]);
  body.state = 'overridden';
  expect(frontView(p, account).entries).toEqual(body.effective);
  body.deleted = true;
  expect(frontView(p, account).entries).toEqual([]);
});

it('keeps legacy IDs/order and does not use Body for an unknown selected front', () => {
  const p: Projection = { rows: {}, sets: {}, opaque: 0, fronts: { account: {
    current: [{subject_type:'member',subject_id:'member',level:'cocon',is_primary:false}], switches: [], intervals: [],
  } } };
  expect(frontView(p, 'account').entries[0].layer_id).toBe('cocon');
  expect(frontView(p, 'account', 'missing').entries).toEqual([]);
  expect(frontView(p, 'account', 'missing').state).toBe('unavailable');
});
