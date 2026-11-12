import { expect, it } from 'vitest';
import type { SnapshotItem } from './data';
import { destinations, snapshotsFor, warning } from './chat-forward';

const item: SnapshotItem = { message_id: 'm1', channel_name: 'general', authors: ['a1'], text: 'hi', entities: [], occurred_at: 1 };
const chats = [{ id: 'a', t: 'General', at: 1 }, { id: 'b', t: 'Book club', at: 3 }, { id: 'c', t: 'Art corner', at: 2 }];

it('finds chats by name, newest first, and leaves one out', () => {
  expect(destinations(chats, '', (c) => [c.t], (c) => c.at).map((c) => c.id)).toEqual(['b', 'c', 'a']);
  expect(destinations(chats, ' ART', (c) => [c.t], (c) => c.at).map((c) => c.id)).toEqual(['c']);
  expect(destinations(chats, '', (c) => [c.t], (c) => c.at, (c) => c.id === 'b').map((c) => c.id)).toEqual(['c', 'a']);
});
it('names who will see a copy that leaves', () => {
  expect(warning('The Lanterns', ['The Junipers', 'Driftwood'], 'Book club')).toEqual({ title: 'This leaves The Lanterns', text: 'The Junipers and Driftwood will see it.' });
  expect(warning('The Lanterns', ['A', 'B', 'C'], 'x').text).toBe('A, B and C will see it.');
  expect(warning('The Lanterns', [], 'Book club').text).toBe('Book club will see it.');
});
it("never reveals a hidden author or another space's chat name", () => {
  expect(snapshotsFor([item], 's1', 's2', true)[0]).not.toHaveProperty('channel_name');
  expect(snapshotsFor([item], 's1', 's1', true)[0]).toMatchObject({ channel_name: 'general', authors: ['a1'] });
  expect(snapshotsFor([item], 's1', 's1', false)[0].authors).toEqual([]);
  expect(snapshotsFor([item], 's1', 's2', false)[0]).toMatchObject({ authors: [] });
  expect(item.authors).toEqual(['a1']);
});
