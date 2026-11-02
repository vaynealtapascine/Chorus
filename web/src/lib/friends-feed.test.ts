import { describe, expect, it } from 'vitest';
import { friendsFeed, withHeart, type FriendPost } from './friends-feed';

const post = (id: string, at: number, extra: Partial<FriendPost> = {}): FriendPost => ({ id, text: id, occurred_at: at, ...extra });

describe('friends feed', () => {
  it('merges accounts newest first, once each, without textless reposts', () => {
    const feed = friendsFeed([
      { account: 'Junipers', posts: [post('a', 3), post('r', 9, { repost_of: 'x', text: '' }), post('b', 1)] },
      { account: 'Sam', posts: [post('c', 2), post('a', 3)] },
    ]);
    expect(feed.map((i) => `${i.account}:${i.post.id}`)).toEqual(['Junipers:a', 'Sam:c', 'Junipers:b']);
  });

  it('toggles only this member’s heart', () => {
    const p = post('a', 1, { reactions: [{ emoji: '💜', member_id: 'rin', member_name: 'Rin' }, { emoji: '👍', member_id: 'kai', member_name: 'Kai' }] });
    const on = withHeart(p, 'kai', 'Kai', true);
    expect(on.reactions?.filter((r) => r.emoji === '💜').map((r) => r.member_id)).toEqual(['rin', 'kai']);
    expect(withHeart(on, 'kai', 'Kai', false).reactions).toEqual(p.reactions);
  });
});
