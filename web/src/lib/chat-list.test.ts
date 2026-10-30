import { describe, expect, it } from 'vitest';
import { byActivity, chatTime, dayLabel, folderOf, frontEvents, hideSpoilers, previewOf } from './chat-list';
import type { ChannelRow, MessageRow, SpaceRow } from './data';

const space = (kind: SpaceRow['kind']): SpaceRow => ({ id: kind, kind, name: kind });
const channel = (kind: ChannelRow['kind'] = 'text'): ChannelRow => ({ id: 'c', space_id: 's', kind, name: 'general', archived: false });
const message = (over: Partial<MessageRow> = {}): MessageRow => ({
  id: 'm', channel_id: 'c', authors: ['a'], text: 'hello', entities: [], segments: [], occurred_at: 0,
  sent_offline: false, edited: false, deleted: false, pinned: false, attachments: [], ...over,
});

describe('chat list', () => {
  it('sorts chats into folders like Android', () => {
    expect(folderOf(channel(), space('internal'))).toBe('internal');
    expect(folderOf(channel('member_dm'), space('internal'))).toBe('direct');
    expect(folderOf(channel(), space('dm'))).toBe('direct');
    expect(folderOf(channel(), space('shared'))).toBe('shared');
  });

  it('times read as a clock today, a weekday this week, then a date', () => {
    const now = new Date(2026, 9, 31, 12, 0).getTime();
    expect(chatTime(new Date(2026, 9, 31, 9, 5).getTime(), now, 'en-GB')).toBe('09:05');
    expect(chatTime(new Date(2026, 9, 27, 9, 5).getTime(), now, 'en-GB')).toBe('Tue');
    expect(chatTime(new Date(2026, 9, 3, 9, 5).getTime(), now, 'en-GB')).toBe('3 Oct');
    expect(dayLabel(new Date(2026, 9, 31, 1).getTime(), now, 'en-GB')).toBe('Today');
    expect(dayLabel(new Date(2026, 9, 30, 23).getTime(), now, 'en-GB')).toBe('Yesterday');
    expect(dayLabel(new Date(2025, 0, 2).getTime(), now, 'en-GB')).toBe('2 January 2025');
  });

  it('previews say who wrote last and never give away a warning or spoiler', () => {
    const nameOf = (id: string) => ({ a: 'Amarynthe', n: 'Nairon' })[id] ?? '?';
    expect(previewOf(message({ text: 'Keys are\nby the door.' }), nameOf)).toEqual({ who: 'Amarynthe', text: 'Keys are by the door.' });
    expect(previewOf(message({ cw: 'rough night' }), nameOf).text).toBe('Content warning: rough night');
    expect(previewOf(message({ text: 'a big secret', entities: [{ type: 'spoiler', offset: 2, length: 3 }] }), nameOf).text).toBe('a ••• secret');
    expect(previewOf(message({ text: '', attachments: [{ id: 'x', blob_hash: 'h', filename: 'p.png', mime: 'image/png', size: 1, alt_text: '', is_spoiler: false }] }), nameOf).text).toBe('Photo');
    expect(previewOf(undefined, nameOf)).toEqual({ who: null, text: 'No messages yet' });
    expect(previewOf(message(), nameOf, false).who).toBeNull();
    expect(hideSpoilers('ab cd', [{ type: 'spoiler', offset: 0, length: 5 }])).toBe('•• ••');
  });

  it('newest activity first, quiet chats by title', () => {
    expect(byActivity([{ at: 0, title: 'b' }, { at: 5, title: 'z' }, { at: 0, title: 'a' }]).map((r) => r.title)).toEqual(['z', 'a', 'b']);
  });

  it('front changes inside the shown stretch read as one line per switch', () => {
    const iv = (id: string, level: string, start: number) => ({ subject_type: 'member', subject_id: id, level, is_primary: false, start_at: start, end_at: null });
    const names: Record<string, string> = { f: 'Foxglove', a: 'Amarynthe', n: 'Nairon' };
    const layers: Record<string, string> = { front: 'Fronting', cocon: 'Co-conscious', l1: 'Steering' };
    const events = frontEvents([iv('f', 'front', 50), iv('a', 'front', 50), iv('n', 'cocon', 60), iv('f', 'l1', 70), iv('n', 'front', 5), iv('x', 'front', 80)],
      10, 100, (_, id) => names[id], (l) => layers[l]);
    expect(events).toEqual([
      { at: 50, text: 'Foxglove & Amarynthe started fronting' },
      { at: 60, text: 'Nairon became co-conscious' },
      { at: 70, text: 'Foxglove started steering' },
    ]);
  });
});
