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
  /** `profile`: the reader may open this member's profile, and only then `avatar_blob` (D-101). */
  author_cards?: { id?: string; name?: string | null; display_name?: string | null; color?: string | null; handle?: string | null;
    avatar_blob?: string | null; profile?: boolean }[];
  account_id?: string;
  tags?: string[] | null;
  /** A member this reader may not see reacts with `member_id` and `member_name` null (D-154). */
  reactions?: { emoji: string; member_id?: string | null; member_name?: string | null }[];
  /** Replies this reader may see. */
  reply_count?: number;
  reply_to?: string | null;
  poll?: unknown;
  poll_tally?: PollTally | null;
  quote?: { post_id?: string } | null;
  /** The quoted post, when this reader may read it. */
  quoted?: ThreadPost | null;
  /** What a listed reply answers, when this reader may read it (`GET /posts` items, D-127). */
  parent?: ThreadPost | null;
}

/** A click anywhere on a post opens it, as on Twitter, except on its own links and controls or
 * when it ends a text selection. Keyboards and screen readers open it by its time link. */
export function opensPost(event: Pick<MouseEvent, 'target'>, selected = window.getSelection()?.toString()): boolean {
  return !selected && !(event.target as Element).closest('a, button, summary, input, select, textarea, label, video, audio, dialog, [role="menu"]');
}

/** Where tapping a post's author goes: the first author whose profile this reader may open, on
 * their account's friend profile page; nowhere when none is shared. */
export function authorProfileHref(post: ThreadPost): string | null {
  const card = post.author_cards?.find((c) => c.profile === true && c.id);
  return post.account_id && card?.id ? `#/people/${encodeURIComponent(post.account_id)}/${encodeURIComponent(card.id)}` : null;
}

/** A post's tags as text ("#garden  #cats"), or nothing. */
export function tagLine(tags: readonly unknown[] | null | undefined): string {
  return (tags ?? []).filter((t): t is string => typeof t === 'string' && t.trim() !== '').map((t) => `#${t.trim()}`).join('  ');
}

/** The thread view's read: the post, what it answers (oldest first), its replies and reactions. */
export interface PostDetail extends ThreadPost {
  replies?: ThreadPost[];
  ancestors?: ThreadPost[];
  /** Posts quoting this one, newest first. */
  quotes?: ThreadPost[];
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

/** A reaction that came without its member (one this reader may not see, D-154) still counts
 * under its emoji: it gets an id of its own that no member has, so it is never one of ours, and
 * shows as "Someone" (an unknown id's name). `key` tells it from the others. */
export const reactorId = (r: { member_id?: string | null }, key: string): string => r.member_id || `someone:${key}`;

/**
 * An own post's reactions on its thread page: the replica's (this account's members) and the
 * server's, which also has other accounts' readers (a friend's heart only reaches us through the
 * server read). Each member once per emoji; `names` has the server's names for members we don't
 * know, so the card can say who.
 */
export function mergedReactions(detail: PostDetail | null, local: (id: string) => Map<string, string[]> | undefined):
  { reactions: (id: string) => Map<string, string[]>; names: Map<string, string> } {
  const server = new Map<string, ThreadPost['reactions']>();
  for (const p of [detail, ...(detail?.ancestors ?? []), ...(detail?.replies ?? [])]) if (p?.reactions?.length) server.set(p.id, p.reactions);
  const names = new Map<string, string>();
  for (const list of server.values()) for (const r of list ?? []) if (r.member_id && r.member_name) names.set(r.member_id, r.member_name);
  return {
    names,
    reactions: (id) => {
      const out = new Map([...(local(id) ?? new Map<string, string[]>())].map(([emoji, ids]) => [emoji, [...ids]] as [string, string[]]));
      for (const [i, r] of (server.get(id) ?? []).entries()) {
        const who = reactorId(r, `${id}:${i}`);
        const ids = out.get(r.emoji) ?? [];
        if (!ids.includes(who)) out.set(r.emoji, [...ids, who]);
      }
      return out;
    },
  };
}
