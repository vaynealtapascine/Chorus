// Typed views over the core projection (the reference model's output, DATA_MODEL.md §4).
import type { Projection } from './sync/client';
import { patchedFrom } from './sync/delta';

export interface ProxyTag {
  prefix: string;
  suffix: string;
}

export interface MemberRow {
  id: string;
  name: string;
  display_name?: string;
  pronouns?: string;
  color: string;
  sigils: string[];
  proxy_tags: ProxyTag[];
  description?: string;
  birthday?: string;
  avatar_blob?: string;
  banner_blob?: string;
  pinned_post_id?: string;
  archived: boolean;
  deleted: boolean;
  created_at?: number;
  /** The one member of a person account (D-003). */
  is_self?: boolean;
}

export interface GroupRow {
  id: string;
  name: string;
  kind: 'subsystem' | 'group';
  parent_id?: string;
  color?: string;
  avatar_blob?: string;
  deleted: boolean;
}

export interface FieldDef {
  id: string;
  name: string;
  type: 'text' | 'long_text' | 'number' | 'date' | 'select' | 'boolean' | 'url' | 'color';
  options: { choices?: string[] };
  sort_key?: string;
  deleted: boolean;
}

type Rows = Record<string, { exists: boolean; fields: Record<string, unknown> }>;

const str = (v: unknown): string | undefined => (typeof v === 'string' && v !== '' ? v : undefined);

export interface EmojiRow {
  id: string;
  name: string;
  aliases: string[];
  category: string;
  blob_hash: string;
  is_animated: boolean;
  deleted: boolean;
}

export interface BucketRow {
  id: string;
  name: string;
  ceiling: Record<string, unknown>;
}

export interface RelationshipTypeRow {
  id: string;
  name: string;
  inverse_name?: string;
  is_symmetric: boolean;
  color?: string;
}

export interface RelationshipRow {
  id: string;
  from_member_id: string;
  to_kind: 'member' | 'account' | 'external';
  to_id?: string;
  to_label?: string;
  type_id: string;
  note?: string;
  visibility: { mode: string };
}

export interface MemberListRow {
  id: string;
  name: string;
  description?: string;
  visibility: { mode: string };
}

export function memberLists(p: Projection): MemberListRow[] {
  return Object.entries((p.rows.member_list ?? {}) as Rows)
    .filter(([, row]) => row.exists && row.fields.deleted_at == null)
    .map(([id, row]) => ({ id, name: str(row.fields.name) ?? 'Untitled', description: str(row.fields.description),
      visibility: row.fields.visibility && typeof row.fields.visibility === 'object' && !Array.isArray(row.fields.visibility)
        ? row.fields.visibility as { mode: string } : { mode: 'private' } }))
    .sort((a, b) => a.name.localeCompare(b.name));
}

/** list ID → present member IDs in the LWW element set. */
export function memberListItems(p: Projection): Map<string, Set<string>> {
  const out = new Map<string, Set<string>>();
  for (const [key, present] of Object.entries(p.sets.member_list_item ?? {})) {
    if (!present) continue;
    const bar = key.indexOf('|');
    if (bar < 0) continue;
    try {
      const member = (JSON.parse(key.slice(bar + 1)) as { member_id?: unknown }).member_id;
      if (typeof member !== 'string' || !member) continue;
      const list = key.slice(0, bar);
      const ids = out.get(list) ?? new Set<string>();
      ids.add(member);
      out.set(list, ids);
    } catch { /* malformed set keys cannot add a member */ }
  }
  return out;
}

/** Live relationship definitions and links from the current account's replica. */
export function relationshipTypes(p: Projection): RelationshipTypeRow[] {
  return Object.entries((p.rows.relationship_type ?? {}) as Rows)
    .filter(([, row]) => row.exists && row.fields.deleted_at == null && str(row.fields.name))
    .map(([id, row]) => ({ id, name: str(row.fields.name)!, inverse_name: str(row.fields.inverse_name),
      is_symmetric: row.fields.is_symmetric === true || row.fields.is_symmetric === 1,
      color: str(row.fields.color) }))
    .sort((a, b) => a.name.localeCompare(b.name));
}

