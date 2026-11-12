import { readFileSync } from 'node:fs';
import { expect, it } from 'vitest';
import { activeViewers } from './hidden';
import { actingOpts, bodyAuthors, bodyPresence, frontView } from './front-view';
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

it('reads Body presence and authors canonically once configured, legacy otherwise', () => {
  const p = JSON.parse(readFileSync(new URL('../../../fixtures/front_runtime/channel_pools.json', import.meta.url), 'utf8')).expect as Projection;
  const account = Object.keys(p.front_systems!)[0];
  const quiet = p.front_systems![account].resolved[account].effective[0].subject_id;
  // a stale legacy timeline is ignored once the account is configured
  p.fronts[account] = { current: [{ subject_type: 'member', subject_id: 'stale', level: 'front', is_primary: true }], switches: [], intervals: [] };
  expect(bodyPresence(p, account)).toEqual([{ subject_type: 'member', subject_id: quiet, level: 'quiet', is_primary: true }]);
  expect(activeViewers(bodyPresence(p, account))).toEqual([]); // A custom layer acquires no fronting role.
  expect(bodyAuthors(p, account)).toEqual([quiet]);
  p.front_systems![account].resolved[account].authorship = [];
  expect(bodyAuthors(p, account)).toEqual([]); // An explicit empty selection selects nobody.
  const legacy = { ...p, front_systems: undefined };
  expect(bodyPresence(legacy, account).map((e) => e.subject_id)).toEqual(['stale']);
  expect(bodyAuthors(legacy, account)).toEqual(['stale']);
});

it('attributes a delete to whoever fronts, and to no one when nobody does', () => {
  const p = { fronts: { a: { current: [{ subject_type: 'member', subject_id: 'm1', level: 'front', is_primary: true }] }, b: { current: [] } } } as unknown as Projection;
  expect(actingOpts(p, 'a')).toEqual({ memberId: 'm1' });
  expect(actingOpts(p, 'b')).toEqual({});
  expect(actingOpts(null, 'a')).toEqual({});
});
