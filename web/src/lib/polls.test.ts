import { expect, it } from 'vitest';
import { closedAt, closingLabel, ownChoices, parsePoll, pollOf, pollPayload, tallyOf, toggled } from './polls';
import type { Projection } from './sync/client';

const row = (fields: Record<string, unknown>) => ({ exists: true, fields, clocks: {} });
const poll = { question: 'Dinner?', options: [{ id: 'a', text: 'Soup' }, { id: 'b', text: 'Rice' }], multiple: false };

it('builds a poll payload with stable option ids and refuses too few options', () => {
  expect(pollPayload([' Soup ', '', 'Rice'], true)).toEqual({ poll: { options: [{ id: 'a', text: 'Soup' }, { id: 'b', text: 'Rice' }], multiple: true } });
  expect(pollPayload(['Soup', ' '], false).error).toMatch(/two options/);
  expect(pollPayload(Array.from({ length: 21 }, (_, i) => `o${i}`), false).error).toMatch(/at most 20/);
});

it('toggles single choice as a replacement and multiple choice as a set', () => {
  expect(toggled(poll, ['a'], 'b')).toEqual(['b']);
  expect(toggled(poll, ['a'], 'a')).toEqual([]);
  expect(toggled({ ...poll, multiple: true }, ['a'], 'b')).toEqual(['a', 'b']);
});

it('reads the poll, the published totals and only the acting member own vote', () => {
  const p = { rows: {
    message: { m1: row({ text: 'Dinner?', poll }) },
    poll_tally: { m1: row({ channel_id: 'c', counts: { a: 2, b: 1 }, voters: 3 }) },
    poll_vote: { 'm1|kai': row({ member_id: 'kai', choices: ['a'] }) },
  }, sets: {}, fronts: {}, opaque: 0 } as unknown as Projection;
  expect(pollOf(p, 'm1')?.options.map((o) => o.text)).toEqual(['Soup', 'Rice']);
  expect(pollOf(p, 'missing')).toBeNull();
  expect(tallyOf(p, 'm1')).toEqual({ counts: { a: 2, b: 1 }, voters: 3 });
  expect(tallyOf(p, 'other')).toBeNull();
  expect(ownChoices(p, 'm1', 'kai')).toEqual(['a']);
  expect(ownChoices(p, 'm1', 'june')).toEqual([]);
  expect(ownChoices(p, 'm1', null)).toEqual([]);
});

it('reads closing times and the earliest close, and builds a closing time into the payload (D-136)', () => {
  expect(parsePoll({ ...poll, closes_at: 5000 })?.closes_at).toBe(5000);
  expect(parsePoll({ ...poll, closes_at: 'soon' })?.closes_at).toBeUndefined();
  const p = { rows: { poll_close: { early: row({ closed_at: 3000 }) } } } as unknown as Projection;
  expect(closedAt(p, 'early', { ...poll, closes_at: 5000 })).toBe(3000);
  expect(closedAt(p, 'other', { ...poll, closes_at: 5000 })).toBe(5000);
  expect(closedAt(p, 'other', poll, 4000)).toBe(4000);
  expect(closedAt(p, 'other', poll)).toBeNull();
  expect(closingLabel(null, 0)).toBe('');
  expect(closingLabel(5000, 5000)).toBe('Closed');
  expect(closingLabel(65_000, 30_000)).toBe('Closes in 1 min');
  expect(closingLabel(3 * 3_600_000 + 60_000, 0)).toBe('Closes in 3 h');
  expect(closingLabel(30 * 3_600_000, 0)).toBe('Closes in 1 day');
  expect(pollPayload(['Soup', 'Rice'], false, 2000, 1000).poll?.closes_at).toBe(2000);
  expect(pollPayload(['Soup', 'Rice'], false, 1000, 1000).error).toMatch(/closing time/);
  expect(pollPayload(['Soup', 'Rice'], false).poll).not.toHaveProperty('closes_at');
});
