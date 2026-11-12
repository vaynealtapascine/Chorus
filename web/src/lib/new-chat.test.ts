import { describe, expect, it } from 'vitest';
import type { MemberRow } from './data';
import type { SpaceAccount } from './spaces';
import { friendLine, friendList, memberLine, memberList, selectedLabel, spaceName, startFrom, toggle } from './new-chat';

const member = (id: string, name: string, extra: Partial<MemberRow> = {}) => ({ id, name, display_name: null, sigils: [], ...extra }) as unknown as MemberRow;
const account = (id: string, kind: string, name: string): SpaceAccount => ({ id, kind, handle: name.toLowerCase(), display_name: name });
const noHandle = () => undefined;

describe('New message (F5.18)', () => {
  it('lists members here now first, without the speaker, and searches names and sigils', () => {
    const own = [member('a', 'Amarynthe'), member('b', 'Boris'), member('c', 'Cy', { sigils: ['~'] })];
    expect(memberList(own, 'a', ['c'], '', noHandle).map((m) => m.id)).toEqual(['c', 'b']);
    expect(memberList(own, null, [], 'bor', noHandle).map((m) => m.id)).toEqual(['b']);
    expect(memberList(own, null, [], '~', noHandle).map((m) => m.id)).toEqual(['c']);
  });
  it('says where a member is, else their pronouns', () => {
    expect(memberLine('co-conscious', 'she/her')).toBe('co-conscious');
    expect(memberLine(undefined, ' they/them ')).toBe('they/them');
    expect(memberLine(undefined, null)).toBeNull();
  });
  it('says who is fronting at a friend, else what they are', () => {
    expect(friendLine(account('1', 'system', 'Junipers'), ['Kit'])).toBe('Kit is fronting');
    expect(friendLine(account('1', 'system', 'Junipers'), [])).toBe('a system');
    expect(friendLine(account('2', 'person', 'Ren'), undefined)).toBe('one person');
    expect(friendList([account('1', 'system', 'Junipers'), account('2', 'person', 'Ren')], 'ren').map((a) => a.id)).toEqual(['2']);
  });
});

describe('New shared space (F5.10)', () => {
  const chosen = [account('1', 'system', 'Junipers'), account('2', 'system', 'Driftwood')];
  it('counts what was picked', () => {
    expect(selectedLabel([])).toBeNull();
    expect(selectedLabel(chosen)).toBe('2 systems selected');
    expect(selectedLabel([account('2', 'person', 'Ren')])).toBe('1 person selected');
    expect(selectedLabel([...chosen, account('3', 'person', 'Ren')])).toBe('3 selected');
  });
  it('suggests a name from us and who was picked', () => {
    expect(spaceName('Lanterns', chosen)).toBe('Lanterns, Junipers & Driftwood');
    expect(spaceName(null, [chosen[0]])).toBe('Junipers');
    expect(spaceName('', [])).toBe('');
  });
  it('starts a shared space from the Shared folder only', () => {
    const custom = [{ id: 'f', name: 'F', chats: [], kinds: ['shared' as const], except: [], unreadOnly: false, hideMuted: false }];
    expect(startFrom('shared', { custom: [] })).toBe('space');
    expect(startFrom('f', { custom })).toBe('space');
    expect(startFrom('f', { custom: [{ ...custom[0], chats: ['c'] }] })).toBe('message');
    expect(startFrom('direct', { custom })).toBe('message');
  });
  it('toggles a pick', () => {
    expect(toggle(['a'], 'b')).toEqual(['a', 'b']);
    expect(toggle(['a', 'b'], 'a')).toEqual(['b']);
  });
});
