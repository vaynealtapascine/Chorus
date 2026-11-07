import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { beforeAll, expect, it } from 'vitest';
import { loadCore } from './core';
import { claimedHere, handleMentions, memberHandles, normalizeClaim, shownHandle, validClaim } from './handles';
import type { MemberRow } from './data';
import type { Projection } from './sync/client';

beforeAll(async () => {
  await loadCore(readFileSync(fileURLToPath(new URL('./core/pkg/chorus_wasm_bg.wasm', import.meta.url))));
});

const row = (fields: Record<string, unknown>) => ({ exists: true, fields });
// Foxglove (display name over a private full name), Kai inside the Littles, Ren with claims
const projection = () => ({
  opaque: 0, fronts: {},
  rows: {
    account: { acct: row({ handle: 'lanterns' }) },
    member: {
      fox: row({ name: 'Foxglove Marsh', display_name: 'Foxglove' }),
      kai: row({ name: 'Kai' }),
      ren: row({ name: 'Ren', handles: ['ren', 'renny'] }),
    },
    member_group: { g: row({ name: 'Littles', kind: 'subsystem' }) },
  },
  sets: { group_membership: { [`g|${JSON.stringify({ member_id: 'kai' })}`]: true } },
}) as unknown as Projection;

it('works out defaults from what friends may see, and claims come first', () => {
  const p = projection();
  const h = memberHandles(p, 'acct');
  expect(h.get('fox')).toEqual({ default: 'foxglove.lanterns', claimed: [], shown: 'foxglove.lanterns' });
  expect(h.get('kai')?.default).toBe('kai.littles.lanterns');
  expect(h.get('ren')).toEqual({ default: 'ren.lanterns', claimed: ['ren', 'renny'], shown: 'ren' });
  expect(memberHandles(p, 'acct')).toBe(h); // cached while nothing it reads changed
});

it('shows ours from the core and another account’s from its card', () => {
  const own = memberHandles(projection(), 'acct');
  expect(shownHandle({ id: 'kai' }, own)).toBe('kai.littles.lanterns');
  expect(shownHandle({ id: 'june', handle: 'june.moons' }, own)).toBe('june.moons');
  expect(shownHandle(undefined, own)).toBeUndefined();
});

it('every handle names its member for mentions; a shared one names nobody', () => {
  const own = memberHandles(projection(), 'acct');
  const people = [
    { id: 'kai', name: 'Kai' }, { id: 'ren', name: 'Ren', handles: ['ren', 'renny'] },
    { id: 'june', name: 'June', account_id: 'other', handle: 'june.moons', handles: ['jj'] },
    { id: 'twin', name: 'Twin', account_id: 'other', handle: 'renny' },
  ] as MemberRow[];
  const m = handleMentions(own, people);
  expect(m.get('kai.littles.lanterns')).toBe('kai');
  expect(m.get('ren.lanterns')).toBe('ren');
  expect(m.get('june.moons')).toBe('june');
  expect(m.get('jj')).toBe('june');
  expect(m.has('renny')).toBe(false);
});

it('claims are account-handle shaped and checked against this device', () => {
  expect(validClaim('fox_2')).toBe(true);
  expect(validClaim('fox.lanterns')).toBe(false);
  expect(validClaim('F')).toBe(false);
  expect(normalizeClaim(' @Fox Glove-2 ')).toBe('fox_glove_2');
  const p = projection();
  expect(claimedHere(p, 'acct', 'lanterns', 'fox')).toBe('your account');
  expect(claimedHere(p, 'acct', 'renny', 'fox')).toBe('Ren');
  expect(claimedHere(p, 'acct', 'renny', 'ren')).toBeNull();
});
