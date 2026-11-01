import type { Entity } from './core';
import type { AttachmentRow } from './data';
import type { PollTally } from './polls';
import type { PostRow } from './posts';

/** A post as `GET /posts/{id}` sends it, read through the server's audience check (API.md §3). */
export interface ThreadPost {
  id: string;
  text: string;
  title?: string | null;
  cw?: string | null;
  entities?: Entity[];
  attachments?: AttachmentRow[];
  occurred_at: number;
  authors?: string[];
  author_cards?: { name?: string | null; display_name?: string | null; color?: string | null }[];
  /** Replies this reader may see. */
  reply_count?: number;
  reply_to?: string | null;
  poll?: unknown;
  poll_tally?: PollTally | null;
  quote?: { post_id?: string } | null;
  /** The quoted post, when this reader may read it. */
  quoted?: ThreadPost | null;
}

/** The thread view's read: the post, what it answers (oldest first), its replies and reactions. */
export interface PostDetail extends ThreadPost {
  replies?: ThreadPost[];
  ancestors?: ThreadPost[];
  /** Posts quoting this one, newest first. */
  quotes?: ThreadPost[];
  reactions?: { emoji: string; member_id?: string; member_name: string }[];
}

/** One post in a thread. Own posts keep their synced row (`local`), so they keep every action;
 * other accounts' posts are only what the server sent (`remote`). */
export interface ThreadItem {
  id: string;
  occurred_at: number;
  local?: PostRow;
  remote?: ThreadPost;
  replyCount: number;
}

/** How many live replies each post has on this device. */
export function replyCounts(rows: PostRow[]): Map<string, number> {
  const counts = new Map<string, number>();
  for (const post of rows) if (!post.deleted && post.reply_to) counts.set(post.reply_to, (counts.get(post.reply_to) ?? 0) + 1);
  return counts;
}

/** What a post answers, oldest first, from posts on this device; stops at a missing parent, at a
 * loop (the post itself never shows above itself) and after 20 levels, as the server does. */
export function localAncestors(rows: PostRow[], id: string): PostRow[] {
  const byId = new Map(rows.filter((post) => !post.deleted).map((post) => [post.id, post]));
  const chain: PostRow[] = [];
  const seen = new Set([id]);
  let next = byId.get(id)?.reply_to;
  while (next && chain.length < 20 && !seen.has(next)) {
    seen.add(next);
    const parent = byId.get(next);
    if (!parent) break;
    chain.push(parent);
    next = parent.reply_to;
  }
  return chain.reverse();
}

const item = (local: PostRow | undefined, remote: ThreadPost | undefined, counts: Map<string, number>): ThreadItem => {
  const id = (local?.id ?? remote?.id)!;
  return { id, occurred_at: local?.occurred_at ?? remote?.occurred_at ?? 0, local, remote,
    replyCount: Math.max(counts.get(id) ?? 0, remote?.reply_count ?? 0) };
};

/** The server's replies plus own replies it hasn't got yet (offline, or waiting to sync), once each,
 * oldest first. */
export function threadReplies(rows: PostRow[], id: string, remote: ThreadPost[]): ThreadItem[] {
  const counts = replyCounts(rows);
  const byId = new Map(rows.filter((post) => !post.deleted).map((post) => [post.id, post]));
  const items = remote.map((reply) => item(byId.get(reply.id), reply, counts));
  const known = new Set(items.map((reply) => reply.id));
  for (const post of byId.values()) if (post.reply_to === id && !known.has(post.id)) items.push(item(post, undefined, counts));
  return items.sort((a, b) => a.occurred_at - b.occurred_at || a.id.localeCompare(b.id));
}

/** The server's chain reaches other accounts' posts; offline, own parents still give context. */
export function threadAncestors(rows: PostRow[], id: string, remote: ThreadPost[] | undefined): ThreadItem[] {
  const counts = replyCounts(rows);
  const byId = new Map(rows.filter((post) => !post.deleted).map((post) => [post.id, post]));
  if (remote?.length) return remote.map((parent) => item(byId.get(parent.id), parent, counts));
  return localAncestors(rows, id).map((parent) => item(parent, undefined, counts));
}

/** "Kai & Rin" for a remote post, from its author cards. */
export function remoteNames(post: ThreadPost): string {
  return (post.author_cards ?? []).map((a) => a.display_name || a.name || '').filter(Boolean).join(' & ') || 'Someone';
}

/** Posts quoting [id]: the server's plus own ones it hasn't got yet, newest first. Reposts aren't quotes. */
export function threadQuotes(rows: PostRow[], id: string, remote: ThreadPost[]): ThreadItem[] {
  const counts = replyCounts(rows);
  const byId = new Map(rows.filter((post) => !post.deleted).map((post) => [post.id, post]));
  const items = remote.map((quote) => item(byId.get(quote.id), quote, counts));
  const known = new Set(items.map((quote) => quote.id));
  for (const post of byId.values()) if (post.quote_post_id === id && !post.repost_of && !known.has(post.id)) items.push(item(post, undefined, counts));
  return items.sort((a, b) => b.occurred_at - a.occurred_at || b.id.localeCompare(a.id));
}

/** What a quote card shows: the device's own copy when it has one, else the server's; `post` is null
 * when neither is readable, and the card says the quoted post is unavailable. */
export interface QuoteView {
  id: string;
  post: { text: string; title?: string | null; cw?: string | null; entities?: Entity[]; occurred_at: number } | null;
  names: string;
}

export function quoteView(quoteId: string | null | undefined, byId: Map<string, PostRow>, name: (memberId: string) => string,
  remote?: ThreadPost | null): QuoteView | null {
  if (!quoteId) return null;
  const local = byId.get(quoteId);
  if (local && !local.deleted && !local.repost_of) return { id: quoteId, post: local, names: local.authors.map(name).join(' & ') || 'Someone' };
  if (remote) return { id: quoteId, post: remote, names: remoteNames(remote) };
  return { id: quoteId, post: null, names: '' };
}
