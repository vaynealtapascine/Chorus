import { readFileSync } from 'node:fs';
import { render } from 'svelte/server';
import { afterEach, beforeAll, describe, expect, it } from 'vitest';
import { loadCore } from './core';
import { channels, spaces } from './data';
import { folderOf } from './chat-list';
import { DEFAULTS, fromSuggestion, visibleFor } from './chat-folders';
import { destinations, withSaveToNotes, type Place } from './chat-forward';
import { defaultLevel, isPersonHome, ownInternalSpaces, specialChannels, specialSubtitle } from './special';
import { sync, type Projection } from './sync/client';
import { build, ID, ME, OTHER, personSpecs, systemSpecs } from './testing/d132-ops';
import ChatList from './ui/ChatList.svelte';

beforeAll(async () => loadCore(readFileSync(new URL('./core/pkg/chorus_wasm_bg.wasm', import.meta.url))));
afterEach(() => { Object.assign(sync, { device: null }); });

describe('a person account (D-093, D-162): its internal space holds only Notes', () => {
  let p!: Projection;
  beforeAll(() => { p = build(personSpecs()); });

  it('is built by the real projection, whole', () => {
    expect(p.opaque).toBe(0);
    expect(spaces(p).map((s) => s.name).sort()).toEqual(['Notes', 'The Lanterns']);
  });

  it('finds Notes by its role, not its name, and has no Handover', () => {
    const found = specialChannels(p, ME);
    expect(found.notes?.id).toBe(ID.notes);
    expect(found.handover).toBeUndefined();
    expect(found.trashed).toEqual({});
  });

  it('knows its home space and nobody else\'s', () => {
    expect(ownInternalSpaces(p, ME)).toEqual(new Set([ID.home]));
    expect(isPersonHome(p, ME, ID.home)).toBe(true);
    expect(isPersonHome(p, ME, ID.lanterns)).toBe(false);
    expect(isPersonHome(p, OTHER, ID.home)).toBe(false);
    expect(isPersonHome(p, ME, undefined)).toBe(false);
  });

  it('has no Internal folder in the rail or the tabs', () => {
    expect(visibleFor(DEFAULTS, true)).toEqual(['all', 'shared', 'direct']);
    expect(visibleFor(DEFAULTS, false)).toEqual(['all', 'internal', 'shared', 'direct']);
  });

  it('lists Notes as one chat row and the space as nothing: the chat list', () => {
    Object.assign(sync, { device: { account_id: ME } });
    const html = render(ChatList, { props: { projection: p, dark: false, folder: 'all', folders: DEFAULTS, onfolders: () => {}, directory: new Map(),
      person: true, onswitch: () => {}, onnewchat: () => {} } }).body;
    const titles = [...html.matchAll(/<span class="title[^"]*"[^>]*>([^<]*)</g)].map((m) => m[1]);
    expect(titles.sort()).toEqual(['Notes to self', 'The Lanterns']);
    expect(html).not.toMatch(/Internal/);
    expect(html).toContain(`href="#/chat/${ID.notes}"`);
    // the Notes row wears the bookmark and, empty, says nothing about members
    expect(html).toMatch(/bookmark\.svg/);
    expect(html).toContain('No messages yet');
    expect(html).not.toContain('saved for every member');
  });

  it('keeps the chats pickers draw from free of the space', () => {
    // Save to notes leads the forward sheet's destinations; the space is never one of them
    const targets: (Place & { at: number })[] = channels(p).map((c) => ({ id: c.id, title: c.name, names: [c.name], at: 0, disc: { glyph: 'x', color: '#fff' } }));
    const shown = destinations(withSaveToNotes(targets, specialChannels(p, ME).notes?.id), '', (t) => t.names, (t) => t.at, () => false, (t) => !!t.first);
    expect(shown.map((t) => t.title)).toEqual(['Save to notes', 'general']);
    expect(shown[0].disc.icon).toBe('bookmark');
    expect(shown.some((t) => /internal|home/i.test(t.title))).toBe(false);
  });

  it('puts its chat in the Handover and notes folder by role', () => {
    const notes = specialChannels(p, ME).notes!;
    expect(folderOf(notes, spaces(p).find((s) => s.id === notes.space_id))).toBe('internal');
    const f = fromSuggestion('f', 'handover', 'Handover and notes', channels(p).map((c) => ({ id: c.id, special: c.special })));
    expect(f.chats).toEqual([ID.notes]);
  });
});

describe('a system account', () => {
  let p!: Projection;
  beforeAll(() => { p = build(systemSpecs()); });

  it('finds Handover and Notes, and sets the subtitles and levels', () => {
    const found = specialChannels(p, ME);
    expect([found.handover?.id, found.notes?.id]).toEqual([ID.handover, ID.notes]);
    expect(specialSubtitle('handover', false)).toBe('notes between fronts');
    expect(specialSubtitle('notes', false)).toBe('saved for every member');
    expect(specialSubtitle('notes', true)).toBeNull();
    expect(defaultLevel('handover', 'mentions')).toBe('all');
    expect(defaultLevel('notes', 'mentions')).toBe('none');
    expect(defaultLevel(undefined, 'mentions')).toBe('mentions');
    expect(isPersonHome(p, ME, ID.home)).toBe(false);
  });

  it('keeps an ordinary channel called Handover ordinary, and leaves it out of the folder', () => {
    const q = build([...systemSpecs(), { kind: 'channel.create', scope: `space:${ID.home}`, entity: ID.mine, payload: { space_id: ID.home, kind: 'text', name: 'Handover' } }]);
    expect(specialChannels(q, ME).handover?.id).toBe(ID.handover);
    const f = fromSuggestion('f', 'handover', 'x', channels(q).map((c) => ({ id: c.id, special: c.special })));
    expect(f.chats.sort()).toEqual([ID.handover, ID.notes]);
  });

  it('offers Restore, not a second one, once its Notes is in Trash', () => {
    const q = build([...systemSpecs(), { kind: 'channel.delete', scope: `space:${ID.home}`, entity: ID.notes, payload: {} }]);
    const found = specialChannels(q, ME);
    expect(found.notes).toBeUndefined();
    expect(found.trashed.notes?.id).toBe(ID.notes);
    expect(found.handover?.id).toBe(ID.handover);
  });

  it('does not treat another system\'s internal space, seen as a guest, as its own', () => {
    const q = build([...systemSpecs(), { kind: 'space.create', scope: `space:${ID.theirs}`, entity: ID.theirs, account: OTHER, payload: { kind: 'internal', name: 'Theirs' } },
      { kind: 'channel.create', scope: `space:${ID.theirs}`, entity: ID.theirNotes, account: OTHER, payload: { space_id: ID.theirs, kind: 'text', name: 'Notes to ourselves', special: 'notes' } }]);
    expect(specialChannels(q, ME).notes?.id).toBe(ID.notes);
  });
});
