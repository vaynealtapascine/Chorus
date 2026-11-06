import { describe, expect, it } from 'vitest';
import { feedFrontier, friendsFeed, olderToLoad, withHeart, type FriendPost } from './friends-feed';

const post = (id: string, at: number, extra: Partial<FriendPost> = {}): FriendPost => ({ id, text: id, occurred_at: at, ...extra });

describe('friends feed', () => {
  it('merges accounts newest first, once each, without textless reposts', () => {
    const feed = friendsFeed([
      { account: 'Junipers', posts: [post('a', 3), post('r', 9, { repost_of: 'x', text: '' }), post('b', 1)] },
      { account: 'Sam', posts: [post('c', 2), post('a', 3)] },
    ]);
    expect(feed.map((i) => `${i.account}:${i.post.id}`)).toEqual(['Junipers:a', 'Sam:c', 'Junipers:b']);
  });

  it('pages: shows down to where every account with more is loaded, and asks those for older posts', () => {
    const sources = [
      // a full page reaching back to 50: older posts of theirs may still come
      { account: 'Busy', posts: [post('b1', 90), post('b2', 50)], more: true },
      // everything they share is here
      { account: 'Quiet', posts: [post('q1', 70), post('q2', 10)], more: false },
    ];
    expect(feedFrontier(sources)).toBe(50);
    // q2 (10) waits: Busy may have posts between 50 and 10 that belong above it
    expect(friendsFeed(sources).map((i) => i.post.id)).toEqual(['b1', 'q1', 'b2']);
    expect(olderToLoad(sources)).toEqual([0]);
    // Busy's next page comes back short: it has nothing older, so the whole feed shows
    const done = [{ ...sources[0], posts: [...sources[0].posts, post('b3', 30)], more: false }, sources[1]];
    expect(friendsFeed(done).map((i) => i.post.id)).toEqual(['b1', 'q1', 'b2', 'b3', 'q2']);
    expect(olderToLoad(done)).toEqual([]);
  });

  it('toggles only this member’s heart', () => {
    const p = post('a', 1, { reactions: [{ emoji: '💜', member_id: 'rin', member_name: 'Rin' }, { emoji: '👍', member_id: 'kai', member_name: 'Kai' }] });
    const on = withHeart(p, 'kai', 'Kai', true);
    expect(on.reactions?.filter((r) => r.emoji === '💜').map((r) => r.member_id)).toEqual(['rin', 'kai']);
    expect(withHeart(on, 'kai', 'Kai', false).reactions).toEqual(p.reactions);
  });
});
