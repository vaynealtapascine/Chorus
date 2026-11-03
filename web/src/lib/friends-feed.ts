import type { ThreadPost } from './post-threads';

/** A post another account shares with this one, as `GET /posts?account=` returns it. */
export interface FriendPost extends ThreadPost {
  kind?: 'note' | 'entry' | null;
  repost_of?: string | null;
  mood?: string | null;
  reactions?: { emoji: string; member_id: string; member_name: string }[];
}

/** One account's shared posts, with the name to show when a post carries no author card. */
export interface FriendPosts { account: string; posts: FriendPost[] }

/** Friends' posts newest first (Android's `JournalFriends`). A repost has no text of its own and
 * the original may be unreadable here, so reposts are left out; a post seen twice shows once. */
export function friendsFeed(sources: readonly FriendPosts[]): { account: string; post: FriendPost }[] {
  const seen = new Set<string>();
  const out: { account: string; post: FriendPost }[] = [];
  for (const { account, posts } of sources) {
    for (const post of posts) {
      if (!post?.id || post.repost_of || seen.has(post.id)) continue;
      seen.add(post.id);
      out.push({ account, post });
    }
  }
  return out.sort((a, b) => b.post.occurred_at - a.post.occurred_at || b.post.id.localeCompare(a.post.id));
}

/** Toggle one member's 💜 in a server copy, so the card answers before the next read. */
export function withHeart(post: FriendPost, memberId: string, memberName: string, on: boolean): FriendPost {
  const rest = (post.reactions ?? []).filter((r) => !(r.emoji === '💜' && r.member_id === memberId));
  return { ...post, reactions: on ? [...rest, { emoji: '💜', member_id: memberId, member_name: memberName }] : rest };
}