export function relationships(p: Projection): RelationshipRow[] {
  return Object.entries((p.rows.relationship ?? {}) as Rows)
    .filter(([, row]) => row.exists && row.fields.deleted_at == null)
    .map(([id, row]): RelationshipRow => ({
      id,
      from_member_id: str(row.fields.from_member_id) ?? '',
      to_kind: row.fields.to_kind === 'member' || row.fields.to_kind === 'account' ? row.fields.to_kind : 'external',
      to_id: str(row.fields.to_id), to_label: str(row.fields.to_label),
      type_id: str(row.fields.type_id) ?? '', note: str(row.fields.note),
      visibility: row.fields.visibility && typeof row.fields.visibility === 'object' && !Array.isArray(row.fields.visibility)
        ? row.fields.visibility as { mode: string } : { mode: 'private' },
    }))
    .filter((row) => row.from_member_id && row.type_id && (row.to_id || row.to_label));
}

export function buckets(p: Projection): BucketRow[] {
  return Object.entries((p.rows.bucket ?? {}) as Rows)
    .filter(([, row]) => row.exists && row.fields.deleted_at == null)
    .map(([id, row]) => ({
      id,
      name: str(row.fields.name) ?? 'Untitled',
      ceiling: row.fields.ceiling && typeof row.fields.ceiling === 'object' && !Array.isArray(row.fields.ceiling)
        ? row.fields.ceiling as Record<string, unknown> : {},
    }))
    .sort((a, b) => a.name.localeCompare(b.name));
}

/** bucket ID → follower account IDs from the LWW assignment set. */
export function bucketAssignments(p: Projection): Map<string, Set<string>> {
  const result = new Map<string, Set<string>>();
  for (const [key, present] of Object.entries(p.sets.bucket_assignment ?? {})) {
    if (!present) continue;
    const bar = key.indexOf('|');
    if (bar < 0) continue;
    try {
      const follower = (JSON.parse(key.slice(bar + 1)) as { follower_account_id?: unknown }).follower_account_id;
      if (typeof follower !== 'string') continue;
      const bucket = key.slice(0, bar);
      const members = result.get(bucket) ?? new Set<string>();
      members.add(follower);
      result.set(bucket, members);
    } catch { /* an invalid set key cannot grant access */ }
  }
  return result;
}

/** Keep retired rows so old messages can still resolve their stable emoji IDs. */
export function customEmojis(p: Projection): EmojiRow[] {
  return Object.entries((p.rows.custom_emoji ?? {}) as Rows)
    .filter(([, row]) => row.exists)
    .map(([id, row]) => ({
      id,
      name: str(row.fields.name) ?? '',
      aliases: Array.isArray(row.fields.aliases) ? row.fields.aliases.filter((a): a is string => typeof a === 'string') : [],
      category: str(row.fields.category) ?? 'Custom',
      blob_hash: str(row.fields.blob_hash) ?? '',
      is_animated: row.fields.is_animated === true,
      deleted: row.fields.deleted_at != null,
    }))
    .filter((row) => !!row.blob_hash)
    .sort((a, b) => a.category.localeCompare(b.category) || a.name.localeCompare(b.name));
}

export function members(p: Projection): MemberRow[] {
  const rows = (p.rows.member ?? {}) as Rows;
  return Object.entries(rows)
    .filter(([, r]) => r.exists)
    .map(([id, r]) => {
      const f = r.fields;
      return {
        id,
        name: str(f.name) ?? 'Unnamed',
        display_name: str(f.display_name),
        pronouns: str(f.pronouns),
        color: str(f.color) ?? '#A09184',
        sigils: Array.isArray(f.sigils) ? (f.sigils as string[]) : [],
        proxy_tags: Array.isArray(f.proxy_tags) ? (f.proxy_tags as ProxyTag[]) : [],
        description: str(f.description),
        birthday: str(f.birthday),
        avatar_blob: str(f.avatar_blob),
        banner_blob: str(f.banner_blob),
        pinned_post_id: str(f.pinned_post_id),
        archived: f.archived_at != null,
        deleted: f.deleted_at != null,
        created_at: typeof f.created_at === 'number' ? f.created_at : undefined,
        is_self: f.is_self === true || f.is_self === 1,
      };
    })
    .sort((a, b) => a.name.localeCompare(b.name));
}

/** A person account's self member (D-003); present means this account is a person, whose member
 *  and switching UI is hidden. Only the server creates it, at enrolment. */
