import { expect, it } from 'vitest';
import { candidates, counts, groupLine, layerName, membershipOp, names, wouldLoop } from './member-directory';
import type { Projection } from './sync/client';

const row = (fields: Record<string, unknown>) => ({ exists: true, fields });
const p = {
  rows: {
    member: { a: row({ name: 'Ann' }), b: row({ name: 'Bo' }), c: row({ name: 'Cy', archived_at: 5 }), d: row({ name: 'Di' }) },
    member_group: { g: row({ name: 'Day', kind: 'group' }), s: row({ name: 'Bo inside', kind: 'subsystem' }) },
    pref: { '||member_subsystem:b': row({ value: 's' }) },
  },
  sets: { group_membership: { 'g|{"member_id":"a"}': true, 's|{"member_id":"d"}': true } },
  fronts: { acc: { current: [{ subject_type: 'member', subject_id: 'a', level: 'cocon', is_primary: false }] } },
  opaque: 0,
} as unknown as Projection;

it('counts active, fronting and archived members', () => {
  expect(counts(p, 'acc')).toEqual({ all: 3, fronting: 1, archived: 1 });
});
it('names the layer a member fronts at', () => {
  expect(layerName(p, 'acc', 'a')).toBe('Co-conscious');
  expect(layerName(p, 'acc', 'd')).toBeNull();
});
it('lists names and group lines', () => {
  expect(names([{ name: 'A' }, { name: 'B' }, { name: 'C' }, { name: 'D' }] as never)).toBe('A, B, C +1');
  expect(groupLine(1, undefined)).toBe('1 member');
  expect(groupLine(3, { mode: 'private' })).toBe('3 members · only our system');
  expect(groupLine(3, { mode: 'friends' })).toBe('3 members · friends can see it');
});
it('keeps a subsystem from being put inside itself', () => {
  expect(wouldLoop(p, 'acc', 'b', 'b')).toBe(true);
  const list = candidates(p, 'acc', { id: 's', name: 'Bo inside', kind: 'subsystem', deleted: false });
  expect(list.map((c) => c.member.id)).toEqual(['d', 'a']);
  expect(list[0].inside).toBe(true);
  expect(membershipOp(true)).toBe('group.remove_member');
});
