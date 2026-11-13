import { describe, expect, it } from 'vitest';
import { tagLine, trendingTags, type RailPost } from './journal-rail';

const DAY = 24 * 60 * 60 * 1000;
const NOW = 100 * DAY;
const post = (tags: string[], daysAgo: number, extra: Partial<RailPost> = {}): RailPost =>
  ({ tags, occurred_at: NOW - daysAgo * DAY, authors: ['a'], ...extra });

describe('trendingTags', () => {
  it('orders this week by count', () => {
    const r = trendingTags([post(['x'], 1), post(['y'], 1), post(['y'], 2), post(['z'], 3), post(['z'], 3), post(['z'], 4)], NOW);
    expect(r.map((t) => [t.tag, t.count, t.thisWeek])).toEqual([['z', 3, true], ['y', 2, true], ['x', 1, true]]);
  });
  it('breaks ties by newest use', () => {
    const r = trendingTags([post(['old'], 5), post(['new'], 1)], NOW);
    expect(r.map((t) => t.tag)).toEqual(['new', 'old']);
  });
  it('fills from all time when fewer than the limit were used this week', () => {
    const r = trendingTags([post(['now'], 1), post(['a'], 30), post(['a'], 31), post(['b'], 40), post(['now'], 50)], NOW);
    expect(r).toEqual([
      { tag: 'now', count: 1, newestBy: 'a', thisWeek: true },
      { tag: 'a', count: 2, newestBy: 'a', thisWeek: false },
      { tag: 'b', count: 1, newestBy: 'a', thisWeek: false },
    ]);
  });
  it('ignores deleted posts', () => {
    expect(trendingTags([post(['gone'], 1, { deleted: true })], NOW)).toEqual([]);
  });
  it('merges case-insensitively and keeps the first spelling', () => {
    const r = trendingTags([post(['Garden'], 3), post(['garden'], 1), post(['#GARDEN'], 2)], NOW);
    expect(r).toHaveLength(1);
    expect(r[0]).toMatchObject({ tag: 'Garden', count: 3 });
  });
  it('counts a post once however often it repeats a tag', () => {
    expect(trendingTags([post(['a', 'A'], 1)], NOW)[0].count).toBe(1);
  });
  it('respects the limit', () => {
    const rows = ['a', 'b', 'c', 'd', 'e'].map((t) => post([t], 1));
    expect(trendingTags(rows, NOW)).toHaveLength(3);
    expect(trendingTags(rows, NOW, 2)).toHaveLength(2);
  });
  it('reports the first author of the newest post', () => {
    const r = trendingTags([post(['t'], 3, { authors: ['old'] }), post(['t'], 1, { authors: ['new', 'other'] })], NOW);
    expect(r[0].newestBy).toBe('new');
  });
  it('has no author when the newest post has none', () => {
    expect(trendingTags([post(['t'], 1, { authors: [] })], NOW)[0].newestBy).toBeNull();
  });
});

describe('tagLine', () => {
  const t = (count: number, thisWeek: boolean) => ({ tag: 'x', count, newestBy: 'a', thisWeek });
  it('says this week for bigger counts', () => expect(tagLine(t(12, true), 'Foxglove')).toBe('12 posts this week'));
  it('names the newest author for small counts', () => expect(tagLine(t(4, true), 'Foxglove')).toBe('4 posts · newest from Foxglove'));
  it('uses the singular', () => {
    expect(tagLine(t(1, true), null)).toBe('1 post this week');
    expect(tagLine(t(1, false), 'Kit')).toBe('1 post · newest from Kit');
  });
  it('drops this week for all-time tags', () => expect(tagLine(t(9, false), null)).toBe('9 posts'));
});
