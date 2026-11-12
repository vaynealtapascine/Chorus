import { expect, test } from 'vitest';
import fixture from '../../../fixtures/ui_member_order.json';
import { memberOrderKey, orderedMembers } from './member-order';
import type { MemberRow } from './data';

test('manual member order agrees with the Android fixture, including Unicode and ties', () => {
  const members = fixture.members.map(m => ({ ...m, name: 'name_utf16' in m ? String.fromCharCode(...m.name_utf16!) : m.name, color: '#123456', sigils: [], proxy_tags: [], archived: false, deleted: false })) as MemberRow[];
  for (const member of members) expect(memberOrderKey(member)).toBe(fixture.keys[member.id as keyof typeof fixture.keys]);
  expect(orderedMembers(members).map(m => m.id)).toEqual(fixture.order);
  expect(orderedMembers([...members].reverse()).map(m => m.id)).toEqual(fixture.order);
});

import { between, orderPatches } from './member-order';

const mk = (id: string, name: string, sort_key?: string) => ({ id, name, sort_key, color: '#123456', sigils: [], proxy_tags: [], archived: false, deleted: false }) as MemberRow;

test('between finds printable room between neighbours', () => {
  expect(between(null, null)).toBe('O');
  const k = between('O', 'P');
  expect('O' < k && k < 'P').toBe(true);
  expect(() => between('b', 'a')).toThrow();
});

test('reordering writes keys that sort members into the target order', () => {
  const list = [mk('a', 'Ann'), mk('b', 'Bo'), mk('c', 'Cy')];
  const patches = orderPatches(list, ['c', 'a', 'b'], new Set(['c']));
  expect(patches).toHaveLength(1);
  const applied = list.map((m) => ({ ...m, sort_key: patches.find(([id]) => id === m.id)?.[1] ?? m.sort_key }));
  expect(orderedMembers(applied).map((m) => m.id)).toEqual(['c', 'a', 'b']);
  expect(orderPatches(list, ['a', 'b', 'c'], new Set(['a']))).toEqual([]);
});
