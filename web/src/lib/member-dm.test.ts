import { expect, it } from 'vitest';
import { memberDmPlan } from './member-dm';
import type { Projection } from './sync/client';

const row = (fields: Record<string, unknown>) => ({ exists: true, fields, clocks: {} });
const p = (channel: Record<string, unknown> = {}) => ({ rows: {
  space: { home: row({ kind: 'internal', name: 'Home' }) },
  member: { a: row({ name: 'Ada' }), b: row({ name: 'Bo' }) },
  channel,
}, sets: {}, fronts: {}, opaque: 0 }) as unknown as Projection;

it('finds the chat between two of our members or plans a new one', () => {
  const made = memberDmPlan(p(), 'a', 'b', () => 'new');
  expect(made?.scope).toBe('space:home');
  expect(made?.create).toMatchObject({ space_id: 'home', kind: 'member_dm', name: 'Ada & Bo', member_ids: ['a', 'b'] });
  const had = memberDmPlan(p({ dm: row({ space_id: 'home', kind: 'member_dm', name: 'x', member_ids: ['b', 'a'] }) }), 'a', 'b', () => 'new');
  expect(had).toEqual({ id: 'dm', scope: 'space:home' });
  expect(memberDmPlan(p(), 'a', 'a', () => 'new')).toBeNull();
  expect(memberDmPlan(p(), null, 'b', () => 'new')).toBeNull();
});