export function selfMember(p: Projection): MemberRow | undefined {
  return members(p).find((m) => m.is_self && !m.deleted);
}

export function groups(p: Projection): GroupRow[] {
  const rows = (p.rows.member_group ?? {}) as Rows;
  return Object.entries(rows)
    .filter(([, r]) => r.exists)
    .map(([id, r]) => ({
      id,
      name: str(r.fields.name) ?? 'Untitled',
      kind: r.fields.kind === 'group' ? 'group' : 'subsystem',
      parent_id: str(r.fields.parent_id),
      color: str(r.fields.color),
      avatar_blob: str(r.fields.avatar_blob),
      deleted: r.fields.deleted_at != null,
    }))
    .filter((g) => !g.deleted)
    .sort((a, b) => a.name.localeCompare(b.name)) as GroupRow[];
}

/** group id → member ids (LWW element set keys are "<group>|{"member_id":"…"}"). */
export function membership(p: Projection): Map<string, Set<string>> {
  const out = new Map<string, Set<string>>();
  for (const [key, present] of Object.entries(p.sets.group_membership ?? {})) {
    if (!present) continue;
    const bar = key.indexOf('|');
    const group = key.slice(0, bar);
    const member = (JSON.parse(key.slice(bar + 1)) as { member_id: string }).member_id;
    if (!out.has(group)) out.set(group, new Set());
    out.get(group)!.add(member);
  }
  return out;
}

export function fieldDefs(p: Projection): FieldDef[] {
  const rows = (p.rows.field_def ?? {}) as Rows;
  return Object.entries(rows)
    .filter(([, r]) => r.exists && r.fields.deleted_at == null)
    .map(([id, r]) => ({
      id,
      name: str(r.fields.name) ?? 'Field',
      type: (str(r.fields.type) ?? 'text') as FieldDef['type'],
      options: (r.fields.options as FieldDef['options']) ?? {},
      sort_key: str(r.fields.sort_key),
      deleted: false,
    }))
    .sort((a, b) => (a.sort_key ?? a.name).localeCompare(b.sort_key ?? b.name));
}

/** member id → field id → value */
export function fieldValues(p: Projection): Map<string, Map<string, unknown>> {
  const out = new Map<string, Map<string, unknown>>();
  for (const [key, r] of Object.entries((p.rows.field_value ?? {}) as Rows)) {
    const [member, field] = key.split('|');
    if (!out.has(member)) out.set(member, new Map());
    out.get(member)!.set(field, r.fields.value);
  }
  return out;
}

/** Path of subsystem names from root, e.g. "Stars / Inner". */
export function groupPath(g: GroupRow, all: GroupRow[]): string {
  const byId = new Map(all.map((x) => [x.id, x]));
  const names: string[] = [];
  let cur: GroupRow | undefined = g;
  const seen = new Set<string>();
  while (cur && !seen.has(cur.id)) {
    seen.add(cur.id);
    names.unshift(cur.name);
    cur = cur.parent_id ? byId.get(cur.parent_id) : undefined;
  }
  return names.join(' / ');
}

/** Simple fuzzy match: every query char appears in order. Lower score = better. */
export function fuzzy(query: string, text: string): number | null {
  const q = query.toLowerCase().trim();
  if (!q) return 0;
  const t = text.toLowerCase();
  const direct = t.indexOf(q);
  if (direct >= 0) return direct;
  let ti = 0;
  let gaps = 0;
  for (const ch of q) {
    const found = t.indexOf(ch, ti);
    if (found < 0) return null;
    gaps += found - ti;
    ti = found + 1;
  }
  return 100 + gaps;
}

// ─── chat ────────────────────────────────────────────────────────────────────

export interface SpaceRow {
  id: string;
  kind: 'internal' | 'shared' | 'dm';
  name: string;
}

export interface ChannelRow {
  id: string;
  space_id: string;
  kind: 'text' | 'thread' | 'member_dm';
  name: string;
  topic?: string;
  category?: string;
  parent_message_id?: string;
  archived: boolean;
}

export interface ThreadSummary {
  channel: ChannelRow;
  replyCount: number;
  lastRepliers: string[];
}

/** Pending local prefs use an empty account id until the server stamps the op. */
export function segmentParsing(p: Projection, accountId: string): boolean {
  const prefs = (p.rows.pref ?? {}) as Rows;
  const row = prefs['||chat.segment_parsing'] ?? prefs[`${accountId}||chat.segment_parsing`];
  return row?.fields.value !== false;
}

