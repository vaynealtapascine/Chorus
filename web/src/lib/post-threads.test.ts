import { describe, expect, it } from 'vitest';
import { localAncestors, remoteNames, replyCounts, threadAncestors, threadReplies } from './post-threads';
import type { PostRow } from './posts';

const post = (id: string, reply_to?: string, at = 0, deleted = false): PostRow => ({
  id, kind: 'note', authors: ['kai'], text: id, entities: [], occurred_at: at, tags: [],
  visibility: { mode: 'private' }, reply_to, deleted,
});

describe('journal threads', () => {
  it('walks own parents oldest first and stops at a missing, deleted or looping one', () => {
    const rows = [post('a'), post('b', 'a'), post('c', 'b'), post('orphan', 'gone'), post('x', 'y'), post('y', 'x'),
      post('dead', undefined, 0, true), post('under', 'dead')];
    expect(localAncestors(rows, 'c').map((p) => p.id)).toEqual(['a', 'b']);
    expect(localAncestors(rows, 'orphan')).toEqual([]);
    expect(localAncestors(rows, 'x').map((p) => p.id)).toEqual(['y']);
    expect(localAncestors(rows, 'under')).toEqual([]);
  });

  it('counts live replies only', () => {
    const counts = replyCounts([post('a'), post('b', 'a'), post('c', 'a'), post('d', 'a', 0, true)]);
    expect(counts.get('a')).toBe(2);
  });

  it('lists server replies with own pending ones once, oldest first, keeping own rows and the larger count', () => {
    const rows = [post('root'), post('mine', 'root', 30), post('pending', 'root', 40), post('under-mine', 'mine', 50)];
    const items = threadReplies(rows, 'root', [
      { id: 'theirs', text: 'hi', occurred_at: 20, reply_count: 3 },
      { id: 'mine', text: 'synced', occurred_at: 30, reply_count: 0 },
    ]);
    expect(items.map((i) => i.id)).toEqual(['theirs', 'mine', 'pending']);
    expect(items[0].remote?.text).toBe('hi');
    expect(items[0].replyCount).toBe(3);
    expect(items[1].local?.text).toBe('mine');
    expect(items[1].replyCount).toBe(1);
  });

  it("uses the server's chain when it has one, else own parents", () => {
    const rows = [post('a'), post('b', 'a')];
    expect(threadAncestors(rows, 'b', undefined).map((i) => i.id)).toEqual(['a']);
    expect(threadAncestors(rows, 'b', []).map((i) => i.id)).toEqual(['a']);
    const remote = threadAncestors(rows, 'b', [{ id: 'z', text: 'theirs', occurred_at: 1 }, { id: 'a', text: 'a', occurred_at: 2 }]);
    expect(remote.map((i) => [i.id, Boolean(i.local)])).toEqual([['z', false], ['a', true]]);
  });

  it('names remote authors from their cards', () => {
    expect(remoteNames({ id: 'p', text: '', occurred_at: 0, author_cards: [{ name: 'june', display_name: 'June' }, { name: 'Rin' }] })).toBe('June & Rin');
    expect(remoteNames({ id: 'p', text: '', occurred_at: 0 })).toBe('Someone');
  });
});
