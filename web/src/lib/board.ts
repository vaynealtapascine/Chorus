import type { PostRow } from './posts';

/** The System board (D-135) lists our system's own top-level posts: private, not replies or reposts. */
export const isBoardPost = (post: PostRow): boolean =>
  !post.deleted && post.visibility.mode === 'private' && !post.reply_to && !post.repost_of;

/** The board's posts, newest first; the Polls tab keeps those that carry a poll. */
export function boardPosts(all: PostRow[], hasPoll: (id: string) => boolean, tab: 'posts' | 'polls'): PostRow[] {
  return all.filter(isBoardPost).filter((p) => tab === 'posts' || hasPoll(p.id)).sort((a, b) => b.occurred_at - a.occurred_at || b.id.localeCompare(a.id));
}