export function contentWarningsAutoExpand(p: Projection, accountId: string): boolean {
  const prefs = (p.rows.pref ?? {}) as Rows;
  const row = prefs['||chat.cw_auto_expand'] ?? prefs[`${accountId}||chat.cw_auto_expand`];
  return row?.fields.value === true;
}

export interface Segment {
  offset: number;
  length: number;
  authors: string[];
}

export interface MessageRow {
  id: string;
  channel_id: string;
  authors: string[];
  text: string;
  entities: import('./core').Entity[];
  segments: Segment[];
  occurred_at: number;
  account_id?: string;
  sent_offline: boolean;
  edited: boolean;
  deleted: boolean;
  pinned: boolean;
  cw?: string;
  visibility?: { mode: string; member_ids?: string[] };
  reply_to?: string;
  quote?: QuoteValue;
  forward_snapshot?: SnapshotItem[];
  attachments: AttachmentRow[];
}

export interface AttachmentRow {
  id: string;
  blob_hash: string;
  thumb_blob_hash?: string;
  filename: string;
  mime: string;
  size: number;
  alt_text: string;
  is_spoiler: boolean;
}

export interface TextRange { message_id: string; offset: number; length: number; text: string }
export interface SnapshotItem {
  message_id: string;
  offset?: number;
  length?: number;
  text: string;
  channel_name?: string;
  authors: string[];
  entities: import('./core').Entity[];
  occurred_at: number;
  attachments?: AttachmentRow[];
}
export type QuoteValue = TextRange | { items: SnapshotItem[] };

export type TrashKind = 'message' | 'post' | 'member' | 'group' | 'channel';
export interface TrashItem {
  id: string;
  kind: TrashKind;
  label: string;
  detail: string;
  deletedAt: number;
  scope: string;
  channelId?: string;
}

/** Show only items this account authored or deleted; pending local deletes have no stamp yet. */
export function trashItems(p: Projection, accountId: string): TrashItem[] {
  const channelRows = (p.rows.channel ?? {}) as Rows;
  const names = new Map(Object.entries(channelRows).map(([id, row]) => [id, str(row.fields.name) ?? 'channel']));
  const out: TrashItem[] = [];
  for (const [table, kind] of [
    ['message', 'message'], ['post', 'post'], ['member', 'member'], ['member_group', 'group'], ['channel', 'channel'],
  ] as const) {
    for (const [id, row] of Object.entries((p.rows[table] ?? {}) as Rows)) {
      if (!row.exists || typeof row.fields.deleted_at !== 'number') continue;
      const f = row.fields;
      if (f.created_by_account_id !== accountId && f.deleted_by_account_id !== accountId && f.deleted_by_account_id != null) continue;
      const channel = channelRows[str(f.channel_id) ?? ''];
      const spaceId = table === 'message' ? str(channel?.fields.space_id) : str(f.space_id);
      const scope = table === 'message' || table === 'channel' ? (spaceId ? `space:${spaceId}` : '') : `account:${accountId}`;
      const label = table === 'post' ? (f.kind === 'entry' ? 'Entry' : 'Post') :
        table === 'member_group' ? 'Group' : kind[0].toUpperCase() + kind.slice(1);
      const detail = table === 'message' ? `#${names.get(str(f.channel_id) ?? '') ?? 'channel'} · ${str(f.text) ?? '(empty message)'}` :
        str(f.name) ?? str(f.title) ?? str(f.text) ?? '(untitled)';
      out.push({ id, kind, label, detail, deletedAt: f.deleted_at as number, scope,
        channelId: table === 'message' ? str(f.channel_id) : undefined });
    }
  }
  return out.sort((a, b) => b.deletedAt - a.deletedAt || a.id.localeCompare(b.id));
}

export function spaces(p: Projection): SpaceRow[] {
  return Object.entries((p.rows.space ?? {}) as Rows)
    .filter(([, r]) => r.exists && r.fields.deleted_at == null)
    .map(([id, r]) => ({
      id,
      kind: (str(r.fields.kind) ?? 'shared') as SpaceRow['kind'],
      name: str(r.fields.name) ?? 'Space',
    }))
    .sort((a, b) => (a.kind === 'internal' ? -1 : b.kind === 'internal' ? 1 : a.name.localeCompare(b.name)));
}

