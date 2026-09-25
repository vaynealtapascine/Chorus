import { describe, expect, it } from 'vitest';
import { applyDelta, changesSince, versionOf, type Delta } from './delta';
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
    expect(next).not.toBe(p);
    expect(next.rows).not.toBe(p.rows);
    const again = applyDelta(next, { ...empty, rows: { member: { b: { exists: true, fields: { name: 'Mo' } } } } });
    expect(again.rows.member).not.toBe(next.rows.member); // copy-on-write…
    expect(next.rows.member.b).toBeUndefined(); // …so the old projection is unchanged
  });
  it('patches the message table in place, with a version and what each key was', () => {
    const p = base();
    const table = p.rows.message;
    const m1 = table.m1;
    expect(versionOf(table)).toBe(0);
    let next = applyDelta(p, { ...empty, rows: { message: { m2: { exists: true, fields: { text: 'yo' } } } } });
    next = applyDelta(next, { ...empty, rows: { message: { m1: { exists: true, fields: { text: 'hi!' } } } } });
    next = applyDelta(next, { ...empty, rows: { message: { m2: null } } });
    expect(next.rows.message).toBe(table);
    expect(versionOf(table)).toBe(3);
    expect(Object.keys(table)).toEqual(['m1']);
    expect(changesSince(table, 3)).toEqual(new Map());
    expect(changesSince(table, 1)).toEqual(new Map([['m1', m1], ['m2', { exists: true, fields: { text: 'yo' } }]]));
    expect(changesSince(table, 0)).toEqual(new Map([['m2', undefined], ['m1', m1]]));
    for (let i = 0; i < 20; i++) next = applyDelta(next, { ...empty, rows: { message: { m1: { exists: true, fields: { text: `${i}` } } } } });
    expect(changesSince(table, 0)).toBeNull(); // further back than the log keeps: rebuild
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
