import { describe, expect, it } from 'vitest';
import { byActivity, chatPrefs, chatTime, dayLabel, folderOf, frontEvents, HIDDEN, HIDE_PRIVATE, hideSpoilers, isPrivate, movePin, orderChats, PINS, previewOf, privateVisible, togglePin } from './chat-list';
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

describe('pins, private chats and cleared chats (D-097)', () => {
  it('reads the prefs, pending first', () => {
    const p = chatPrefs({
      [`a||${PINS}`]: { fields: { value: ['c1', 'c2'] } },
      [`a||${HIDDEN}c3`]: { fields: { value: 100 } },
      [`||${HIDDEN}c3`]: { fields: { value: 200 } },
      [`||${HIDE_PRIVATE}`]: { fields: { value: false } },
      [`other||${HIDDEN}c4`]: { fields: { value: 5 } },
    }, 'a');
    expect(p.pins).toEqual(['c1', 'c2']);
    expect(p.hidePrivate).toBe(false);
    expect([...p.hidden]).toEqual([['c3', 200]]);
    expect(chatPrefs({}, 'a').hidePrivate).toBe(true);
  });
  it("shows members' private chats only while one of them is here", () => {
    const home = { kind: 'internal' };
    const dm = { kind: 'member_dm', member_ids: ['kai', 'rin'] };
    const f = (id: string, level = 'front') => [{ subject_type: 'member', subject_id: id, level }];
    expect(privateVisible(dm, home, f('june'), null, true)).toBe(false);
    expect(privateVisible(dm, home, f('rin'), null, true)).toBe(true);
    expect(privateVisible(dm, home, f('kai', 'cocon'), null, true)).toBe(true);
    expect(privateVisible(dm, home, f('kai', 'present'), null, true)).toBe(false);
    expect(privateVisible(dm, home, f('june'), 'kai', true)).toBe(true);
    expect(privateVisible(dm, home, [], null, false)).toBe(true);
    expect(privateVisible({ kind: 'text' }, home, [], null, true)).toBe(true);
    expect(isPrivate({ kind: 'text', member_ids: ['kai'] }, { kind: 'shared' })).toBe(false);
  });
  it('puts pins first in their order, then by activity', () => {
    const rows = [{ id: 'a', at: 10, title: 'a' }, { id: 'b', at: 50, title: 'b' }, { id: 'c', at: 30, title: 'c' }, { id: 'd', at: 40, title: 'd' }];
    expect(orderChats(rows, ['c', 'a']).map((r) => r.id)).toEqual(['c', 'a', 'b', 'd']);
    expect(togglePin(['a'], 'c')).toEqual(['a', 'c']);
    expect(togglePin(['a', 'c'], 'a')).toEqual(['c']);
    expect(movePin(['a', 'b', 'c'], 'c', 'a')).toEqual(['c', 'a', 'b']);
    expect(movePin(['a', 'b', 'c'], 'a', 'c')).toEqual(['b', 'c', 'a']);
  });
});