export function channels(p: Projection, spaceId?: string): ChannelRow[] {
  return Object.entries((p.rows.channel ?? {}) as Rows)
    .filter(([, r]) => r.exists && r.fields.deleted_at == null)
    .map(([id, r]) => ({
      id,
      space_id: str(r.fields.space_id) ?? '',
      kind: (str(r.fields.kind) ?? 'text') as ChannelRow['kind'],
      name: str(r.fields.name) ?? 'channel',
      topic: str(r.fields.topic),
      category: str(r.fields.category),
      parent_message_id: str(r.fields.parent_message_id),
      archived: r.fields.archived_at != null,
    }))
    .filter((c) => !spaceId || c.space_id === spaceId)
    .sort((a, b) => a.name.localeCompare(b.name));
}

/** One pass over messages for the previews under thread parents. */
export function threadSummaries(p: Projection): Map<string, ThreadSummary> {
  const summaries = new Map<string, ThreadSummary>();
  const byChannel = new Map<string, { summary: ThreadSummary; latestByAuthor: Map<string, { at: number; id: string; position: number }> }>();
  const allChannels = channels(p);
  const channelById = new Map(allChannels.map((channel) => [channel.id, channel]));
  const messageRows = (p.rows.message ?? {}) as Rows;
  for (const channel of allChannels) {
    if (channel.kind !== 'thread' || channel.archived || !channel.parent_message_id) continue;
    const parent = messageRows[channel.parent_message_id];
    if (!parent?.exists || channelById.get(str(parent.fields.channel_id) ?? '')?.space_id !== channel.space_id) continue;
    const summary: ThreadSummary = { channel, replyCount: 0, lastRepliers: [] };
    summaries.set(channel.parent_message_id, summary);
    byChannel.set(channel.id, { summary, latestByAuthor: new Map() });
  }
  for (const [id, row] of Object.entries(messageRows)) {
    if (!row.exists || row.fields.deleted_at != null) continue;
    const entry = byChannel.get(str(row.fields.channel_id) ?? '');
    if (!entry) continue;
    entry.summary.replyCount++;
    const at = typeof row.fields.occurred_at === 'number' ? row.fields.occurred_at : 0;
    const authors = Array.isArray(row.fields.authors) ? row.fields.authors : [];
    for (const [position, author] of authors.entries()) {
      if (typeof author !== 'string') continue;
      const previous = entry.latestByAuthor.get(author);
      if (!previous || at > previous.at || (at === previous.at && id > previous.id)) {
        entry.latestByAuthor.set(author, { at, id, position });
      }
    }
  }
  for (const { summary, latestByAuthor } of byChannel.values()) {
    summary.lastRepliers = [...latestByAuthor.entries()]
      .sort((a, b) => b[1].at - a[1].at || b[1].id.localeCompare(a[1].id) || a[1].position - b[1].position)
      .slice(0, 3).map(([author]) => author);
  }
  return summaries;
}

type MessageRecord = { exists: boolean; fields: Record<string, unknown>; edits?: number };

// Tables are copy-on-write (sync/delta.ts): a table object changes only when one of its rows does,
// and untouched row objects are shared. So the per-channel order is cached per message table, and
// each built row per (attachment table, row object): opening a 50k-message channel or sending one
// message doesn't rebuild every row (SPEC §9).
const channelOrder = new WeakMap<object, Map<string, string[]>>();
const builtRows = new WeakMap<object, WeakMap<object, MessageRow>>();
const NO_ATTACHMENTS = {};

function orderOf(rows: Record<string, MessageRecord>): Map<string, string[]> {
  let order = channelOrder.get(rows);
  if (order) return order;
  // a table from a delta: patch the order of the table it came from, if that one was indexed
  const from = patchedFrom.get(rows);
  const prevOrder = from && channelOrder.get(from.prev);
  order = from && prevOrder
    ? patchOrder(from.prev as Record<string, MessageRecord>, prevOrder, rows, from.keys)
    : buildOrder(rows);
  channelOrder.set(rows, order);
  return order;
}

const atOf = (r: MessageRecord) => (typeof r.fields.occurred_at === 'number' ? r.fields.occurred_at : 0);

