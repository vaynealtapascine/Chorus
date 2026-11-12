import { expect, it } from 'vitest';
import type { TrashItem } from './data';
import { trashDetail, trashFilter, trashSections } from './trash';

const DAY = 86_400_000;
const now = 100 * DAY;
const item = (over: Partial<TrashItem>): TrashItem => ({ id: 'a', kind: 'message', label: 'Message', detail: '', title: 'Message in #vent', deletedAt: now - 2 * DAY, scope: 'space:s', ...over });

it('says who deleted it and when', () => {
  expect(trashDetail(item({ deletedBy: 'Nairon' }), now)).toBe('Deleted by Nairon · 2 days ago');
  expect(trashDetail(item({}), now)).toBe('Deleted 2 days ago');
  expect(trashDetail(item({ kind: 'member', deletedAt: now - 12 * DAY }), now)).toBe('Deleted 12 days ago · history kept');
});

it('puts this week before earlier and drops an empty one', () => {
  const rows = [item({ id: 'a' }), item({ id: 'b', deletedAt: now - 9 * DAY })];
  expect(trashSections(rows, now).map((s) => [s.label, s.items.map((i) => i.id)])).toEqual([['This week', ['a']], ['Earlier', ['b']]]);
  expect(trashSections([rows[0]], now).map((s) => s.label)).toEqual(['This week']);
});

it('filters by tab and by words in the title or the deleter', () => {
  const rows = [item({ id: 'm' }), item({ id: 'p', kind: 'post', title: 'Post “Rough night”' }), item({ id: 'c', kind: 'channel', title: 'Channel #old', deletedBy: 'Kai' }), item({ id: 'g', kind: 'group', title: 'Group Inside' })];
  expect(trashFilter(rows, 'messages', '').map((i) => i.id)).toEqual(['m', 'c']);
  expect(trashFilter(rows, 'members', '').map((i) => i.id)).toEqual(['g']);
  expect(trashFilter(rows, 'all', 'rough').map((i) => i.id)).toEqual(['p']);
  expect(trashFilter(rows, 'all', 'kai').map((i) => i.id)).toEqual(['c']);
});
