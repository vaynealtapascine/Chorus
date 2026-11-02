import type { PostRow } from './posts';

/** Counts and lookups behind the Journal action row, all from the local replica (Android's
 * `JournalSocial`). Build once per list, not per card: each card reads from the same index. */
export interface SocialIndex {
  byId: Map<string, PostRow>;
  replies: Map<string, number>;
  /** Live reposts of each post (a deleted repost is a tombstone and no longer counts). */
  reposts: Map<string, PostRow[]>;
}

export function socialIndex(rows: readonly PostRow[]): SocialIndex {
  const byId = new Map<string, PostRow>();
  const replies = new Map<string, number>();
  const reposts = new Map<string, PostRow[]>();
  for (const post of rows) {
    byId.set(post.id, post);
    if (post.deleted) continue;
    if (post.reply_to) replies.set(post.reply_to, (replies.get(post.reply_to) ?? 0) + 1);
    if (post.repost_of) reposts.set(post.repost_of, [...(reposts.get(post.repost_of) ?? []), post]);
  }
  return { byId, replies, reposts };
}

/** The acting member's own repost of a post: what "Undo repost" deletes. */
export function repostBy(index: SocialIndex, postId: string, memberId: string): PostRow | undefined {
  return index.reposts.get(postId)?.find((r) => r.authors.includes(memberId));
}

/** A repost shows its original one level deep; a repost of a repost, or a gone original, is unavailable. */
export function repostOriginal(index: SocialIndex, post: PostRow): PostRow | null {
  if (!post.repost_of) return null;
  const original = index.byId.get(post.repost_of);
  return original && !original.deleted && !original.repost_of ? original : null;
}

/** A repost has no text of its own; its audience is chosen like any post's (private by default). */
export function repostPayload(authorId: string, originalId: string, audience: 'private' | 'followers' | 'server' = 'private') {
  return {
    kind: 'note', authors: [authorId], title: null, text: '', entities: [], mood: null, tags: [], cw: null,
    visibility: { mode: audience }, repost_of: originalId,
  };
}

/** Saved journal posts: account-wide `bookmark:<post id>` = true, private to this account's devices. */
export const BOOKMARK = 'bookmark:';

type PrefRows = Record<string, { fields: Record<string, unknown> } | undefined>;

/** Account-wide (no device) bookmarks; a pending local change (empty account id) wins over the stamped row. */
export function savedPosts(prefs: PrefRows, accountId: string): Set<string> {
  const stamped = new Map<string, unknown>();
  const pending = new Map<string, unknown>();
  for (const [k, row] of Object.entries(prefs)) {
    const [account, device, key] = k.split('|');
    if (device || (account && account !== accountId) || !key?.startsWith(BOOKMARK) || !row) continue;
    (account ? stamped : pending).set(key.slice(BOOKMARK.length), row.fields.value);
  }
  const saved = new Set<string>();
  for (const [id, value] of new Map([...stamped, ...pending])) if (value === true && id) saved.add(id);
  return saved;
}

/** `pref.set` payload; null clears the pref rather than storing false. */
export function bookmarkPayload(postId: string, saved: boolean) {
  return { device: '', key: BOOKMARK + postId, value: saved ? true : null };
}

/** Newest first, the order of the Saved view; reposts are never saved in place of the original. */
export function savedList(rows: readonly PostRow[], saved: Set<string>): PostRow[] {
  return rows.filter((p) => saved.has(p.id) && !p.deleted && !p.repost_of)
    .sort((a, b) => b.occurred_at - a.occurred_at || b.id.localeCompare(a.id));
}

/** Plain text to share: the content warning comes first so nothing is shared without it. */
export function shareText(title: string | undefined, cw: string | undefined, text: string): string {
  return [cw?.trim() ? `Content warning: ${cw.trim()}` : null, title?.trim() || null, text.trim() || null]
    .filter((s): s is string => !!s).join('\n\n');
}