/** The previous order with the changed rows moved (a send, an edit, a delete). */
function patchOrder(
  prev: Record<string, MessageRecord>,
  prevOrder: Map<string, string[]>,
  rows: Record<string, MessageRecord>,
  changed: string[],
): Map<string, string[]> {
  if (changed.length > 512) return buildOrder(rows);
  const order = new Map(prevOrder);
  const touched = new Set(changed);
  // first take every changed row out of the lists it was in (so the rest stays sorted)…
  const copied = new Set<string>();
  for (const id of changed) {
    const old = prev[id];
    if (old?.exists) copied.add(String(old.fields.channel_id ?? ''));
  }
  for (const id of changed) {
    const r = rows[id];
    if (r?.exists) copied.add(String(r.fields.channel_id ?? ''));
  }
  for (const ch of copied) {
    const before = order.get(ch) ?? [];
    const ids = before.filter((id) => !touched.has(id));
    derivedList.set(ids, { prev: before, changed: touched });
    order.set(ch, ids);
  }
  // …then put the ones that still exist back where they now sort
  for (const id of changed) {
    const r = rows[id];
    if (!r?.exists) continue;
    const ids = order.get(String(r.fields.channel_id ?? ''))!;
    const at = atOf(r);
    // binary search for the first message sorting after this one (newest is the usual case)
    let lo = 0;
    let hi = ids.length;
    while (lo < hi) {
      const mid = (lo + hi) >> 1;
      const other = atOf(rows[ids[mid]]);
      if (other < at || (other === at && ids[mid] < id)) lo = mid + 1;
      else hi = mid;
    }
    ids.splice(lo, 0, id);
  }
  return order;
}

function buildOrder(rows: Record<string, MessageRecord>): Map<string, string[]> {
  const at = (id: string) => atOf(rows[id]);
  const order = new Map<string, string[]>();
  for (const [id, r] of Object.entries(rows)) {
    if (!r.exists) continue;
    const ch = String(r.fields.channel_id ?? '');
    let ids = order.get(ch);
    if (!ids) order.set(ch, (ids = []));
    ids.push(id);
  }
  for (const ids of order.values()) ids.sort((a, b) => at(a) - at(b) || (a < b ? -1 : a > b ? 1 : 0));
  return order;
}

function messageRow(p: Projection, id: string): MessageRow | undefined {
  const r = ((p.rows.message ?? {}) as Record<string, MessageRecord>)[id];
  if (!r?.exists) return undefined;
  const attachmentRows = p.rows.attachment ?? NO_ATTACHMENTS;
  let cache = builtRows.get(attachmentRows);
  if (!cache) builtRows.set(attachmentRows, (cache = new WeakMap()));
  const hit = cache.get(r);
  if (hit) return hit;
  const attachment = (aid: string): AttachmentRow | null => {
    const a = (attachmentRows as Rows)[aid];
    if (!a?.exists) return null;
    const f = a.fields;
    const blob_hash = str(f.blob_hash);
    if (!blob_hash) return null;
    return {
      id: aid, blob_hash, thumb_blob_hash: str(f.thumb_blob_hash), filename: str(f.filename) ?? 'file',
      mime: str(f.mime) ?? 'application/octet-stream', size: Number(f.size ?? 0),
      alt_text: str(f.alt_text) ?? '', is_spoiler: f.is_spoiler === true,
    };
  };
  const f = r.fields;
  const authors = Array.isArray(f.authors) ? (f.authors as string[]) : [];
  const text = typeof f.text === 'string' ? f.text : '';
  const segs = Array.isArray(f.segments) && f.segments.length ? (f.segments as Segment[]) : [{ offset: 0, length: text.length, authors }];
  const m: MessageRow = {
    id,
    channel_id: String(f.channel_id ?? ''),
    authors,
    text,
    entities: Array.isArray(f.entities) ? (f.entities as MessageRow['entities']) : [],
    segments: segs,
    occurred_at: typeof f.occurred_at === 'number' ? f.occurred_at : 0,
    account_id: str(f.account_id),
    sent_offline: f.sent_offline === true,
    edited: (r.edits ?? 0) > 0,
    deleted: f.deleted_at != null,
    pinned: f.pinned_at != null,
    cw: str(f.cw),
    visibility: f.visibility && typeof f.visibility === 'object' ? f.visibility as MessageRow['visibility'] : undefined,
    reply_to: str(f.reply_to),
    quote: f.quote && typeof f.quote === 'object' ? (f.quote as QuoteValue) : undefined,
    forward_snapshot: Array.isArray(f.forward_snapshot) ? (f.forward_snapshot as MessageRow['forward_snapshot']) : undefined,
    attachments: Array.isArray(f.attachments) ? (f.attachments as string[]).map(attachment).filter((a): a is AttachmentRow => !!a) : [],
  };
  cache.set(r, m);
  return m;
}

