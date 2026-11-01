import { describe, expect, it } from 'vitest';
import { canEditSpace, canInvite, channelChanges, channelCreate, channelSlug, draftOf, draftProblem, existingMemberDm, existingPrivate, privateSides, roleIn } from './chat-manage';
import type { ChannelRow } from './data';

const ch = (id: string, kind: ChannelRow['kind'], member_ids?: string[], space_id = 's'): ChannelRow =>
  ({ id, space_id, kind, name: id, member_ids, archived: false });

describe('chat management', () => {
  it('allows one-member private DMs while internal member chats still need two', () => {
    const draft = { ...draftOf(undefined), name: 'Private', member_ids: ['kai'] };
    expect(draftProblem(draft, 'member_dm', 'dm')).toBeNull();
    expect(draftProblem(draft, 'member_dm', 'internal')).toBe('A members’ chat needs at least two members.');
    expect(draftProblem({ ...draft, member_ids: [] }, 'member_dm', 'dm')).toBeTruthy();
  });
  it('says whose a private chat is on each side', () => {
    const own = (id: string) => id.startsWith('ours:');
    expect(privateSides({ member_ids: ['ours:kai', 'robin'] }, { kind: 'dm' }, own)).toEqual({ mine: ['ours:kai'], theirs: ['robin'] });
    expect(privateSides({ member_ids: ['ours:kai'] }, { kind: 'internal' }, own)).toBeNull();
    expect(privateSides({}, { kind: 'dm' }, own)).toBeNull();
  });
  it('finds an existing private chat or members’ chat instead of making another', () => {
    const own = (id: string) => ['kai', 'june'].includes(id);
    const list = [ch('dm', 'text'), ch('p1', 'member_dm', ['kai', 'robin']), ch('p2', 'member_dm', ['kai', 'june'])];
    expect(existingPrivate(list, 's', 'kai', own)?.id).toBe('p1');
    expect(existingPrivate(list, 's', 'june', own)).toBeUndefined();
    expect(existingMemberDm([ch('b', 'member_dm', ['june', 'kai']), ch('a', 'member_dm', ['kai', 'june'])], 's', ['kai', 'june'])?.id).toBe('a');
    expect(existingMemberDm(list, 's', ['kai'])).toBeUndefined();
  });
  it('writes only what changed, and names channels like Discord', () => {
    const before = draftOf({ ...ch('c', 'text'), name: 'plans', topic: 'trips', member_ids: ['kai'] });
    expect(channelChanges(before, { ...before }, 'text')).toEqual({});
    expect(channelChanges(before, { ...before, name: 'Big Plans ', member_ids: [] }, 'text')).toEqual({ name: 'big-plans', member_ids: null });
    expect(channelChanges(before, { ...before, topic: ' ', icon: '🌙', color: '#86BFA5' }, 'text')).toEqual({ topic: null, icon: '🌙', color: '#86BFA5' });
    expect(channelChanges(before, { ...before, member_ids: ['june', 'kai', 'kai'] }, 'text')).toEqual({ member_ids: ['june', 'kai'] });
    expect(channelSlug('  Movie   Night ')).toBe('movie-night');
  });
  it('makes a new channel or members’ chat without empty fields', () => {
    const d = { ...draftOf(undefined), name: 'Movie night' };
    expect(channelCreate('home', d, 'text')).toEqual({ space_id: 'home', kind: 'text', name: 'movie-night' });
    expect(channelCreate('home', { ...d, name: 'Kai & June', member_ids: ['kai', 'june'] }, 'member_dm'))
      .toEqual({ space_id: 'home', kind: 'member_dm', name: 'Kai & June', member_ids: ['june', 'kai'] });
    expect(draftProblem({ ...d, name: '' }, 'text')).toMatch(/name/);
    expect(draftProblem({ ...d, member_ids: ['kai'] }, 'member_dm')).toMatch(/two members/);
    expect(draftProblem(d, 'text')).toBeNull();
  });
  it('knows who may change a space', () => {
    const info = { owner_account_id: 'o', accounts: [{ id: 'o' }, { id: 'a' }, { id: 'm' }] };
    const rows = { 's|a': { fields: { role: 'admin' } } };
    expect(roleIn(rows, 's', info, 'o')).toBe('owner');
    expect(roleIn(rows, 's', info, 'a')).toBe('admin');
    expect(roleIn(rows, 's', info, 'm')).toBe('member');
    expect(roleIn(rows, 's', { ...info, guest: true }, 'x')).toBeNull();
    expect(canEditSpace('shared', 'admin')).toBe(true);
    expect(canEditSpace('dm', 'owner')).toBe(false);
    expect(canInvite('shared', 'admin')).toBe(false);
    expect(canInvite('shared', 'owner')).toBe(true);
  });
});
