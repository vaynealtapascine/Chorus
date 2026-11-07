import type { ThreadPost } from './post-threads';

/** A post another account shares with this one, as `GET /posts?account=` returns it. */
export interface FriendPost extends ThreadPost {
  kind?: 'note' | 'entry' | null;
  repost_of?: string | null;
  mood?: string | null;
}

/** One account's shared posts, with the name to show when a post carries no author card. [more]:
 * its last page was full, so older posts may be waiting on the server. */
export interface FriendPosts { account: string; posts: FriendPost[]; more?: boolean }

/** Posts asked for per account and page (`GET /posts?account=&before=&limit=`). */
export const FRIEND_PAGE = 20;

/**
 * How far down the merged feed can be trusted: the newest of the oldest loaded posts among the
 * accounts with more to load. Anything older could still have another account's post above it.
 */
export function feedFrontier(sources: readonly FriendPosts[]): number {
  let frontier = -Infinity;
  for (const s of sources) {
    if (!s.more || !s.posts.length) continue;
    frontier = Math.max(frontier, Math.min(...s.posts.map((p) => p.occurred_at)));
  }
  return frontier;
}

/** The accounts to ask for an older page next: those whose oldest loaded post is the frontier. */
export function olderToLoad(sources: readonly FriendPosts[]): number[] {
  const frontier = feedFrontier(sources);
  return sources.flatMap((s, i) => (s.more && s.posts.length && Math.min(...s.posts.map((p) => p.occurred_at)) === frontier ? [i] : []));
}

/** The source under which other accounts' replies to our posts page (D-127); never an account id. */
export const REPLIES = 'replies-to-us';

/** Friends' posts newest first (Android's `JournalFriends`), down to the frontier. A repost has no
 * text of its own and the original may be unreadable here, so reposts are left out; a post seen
 * twice shows once. */
export function friendsFeed(sources: readonly FriendPosts[]): { account: string; post: FriendPost }[] {
  const frontier = feedFrontier(sources);
  const seen = new Set<string>();
  const out: { account: string; post: FriendPost }[] = [];
  for (const { account, posts } of sources) {
    for (const post of posts) {
      if (!post?.id || post.repost_of || seen.has(post.id) || post.occurred_at < frontier) continue;
      seen.add(post.id);
      out.push({ account, post });
    }
  }
  return out.sort((a, b) => b.post.occurred_at - a.post.occurred_at || b.post.id.localeCompare(a.post.id));
}

/** Toggle one member's 💜 in a server copy, so the card answers before the next read. */
export function withHeart<T extends ThreadPost>(post: T, memberId: string, memberName: string, on: boolean): T {
  const rest = (post.reactions ?? []).filter((r) => !(r.emoji === '💜' && r.member_id === memberId));
  return { ...post, reactions: on ? [...rest, { emoji: '💜', member_id: memberId, member_name: memberName }] : rest };
}
