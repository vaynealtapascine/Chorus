import { describe, expect, it } from 'vitest';
import { BUILT_IN, contains, folderPrefs, fromSuggestion, holds, label, move, ordered, parse, setChat, setKind, toJson, visible, without, type Custom, type Settings } from './chat-folders';

const friends: Custom = { id: 'f1', name: 'Friends', chats: ['c1'], kinds: ['direct'], except: ['c9'], unreadOnly: false, hideMuted: false };
const settings = (s: Partial<Settings> = {}): Settings => ({ order: [...BUILT_IN], hidden: [], custom: [friends], showHere: true, compact: false, ...s });

describe('chat folders (D-113)', () => {
  it('hold chosen chats and whole kinds, less the chats left out', () => {
    const s = settings();
    expect(contains(s, 'f1', 'c1', 'internal', 0, false)).toBe(true);
    expect(contains(s, 'f1', 'c2', 'direct', 0, false)).toBe(true);
    expect(contains(s, 'f1', 'c9', 'direct', 0, false)).toBe(false);
    expect(contains(s, 'f1', 'c3', 'shared', 0, false)).toBe(false);
    expect(contains(s, 'all', 'c3', 'shared', 0, true)).toBe(true);
    expect(contains(s, 'shared', 'c3', 'shared', 0, false)).toBe(true);
    expect(contains(s, 'gone', 'c1', 'internal', 0, false)).toBe(false);
    const strict = settings({ custom: [{ ...friends, unreadOnly: true, hideMuted: true }] });
    expect(contains(strict, 'f1', 'c1', 'internal', 0, false)).toBe(false);
    expect(contains(strict, 'f1', 'c1', 'internal', 2, false)).toBe(true);
    expect(contains(strict, 'f1', 'c1', 'internal', 2, true)).toBe(false);
  });

  it('put a chat in or out by its kind', () => {
    const out = setChat(friends, 'c2', 'direct', false);
    expect(out.except).toContain('c2');
    expect(holds(out, 'c2', 'direct')).toBe(false);
    const back = setChat(out, 'c2', 'direct', true);
    expect(back.except).not.toContain('c2');
    expect(back.chats).not.toContain('c2');
    expect(holds(back, 'c2', 'direct')).toBe(true);
    const chosen = setChat(friends, 'c5', 'shared', true);
    expect(chosen.chats).toEqual(['c1', 'c5']);
    expect(setChat(chosen, 'c5', 'shared', false).chats).toEqual(['c1']);
    const kinds: Record<string, 'internal' | 'shared' | 'direct'> = { c1: 'internal', c5: 'shared', c9: 'direct' };
    const shared = setKind(chosen, 'shared', true, (c) => kinds[c]);
    expect(shared.chats).toEqual(['c1']);
    expect(shared.kinds).toContain('shared');
    expect(setKind(shared, 'direct', false, (c) => kinds[c]).except).toEqual([]);
  });

  it('keep their order and hidden folders, and read what Android writes', () => {
    const s = settings({ order: ['direct', 'all'], hidden: ['internal', 'gone'], showHere: false, compact: true });
    expect(ordered(s)).toEqual(['direct', 'all', 'internal', 'shared', 'f1']);
    expect(visible(s)).toEqual(['direct', 'all', 'shared', 'f1']);
    const moved = move(s, 'f1', 0);
    expect(ordered(moved)).toEqual(['f1', 'direct', 'all', 'internal', 'shared']);
    expect(move(s, 'f1', 99)).toBe(s);
    const json = toJson(moved);
    expect(json.hidden).toEqual(['internal']);
    const back = parse(JSON.parse(JSON.stringify(json)));
    expect(ordered(back)).toEqual(ordered(moved));
    expect(back.custom).toEqual([friends]);
    expect([back.showHere, back.compact]).toEqual([false, true]);
    expect(ordered(without(moved, 'f1'))).toEqual(['direct', 'all', 'internal', 'shared']);
    const prefs = { 'a||chat_folders': { fields: { value: json } } };
    expect(ordered(folderPrefs(prefs, 'a'))).toEqual(ordered(moved));
    expect(ordered(folderPrefs({ ...prefs, '||chat_folders': { fields: { value: { order: ['shared'] } } } }, 'a'))[0]).toBe('shared');
  });

  it('fall back safely from odd prefs', () => {
    expect(parse(null).order).toEqual([...BUILT_IN]);
    expect(parse('folders').custom).toEqual([]);
    const odd = parse({ order: [], custom: [{ id: 'all', name: 'Mine' }, { id: '', name: 'x' },
      { id: 'f', name: 'F', kinds: ['direct', 'bogus'], chats: ['a', 'a', 3] }, { id: 'f', name: 'Again' }] });
    expect(ordered(odd)).toEqual([...BUILT_IN, 'f']);
    expect(odd.custom).toHaveLength(1);
    expect(odd.custom[0].kinds).toEqual(['direct']);
    expect(odd.custom[0].chats).toEqual(['a']);
    expect(label({ ...odd, custom: [{ ...odd.custom[0], name: ' ' }] }, 'f')).toBe('Folder');
  });

  it('start useful folders from suggestions', () => {
    expect(fromSuggestion('n', 'unread', 'Unread', []).unreadOnly).toBe(true);
    expect(fromSuggestion('n', 'unmuted', 'Not muted', []).hideMuted).toBe(true);
    expect(fromSuggestion('n', 'handover', 'Handover', [{ id: 'a', name: 'Handover' }, { id: 'b', name: 'general' }, { id: 'c', name: 'Front notes' }]).chats)
      .toEqual(['a', 'c']);
  });
});