// a patched channel list → the list it came from and the ids that changed; built rows per list
const derivedList = new WeakMap<string[], { prev: string[]; changed: Set<string> }>();
const builtLists = new WeakMap<string[], { attachments: object; rows: MessageRow[] }>();

/** A channel's messages, oldest first. */
export function messages(p: Projection, channelId: string): MessageRow[] {
  const rows = (p.rows.message ?? {}) as Record<string, MessageRecord>;
  const ids = orderOf(rows).get(channelId);
  if (!ids) return [];
  const attachments = p.rows.attachment ?? NO_ATTACHMENTS;
  const hit = builtLists.get(ids);
  if (hit?.attachments === attachments) return hit.rows;
  const from = derivedList.get(ids);
  const prev = from && builtLists.get(from.prev);
  let out: MessageRow[];
  if (from && prev?.attachments === attachments) {
    // unchanged rows keep their place relative to each other: walk both lists once
    out = new Array(ids.length);
    let j = 0;
    for (let i = 0; i < ids.length; i++) {
      const id = ids[i];
      if (from.changed.has(id)) {
        out[i] = messageRow(p, id)!;
        continue;
      }
      while (from.prev[j] !== id) j++;
      out[i] = prev.rows[j++];
    }
  } else {
    out = ids.map((id) => messageRow(p, id)!);
  }
  builtLists.set(ids, { attachments, rows: out });
  return out;
}

/** message id → emoji → member ids (LWW element set, DATA_MODEL `reaction`). */
export function reactions(p: Projection): Map<string, Map<string, string[]>> {
  const out = new Map<string, Map<string, string[]>>();
  for (const [key, present] of Object.entries(p.sets.reaction ?? {})) {
    if (!present) continue;
    const r = JSON.parse(key.slice(key.indexOf('|') + 1)) as { target_id: string; emoji: string; member_id: string };
    if (!out.has(r.target_id)) out.set(r.target_id, new Map());
    const byEmoji = out.get(r.target_id)!;
    byEmoji.set(r.emoji, [...(byEmoji.get(r.emoji) ?? []), r.member_id]);
  }
  return out;
}

/** Last read message time for a channel (account-level read state). Pending local marks have no
 *  account id yet, so both keys are checked. */
export function lastRead(p: Projection, channelId: string, accountId: string): number {
  const rows = (p.rows.read_state ?? {}) as Rows;
  const vals = [rows[`${channelId}|${accountId}|`], rows[`${channelId}||`]]
    .map((r) => (r?.fields?.last_read_message_at as number | undefined) ?? 0);
  return Math.max(0, ...vals);
}

export function unread(p: Projection, channelId: string, accountId: string): number {
  const since = lastRead(p, channelId, accountId);
  const rows = (p.rows.message ?? {}) as Record<string, MessageRecord>;
  const ids = orderOf(rows).get(channelId) ?? [];
  let n = 0;
  // newest first, stopping at the read mark
  for (let i = ids.length - 1; i >= 0; i--) {
    const f = rows[ids[i]].fields;
    const at = typeof f.occurred_at === 'number' ? f.occurred_at : 0;
    if (at <= since) break;
    // a message without an account id is still pending on this device, so it's ours
    if (f.deleted_at == null && f.account_id && f.account_id !== accountId) n++;
  }
  return n;
}

/** Any message by id, across channels (for replies elsewhere and forwards). */
export function messageById(p: Projection, id: string): (MessageRow & { channel_name?: string }) | undefined {
  const r = (p.rows.message ?? {})[id];
  if (!r?.exists) return undefined;
  const ch = String(r.fields.channel_id ?? '');
  const m = messageRow(p, id);
  const name = (p.rows.channel ?? {})[ch]?.fields?.name;
  return m ? { ...m, channel_name: typeof name === 'string' ? name : undefined } : undefined;
}
