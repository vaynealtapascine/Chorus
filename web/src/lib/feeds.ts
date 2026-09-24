import { core, type Json } from './core';
import { channels, groups, memberListItems, memberLists, members, membership, messages, type MessageRow } from './data';
import { localDay } from './insights';
import { posts, type PostRow } from './posts';
import type { Projection } from './sync/client';

export interface FeedRow { id: string; name: string; description?: string; query: string; query_ast: Json; visibility: { mode: string } }
export interface FeedCandidate { id: string; occurred_at: number; item: Record<string, unknown>; post?: PostRow; message?: MessageRow; channelName?: string }

const str = (value: unknown) => typeof value === 'string' && value ? value : undefined;

export function savedFeeds(p: Projection): FeedRow[] {
  return Object.entries(p.rows.feed ?? {}).filter(([, row]) => row.exists && row.fields.deleted_at == null)
    .map(([id, row]) => ({ id, name: str(row.fields.name) ?? 'Untitled', description: str(row.fields.description),
      query: str(row.fields.query) ?? '', query_ast: row.fields.query_ast,
      visibility: row.fields.visibility && typeof row.fields.visibility === 'object'
        ? row.fields.visibility as { mode: string } : { mode: 'private' } }))
    .filter((feed) => feed.query && feed.query_ast)
    .sort((a, b) => a.name.localeCompare(b.name));
}

/** Start of the named day in the configured timezone (including DST transitions). */
export function feedDateStart(date: string, timeZone?: string): number | undefined {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || Number.isNaN(Date.parse(`${date}T00:00:00Z`))) return undefined;
  if (!timeZone) return new Date(`${date}T00:00:00`).getTime();
  const center = Date.parse(`${date}T00:00:00Z`);
  let low = center - 2 * 86_400_000, high = center + 2 * 86_400_000;
  try {
    while (high - low > 60_000) {
      const mid = Math.floor((low + high) / 120_000) * 60_000;
      if (localDay(mid, timeZone) < date) low = mid;
      else high = mid;
    }
    return Math.ceil(high / 60_000) * 60_000;
  } catch { return undefined; }
}

function datesIn(ast: Json, out = new Set<string>()): Set<string> {
  if (!ast || typeof ast !== 'object') return out;
  const node = ast as Record<string, unknown>;
  if ((node.op === 'since' || node.op === 'until') && node.at && typeof node.at === 'object') {
    const at = node.at as Record<string, unknown>;
    if (at.type === 'date' && typeof at.date === 'string') out.add(at.date);
  }
  if (Array.isArray(node.args)) for (const arg of node.args) datesIn(arg, out);
  if (node.arg) datesIn(node.arg, out);
  return out;
}

export function feedContext(p: Projection, ast: Json, now = Date.now()): Record<string, unknown> {
  const names = new Map<string, Set<string>>();
  const lists = new Map<string, Set<string>>();
  const add = (map: Map<string, Set<string>>, name: string, ids: Iterable<string>) => {
    const key = name.toLowerCase();
    const values = map.get(key) ?? new Set<string>();
    for (const id of ids) values.add(id);
    map.set(key, values);
  };
  for (const member of members(p).filter((m) => !m.deleted)) {
    for (const name of [member.id, member.name, member.display_name].filter((v): v is string => !!v)) add(names, name, [member.id]);
  }
  const groupMembers = membership(p);
  for (const group of groups(p)) add(names, group.name, groupMembers.get(group.id) ?? []);
  const listMembers = memberListItems(p);
  for (const list of memberLists(p)) add(lists, list.name, listMembers.get(list.id) ?? []);
  const timeZone = str(p.rows.system?.[Object.keys(p.rows.system ?? {})[0]]?.fields.timezone);
  const dates = Object.fromEntries([...datesIn(ast)].map((date) => [date, feedDateStart(date, timeZone)]).filter(([, at]) => at !== undefined));
  return { now, members: Object.fromEntries([...names].map(([name, ids]) => [name, [...ids]])),
    lists: Object.fromEntries([...lists].map(([name, ids]) => [name, [...ids]])), dates };
}

function hasFor(attachments: { mime: string }[], text: string, entities: { type: string }[]): string[] {
  const found = new Set<string>();
  if (attachments.length) found.add('attachment');
  for (const attachment of attachments) {
    if (attachment.mime.startsWith('image/')) found.add('image');
    if (attachment.mime.startsWith('video/')) found.add('video');
    if (attachment.mime.startsWith('audio/')) found.add('audio');
  }
  if (entities.some((e) => e.type === 'link') || /https?:\/\//i.test(text)) found.add('link');
  return [...found];
}

/** Candidate set is restricted to the local replica; server-shared feeds need a scoped endpoint. */
export function feedCandidates(p: Projection, now = Date.now()): FeedCandidate[] {
  const front = new Map<string, { start: number; end: number }[]>();
  for (const record of Object.values(p.fronts)) for (const interval of record.intervals) {
    if (interval.subject_type !== 'member' || interval.level !== 'front') continue;
    const rows = front.get(interval.subject_id) ?? [];
    rows.push({ start: interval.start_at, end: interval.end_at ?? now });
    front.set(interval.subject_id, rows);
  }
  const wasFronting = (ids: string[], at: number) => ids.some((id) => front.get(id)?.some((interval) => interval.start <= at && at < interval.end));
  const candidates: FeedCandidate[] = [];
  for (const post of posts(p).filter((row) => !row.deleted)) {
    const raw = p.rows.post?.[post.id]?.fields;
    const attachments = (Array.isArray(raw?.attachments) ? raw.attachments : [])
      .map((id) => p.rows.attachment?.[String(id)]?.fields).filter((f): f is Record<string, unknown> => !!f)
      .map((f) => ({ mime: str(f.mime) ?? '' }));
    candidates.push({ id: post.id, occurred_at: post.occurred_at, post, item: {
      kind: post.kind, author_ids: post.authors, tags: post.tags, mood: post.mood ?? null,
      has: hasFor(attachments, post.text, post.entities), is_reply: !!post.reply_to,
      occurred_at: post.occurred_at, author_fronting: wasFronting(post.authors, post.occurred_at),
      text: `${post.title ?? ''} ${post.text}`,
    } });
  }
  for (const channel of channels(p)) for (const message of messages(p, channel.id).filter((row) => !row.deleted)) {
    candidates.push({ id: message.id, occurred_at: message.occurred_at, message, channelName: channel.name, item: {
      kind: 'message', author_ids: message.authors, tags: [], mood: null,
      has: hasFor(message.attachments, message.text, message.entities), is_reply: !!message.reply_to,
      channel: [channel.id, channel.name], occurred_at: message.occurred_at,
      author_fronting: wasFronting(message.authors, message.occurred_at), text: message.text,
    } });
  }
  return candidates.sort((a, b) => b.occurred_at - a.occurred_at || b.id.localeCompare(a.id));
}

export function filterFeed(candidates: FeedCandidate[], ast: Json, context: Json): FeedCandidate[] {
  const indexes = core.feedFilter(ast, candidates.map((row) => row.item), context);
  return indexes.map((index) => candidates[index]).filter((row): row is FeedCandidate => !!row);
}
