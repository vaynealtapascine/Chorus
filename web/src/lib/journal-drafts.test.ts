import { beforeEach, describe, expect, it, vi } from 'vitest';
import { emptyDraft } from './journal-draft';
import { discardShelved, draftHeadline, shelf, shelve, unshelve } from './journal-drafts';

class FakeStorage {
  [key: string]: unknown;
  getItem(k: string) { return Object.hasOwn(this, k) ? String(this[k]) : null; }
  setItem(k: string, v: string) { this[k] = String(v); }
  removeItem(k: string) { delete this[k]; }
}

const d = (body: string, more = {}) => ({ ...emptyDraft(), body, ...more });
let local: FakeStorage;
beforeEach(() => {
  local = new FakeStorage();
  vi.stubGlobal('localStorage', local);
});

describe('the drafts shelf', () => {
  it('lists newest first and replaces a row with the same id', () => {
    shelve('a', d('one'), 'x', 10);
    shelve('a', d('two'), 'y', 20);
    expect(shelf('a').map((r) => r.id)).toEqual(['y', 'x']);
    shelve('a', d('one again'), 'x', 30);
    expect(shelf('a').map((r) => [r.id, r.draft.body])).toEqual([['x', 'one again'], ['y', 'two']]);
    expect(shelf('b')).toEqual([]);
  });

  it('is keyed by account without aliasing', () => {
    shelve('ab', d('one'), 'x', 1);
    expect(Object.keys(local)).toEqual(['chorus.journal-shelf.v1:2:ab']);
    expect(shelf('a')).toEqual([]);
  });

  it('does not keep an empty draft, but keeps any content', () => {
    shelve('a', emptyDraft(), 'e', 1);
    shelve('a', d('  ', { visibility: 'server' }), 'e', 1);
    expect(shelf('a')).toEqual([]);
    for (const more of [{ title: 't' }, { cw: 'c' }, { mood: 'm' }, { tags: 'x' }, { quote: 'q' }, { pollOn: true }, { lostFiles: 1 }]) {
      shelve('a', d('', more), JSON.stringify(more), 1);
    }
    expect(shelf('a')).toHaveLength(7);
  });

  it('unshelves (removing it) and discards', () => {
    shelve('a', d('one', { visibility: 'friends' }), 'x', 1);
    shelve('a', d('two'), 'y', 2);
    expect(unshelve('a', 'x')).toMatchObject({ body: 'one', visibility: 'friends' });
    expect(unshelve('a', 'x')).toBeNull();
    discardShelved('a', 'nope');
    discardShelved('a', 'y');
    expect(shelf('a')).toEqual([]);
  });

  it('keeps at most 50, dropping the oldest', () => {
    for (let i = 0; i < 55; i++) shelve('a', d(`n${i}`), `id${i}`, i);
    const rows = shelf('a');
    expect(rows).toHaveLength(50);
    expect(rows[0].id).toBe('id54');
    expect(rows[49].id).toBe('id5');
  });

  it('survives damage: bad JSON, bad rows, malformed fields', () => {
    local.setItem('chorus.journal-shelf.v1:1:a', 'not json');
    expect(shelf('a')).toEqual([]);
    local.setItem('chorus.journal-shelf.v1:1:a', JSON.stringify({ v: 2, drafts: [] }));
    expect(shelf('a')).toEqual([]);
    local.setItem('chorus.journal-shelf.v1:1:a', JSON.stringify({ v: 1, drafts: [
      null, { id: 1, savedAt: 1, draft: {} }, { id: 'bad', savedAt: 'x', draft: {} }, { id: 'nodraft', savedAt: 1 },
      { id: 'ok', savedAt: 5, draft: { body: 'x', visibility: 'everyone', authors: 'kai' } },
    ] }));
    expect(shelf('a')).toHaveLength(1);
    expect(shelf('a')[0]).toMatchObject({ id: 'ok', draft: { body: 'x', visibility: 'private', authors: null } });
  });

  it('never throws when storage does', () => {
    const boom = { getItem() { throw new Error('x'); }, setItem() { throw new Error('x'); }, removeItem() { throw new Error('x'); } };
    vi.stubGlobal('localStorage', boom);
    expect(shelf('a')).toEqual([]);
    expect(() => shelve('a', d('x'), 'i', 1)).not.toThrow();
    expect(unshelve('a', 'i')).toBeNull();
    expect(() => discardShelved('a', 'i')).not.toThrow();
    vi.stubGlobal('localStorage', undefined);
    expect(shelf('a')).toEqual([]);
    expect(() => shelve('a', d('x'), 'i', 1)).not.toThrow();
  });
});

describe('a shelved draft in the list', () => {
  it('heads with the title, else the first line of the body, else the files, else Empty', () => {
    expect(draftHeadline(d('body', { title: 'Title' })).head).toBe('Title');
    expect(draftHeadline(d('\n\n## > • Hello there\nmore')).head).toBe('Hello there');
    expect(draftHeadline(d('', { lostFiles: 3 })).head).toBe('3 files');
    expect(draftHeadline(d('###')).head).toBe('Empty');
    expect(draftHeadline(emptyDraft()).head).toBe('Empty');
  });

  it('describes the kind, words, poll and quote', () => {
    expect(draftHeadline(d('')).meta).toBe('Post');
    expect(draftHeadline(d('one', { kind: 'entry' })).meta).toBe('Journal entry · 1 word');
    expect(draftHeadline(d('a b c', { pollOn: true, quote: 'q' })).meta).toBe('Post · 3 words · Poll · Quote');
  });
});
