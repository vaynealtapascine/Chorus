import { describe, expect, it } from 'vitest';
import { decodeDraft, draftKey, emptyDraft, encodeDraft, occupied } from './journal-draft';

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
  });

  it('scopes keys by account without aliasing', () => {
    expect(draftKey('ab', 'c:main')).not.toBe(draftKey('ab:c', 'main'));
  });
});
