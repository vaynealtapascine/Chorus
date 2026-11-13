import { beforeEach, describe, expect, it, vi } from 'vitest';
import { decodeDraft, draftKey, emptyDraft, encodeDraft, loadDraft, occupied, saveDraft } from './journal-draft';

class FakeStorage {
  [key: string]: unknown;
  getItem(k: string) { return Object.hasOwn(this, k) ? String(this[k]) : null; }
  setItem(k: string, v: string) { this[k] = String(v); }
  removeItem(k: string) { delete this[k]; }
}

describe('kept drafts', () => {
  let local: FakeStorage, session: FakeStorage;
  const d = { ...emptyDraft(), body: 'hello' };
  beforeEach(() => {
    local = new FakeStorage();
    session = new FakeStorage();
    vi.stubGlobal('localStorage', local);
    vi.stubGlobal('sessionStorage', session);
  });

  it('keeps the main draft in localStorage and replies in sessionStorage', () => {
    saveDraft('a', 'main', d);
    saveDraft('a', 'reply:p1', { ...d, body: 'r' });
    expect(Object.keys(local)).toEqual([draftKey('a', 'main')]);
    expect(Object.keys(session)).toEqual([draftKey('a', 'reply:p1')]);
    expect(loadDraft('a', 'main')).toEqual(d);
    expect(loadDraft('a', 'reply:p1')?.body).toBe('r');
    expect(loadDraft('b', 'main')).toBeNull();
  });

  it('removes a draft when saving null', () => {
    saveDraft('a', 'main', d);
    saveDraft('a', 'main', null);
    expect(loadDraft('a', 'main')).toBeNull();
    saveDraft('a', 'reply:p1', d);
    saveDraft('a', 'reply:p1', null);
    expect(Object.keys(session)).toEqual([]);
  });

  it('moves a draft an older build left in the tab', () => {
    session.setItem(draftKey('a', 'main'), encodeDraft(d));
    expect(loadDraft('a', 'main')).toEqual(d);
    expect(Object.keys(session)).toEqual([]);
    expect(local.getItem(draftKey('a', 'main'))).toBe(encodeDraft(d));
  });

  it('prefers localStorage over a stale tab copy and ignores damage', () => {
    local.setItem(draftKey('a', 'main'), encodeDraft(d));
    session.setItem(draftKey('a', 'main'), encodeDraft({ ...d, body: 'old' }));
    expect(loadDraft('a', 'main')?.body).toBe('hello');
    local.setItem(draftKey('b', 'main'), 'not json');
    expect(loadDraft('b', 'main')).toBeNull();
  });

  it('never throws when storage does', () => {
    const boom = { getItem() { throw new Error('x'); }, setItem() { throw new Error('x'); }, removeItem() { throw new Error('x'); } };
    vi.stubGlobal('localStorage', boom);
    vi.stubGlobal('sessionStorage', boom);
    expect(loadDraft('a', 'main')).toBeNull();
    expect(loadDraft('a', 'reply:p')).toBeNull();
    expect(() => saveDraft('a', 'main', d)).not.toThrow();
    expect(() => saveDraft('a', 'reply:p', null)).not.toThrow();
    vi.stubGlobal('localStorage', undefined);
    vi.stubGlobal('sessionStorage', undefined);
    expect(loadDraft('a', 'main')).toBeNull();
    expect(() => saveDraft('a', 'main', d)).not.toThrow();
  });
});

describe('journal drafts', () => {
  it('round-trips every field, keeping null authors distinct from an empty choice', () => {
    const d = { ...emptyDraft(), kind: 'entry' as const, title: 'T', body: '**hi**', cw: 'cw', visibility: 'buckets' as const,
      bucketIds: ['b'], authors: [] as string[], quote: 'q', pollOn: true, pollOptions: ['a', 'b', 'c'], lostFiles: 2 };
    expect(decodeDraft(encodeDraft(d))).toEqual(d);
    expect(decodeDraft(encodeDraft(emptyDraft()))?.authors).toBeNull();
  });

  it('falls back to private and no explicit authors when damaged, never wider', () => {
    const d = decodeDraft(JSON.stringify({ v: 1, body: 'x', visibility: 'everyone', authors: 'kai', kind: 'poem', pollOptions: ['one'] }));
    expect(d).toMatchObject({ body: 'x', visibility: 'private', authors: null, kind: 'note', pollOptions: ['', ''] });
    expect(decodeDraft('not json')).toBeNull();
    expect(decodeDraft(JSON.stringify({ v: 2, body: 'x' }))).toBeNull();
  });

  it('keeps a draft only when it holds something beyond the defaults', () => {
    expect(occupied(emptyDraft())).toBe(false);
    expect(occupied(emptyDraft('server'), 'server')).toBe(false);
    expect(occupied({ ...emptyDraft(), body: '  ' })).toBe(false);
    expect(occupied({ ...emptyDraft(), body: 'x' })).toBe(true);
    expect(occupied({ ...emptyDraft(), authors: ['kai'] })).toBe(true);
    expect(occupied({ ...emptyDraft(), lostFiles: 1 })).toBe(true);
    // a draft at the account's default audience holds nothing yet; any other choice is kept
    expect(occupied(emptyDraft('buckets', ['b1']), 'buckets', ['b1'])).toBe(false);
    expect(occupied(emptyDraft('buckets', ['b2']), 'buckets', ['b1'])).toBe(true);
    expect(occupied(emptyDraft('buckets', ['b1']))).toBe(true);
  });

  it('scopes keys by account without aliasing', () => {
    expect(draftKey('ab', 'c:main')).not.toBe(draftKey('ab:c', 'main'));
  });
});
