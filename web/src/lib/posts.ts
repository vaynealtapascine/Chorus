import type { Entity } from './core';
import type { Projection } from './sync/client';

export interface PostRow {
  id: string;
  account_id?: string;
  kind: 'note' | 'entry';
  authors: string[];
  title?: string;
  text: string;
  entities: Entity[];
  occurred_at: number;
  mood?: string;
  tags: string[];
  cw?: string;
  visibility: { mode: string; bucket_ids?: string[] };
  reply_to?: string;
  deleted: boolean;
}

const string = (value: unknown) => typeof value === 'string' && value.length ? value : undefined;

/** Typed journal rows from the current delta-updated projection. */
export function posts(p: Projection): PostRow[] {
  return Object.entries(p.rows.post ?? {}).filter(([, row]) => row.exists).map(([id, row]) => {
    const f = row.fields;
    return {
      id,
      account_id: string(f.account_id),
      kind: (f.kind === 'entry' ? 'entry' : 'note') as PostRow['kind'],
      authors: Array.isArray(f.authors) ? f.authors.filter((v): v is string => typeof v === 'string') : [],
      title: string(f.title), text: string(f.text) ?? '',
      entities: Array.isArray(f.entities) ? f.entities as Entity[] : [],
      occurred_at: typeof f.occurred_at === 'number' ? f.occurred_at : 0,
      mood: string(f.mood), tags: Array.isArray(f.tags) ? f.tags.filter((v): v is string => typeof v === 'string') : [],
      cw: string(f.cw), visibility: f.visibility && typeof f.visibility === 'object' ? f.visibility as PostRow['visibility'] : { mode: 'private' },
      reply_to: string(f.reply_to), deleted: f.deleted_at != null,
    };
  }).sort((a, b) => b.occurred_at - a.occurred_at || b.id.localeCompare(a.id));
}

/** Exact LWW-set element; add and remove must use the same payload. */
export function postReaction(postId: string, emoji: string, memberId: string) {
  return { target_type: 'post', target_id: postId, emoji, member_id: memberId };
}

/** Only posts written by this member; replies have their own profile tab. */
export function memberPosts(rows: PostRow[], memberId: string, replies: boolean): PostRow[] {
  return rows.filter((post) => !post.deleted && post.authors.includes(memberId) && Boolean(post.reply_to) === replies);
}

/** A locally pending member has no server stamp yet; a stamped foreign author is read-only. */
export function canWriteAs(p: Projection, memberId: string, accountId: string): boolean {
  const row = p.rows.member?.[memberId];
  if (!row?.exists || row.fields.deleted_at != null || row.fields.archived_at != null) return false;
  const owner = row.fields.created_by_account_id;
  return owner == null || owner === accountId;
}
