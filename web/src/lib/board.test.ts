import { expect, it } from 'vitest';
import { boardPosts, isBoardPost } from './board';
import type { PostRow } from './posts';

const post = (id: string, over: Partial<PostRow> = {}): PostRow => ({ id, kind: 'note', authors: ['a'], text: id, entities: [], occurred_at: 1, tags: [], visibility: { mode: 'private' }, deleted: false, ...over });

it('lists our private top-level posts, newest first, polls on their own tab (D-135)', () => {
  const all = [post('old', { occurred_at: 1 }), post('new', { occurred_at: 3 }), post('poll', { occurred_at: 2 }),
    post('reply', { reply_to: 'old' }), post('repost', { repost_of: 'old' }), post('friends', { visibility: { mode: 'friends' } }), post('gone', { deleted: true })];
  expect(boardPosts(all, () => false, 'posts').map((p) => p.id)).toEqual(['new', 'poll', 'old']);
  expect(boardPosts(all, (id) => id === 'poll', 'polls').map((p) => p.id)).toEqual(['poll']);
  expect(isBoardPost(post('x', { visibility: { mode: 'buckets', bucket_ids: ['b'] } }))).toBe(false);
});
