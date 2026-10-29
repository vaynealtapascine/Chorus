import { expect, test } from 'vitest';
import fixture from '../../../fixtures/ui_member_order.json';
import { memberOrderKey, orderedMembers } from './member-order';
import type { MemberRow } from './data';

test('manual member order agrees with the Android fixture, including Unicode and ties', () => {
  const members = fixture.members.map(m => ({ ...m, color: '#123456', sigils: [], proxy_tags: [], archived: false, deleted: false })) as MemberRow[];
  for (const member of members) expect(memberOrderKey(member)).toBe(fixture.keys[member.id as keyof typeof fixture.keys]);
  expect(orderedMembers(members).map(m => m.id)).toEqual(fixture.order);
  expect(orderedMembers([...members].reverse()).map(m => m.id)).toEqual(fixture.order);
});
