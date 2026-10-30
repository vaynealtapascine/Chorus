import { expect, it } from 'vitest';
import { expandFront, memberSubsystems } from './subsystems';
import type { Projection } from './sync/client';

const row = (fields: Record<string, unknown>) => ({ exists: true, fields });
// garden (a member) is subsystem g with kai and wren inside; wren is subsystem w with moss inside;
// the Stars group fronts as a unit with fox in it and kai in its nested Inner
const p = {
  opaque: 0, fronts: {},
  rows: {
    member: Object.fromEntries(['garden', 'kai', 'wren', 'moss', 'fox'].map((id) => [id, row({ name: id[0].toUpperCase() + id.slice(1) })])
      .concat([['old', row({ name: 'Old', archived_at: 1 })]])),
    member_group: {
      g: row({ name: 'Garden', kind: 'subsystem' }), w: row({ name: 'Wren', kind: 'subsystem', parent_id: 'g' }),
      s: row({ name: 'Stars', kind: 'subsystem' }), s2: row({ name: 'Inner', kind: 'subsystem', parent_id: 's' }),
    },
    pref: {
      'acct||member_subsystem:garden': row({ value: 'g' }),
      'acct||member_subsystem:wren': row({ value: 'w' }),
      'acct||member_subsystem:fox': row({ value: 's' }),
      '||member_subsystem:fox': row({ value: null }),
      'acct|phone|member_subsystem:kai': row({ value: 's2' }),
    },
  },
  sets: { group_membership: Object.fromEntries([['g', 'kai'], ['g', 'wren'], ['g', 'old'], ['w', 'moss'], ['s', 'fox'], ['s2', 'kai']]
    .map(([g, m]) => [`${g}|${JSON.stringify({ member_id: m })}`, true])) },
} as unknown as Projection;
const e = (type: string, id: string, level: string, primary = false) => ({ subject_type: type, subject_id: id, level, is_primary: primary });

it('reads member subsystem links, a pending clear winning and device prefs ignored', () => {
  expect([...memberSubsystems(p, 'acct')]).toEqual([['garden', 'g'], ['wren', 'w']]);
});

it('a fronting subsystem member brings everyone inside, recursively, at its layer', () => {
  const out = expandFront(p, 'acct', [e('member', 'garden', 'front', true), e('member', 'kai', 'cocon')]);
  expect(out.entries).toEqual([e('member', 'garden', 'front', true), e('member', 'kai', 'cocon'), e('member', 'wren', 'front'), e('member', 'moss', 'front')]);
  expect([...out.via]).toEqual([['wren', 'Garden'], ['moss', 'Wren']]);
});

it('a subsystem group fronting as a unit brings its nested members; nothing else changes', () => {
  expect(expandFront(p, 'acct', [e('group', 's', 'front', true)]).entries.slice(1).map((x) => x.subject_id).sort()).toEqual(['fox', 'kai']);
  expect(expandFront(p, 'acct', [e('member', 'fox', 'front', true)]).entries).toHaveLength(1);
});
