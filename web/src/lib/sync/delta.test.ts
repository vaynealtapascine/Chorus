import { describe, expect, it } from 'vitest';
import { applyDelta, type Delta } from './delta';
import type { Projection } from './client';

const base = (): Projection => ({
  rows: { member: { a: { exists: true, fields: { name: 'Kai' } } }, message: { m1: { exists: true, fields: { text: 'hi' } } } },
  sets: { reaction: { 'm1|x': true } },
  fronts: { acct: { current: [], switches: [], intervals: [] } },
  opaque: 0,
});
const empty: Delta = { rows: {}, sets: {}, fronts: {}, reviews: {}, opaque: 0, full: false };

describe('applyDelta', () => {
  it('adds, replaces and removes rows without touching other tables', () => {
    const p = base();
    const next = applyDelta(p, {
      ...empty,
      rows: { message: { m2: { exists: true, fields: { text: 'yo' } }, m1: null } },
    });
    expect(Object.keys(next.rows.message)).toEqual(['m2']);
    expect(next.rows.member).toBe(p.rows.member); // shared, not copied
    expect(p.rows.message.m1).toBeDefined(); // the old projection is unchanged
    expect(next).not.toBe(p);
  });
  it('drops emptied tables and handles sets, fronts and reviews', () => {
    const next = applyDelta(base(), {
      ...empty,
      sets: { reaction: { 'm1|x': null } },
      fronts: { acct: null },
      reviews: { acct: [{ id: 'r' }] },
      opaque: 2,
    });
    expect(next.sets.reaction).toBeUndefined();
    expect(next.fronts.acct).toBeUndefined();
    expect(next.reviews?.acct).toEqual([{ id: 'r' }]);
    expect(next.opaque).toBe(2);
    const cleared = applyDelta(next, { ...empty, reviews: { acct: null }, opaque: 2 });
    expect(cleared.reviews).toBeUndefined();
  });
});
