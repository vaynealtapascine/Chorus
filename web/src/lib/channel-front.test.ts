import { readFileSync } from 'node:fs';
import { beforeAll, expect, it } from 'vitest';
import { loadCore } from './core';
import { channelFrontChoices, channelFrontId, channelReaders, channelSpeaker } from './channel-front';
import type { Projection } from './sync/client';

const load = () => JSON.parse(readFileSync(new URL('../../../fixtures/front_runtime/channel_pools.json', import.meta.url), 'utf8')).expect as Projection;
const account = '0192f8c2-0000-7000-8000-0000000000a1';
const other = '0192f8c2-0000-7000-8000-0000000000a2';
const quiet = '0192f8c2-0000-7000-8000-0000000000b1';
const pref = (value: unknown) => ({ exists: true, fields: { value }, clocks: {} });

beforeAll(async () => loadCore(readFileSync(new URL('./core/pkg/chorus_wasm_bg.wasm', import.meta.url))));

it('selects the channel front from its own pref and lists only live fronts, Body first', () => {
  const p = load();
  expect(channelFrontId(p, account, 'c')).toBe(account);
  p.rows.pref = { [`||authorship_front:c`]: pref(other) } as never;
  expect(channelFrontId(p, account, 'c')).toBe(other);
  expect(channelFrontId(p, account, 'd')).toBe(account);
  expect(channelFrontChoices(p, account).map((f) => f.id)).toEqual([account, other]);
  expect(channelFrontChoices({ ...p, front_systems: undefined }, account)).toEqual([]);
});

it('uses the channel front pools for the default speaker and per-member reads', () => {
  const p = load();
  const context = { mode: 'front' as const, members: [quiet] };
  expect(channelSpeaker(p, account, 'c', context)).toEqual([quiet]);
  p.rows.pref = { [`||authorship_front:c`]: pref(other) } as never;
  expect(channelSpeaker(p, account, 'c', context)).toEqual([]); // The other front has no authorship candidates.
  // reading pools are empty in both fronts: only the account marks, never a display fronter
  expect(channelReaders(p, account, 'c', true, [{ member_id: quiet, is_primary: true, level: 'front' }], [quiet])).toEqual(['']);
  p.front_systems![account].resolved[other].purpose_candidates.reading = [{ subject_type: 'member', subject_id: quiet, layer_id: 'quiet', is_primary: false }];
  expect(channelReaders(p, account, 'c', true, [], [quiet])).toEqual(['', quiet]);
  expect(channelReaders(p, account, 'c', true, [], [])).toEqual(['']); // Only own members acquire read marks.
});

it('keeps legacy defaults for an unconfigured account', () => {
  const p = { ...load(), front_systems: undefined };
  const fronting = [{ member_id: 'kai', is_primary: false, level: 'front' }];
  expect(channelSpeaker(p, account, 'c', { mode: 'front', fronting, members: ['kai'] })).toEqual(['kai']);
  expect(channelReaders(p, account, 'c', true, fronting, ['kai'])).toEqual(['', 'kai']);
});
