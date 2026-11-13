import { describe, expect, it } from 'vitest';
import { arrive, noArrivals, seen } from './feed-arrivals';

const away = { atTop: false, justPosted: null };
const top = { atTop: true, justPosted: null };

describe('new posts pill', () => {
  it('starts quiet on the first look and on a tab change', () => {
    expect(arrive(noArrivals, 'all', 'p1', away)).toEqual({ state: { tab: 'all', newest: 'p1', pill: false }, scrollTop: false });
    const before = { tab: 'all', newest: 'p1', pill: true };
    expect(arrive(before, 'entries', 'p9', away)).toEqual({ state: { tab: 'entries', newest: 'p9', pill: false }, scrollTop: false });
  });

  it('changes nothing when the newest post is the same, keeping the pill', () => {
    const before = { tab: 'all', newest: 'p1', pill: true };
    expect(arrive(before, 'all', 'p1', top)).toEqual({ state: before, scrollTop: false });
  });

  it('scrolls to the top for our own post or a reader at the top', () => {
    const before = { tab: 'all', newest: 'p1', pill: true };
    expect(arrive(before, 'all', 'p2', { atTop: false, justPosted: 'p2' })).toEqual({ state: { tab: 'all', newest: 'p2', pill: false }, scrollTop: true });
    expect(arrive(before, 'all', 'p2', top)).toEqual({ state: { tab: 'all', newest: 'p2', pill: false }, scrollTop: true });
  });

  it('shows the pill for another post to a reader further down', () => {
    const before = { tab: 'all', newest: 'p1', pill: false };
    expect(arrive(before, 'all', 'p2', { atTop: false, justPosted: 'p1' })).toEqual({ state: { tab: 'all', newest: 'p2', pill: true }, scrollTop: false });
  });

  it('never shows the pill for a feed that became empty', () => {
    const before = { tab: 'all', newest: 'p1', pill: false };
    expect(arrive(before, 'all', null, away)).toEqual({ state: { tab: 'all', newest: null, pill: false }, scrollTop: false });
  });

  it('drops the pill once seen', () => {
    expect(seen({ tab: 'all', newest: 'p', pill: true })).toEqual({ tab: 'all', newest: 'p', pill: false });
    expect(seen(null)).toBeNull();
  });
});
