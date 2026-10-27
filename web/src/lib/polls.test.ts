import { expect, it } from 'vitest';
import { ownChoices, pollOf, pollPayload, tallyOf, toggled } from './polls';
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
