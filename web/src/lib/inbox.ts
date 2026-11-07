// The Notifications page (D-127, Figma F11): what `GET /notifications` delivered, what other
// accounts did with our posts (`GET /posts/activity`), and what waits on us (follow and alert
// requests, front reviews). Nothing here is new information: each source already reached this
// account. Read marks are per reader (a member; a person's own member) in the account pref
// `inbox.read:<member>` = `{until, ids}`; what shows is `inbox.shows`. Android's `Inbox.kt` agrees.

export interface InboxItem {
  id: string;
  kind: string;
  /** `mentions` · `friends` · `system` · `app`: the chips. */
  category: 'mentions' | 'friends' | 'system' | 'app';
  /** Which "What shows here" switch governs it; null: always shown. */
  shows: string | null;
  at: number;
  /** Who did it (bold at the start of `title` when it starts with it). */
  actor: string | null;
  title: string;
  detail?: string | null;
  quote?: string | null;
  color?: string | null;
  avatarBlob?: string | null;
  postId?: string | null;
  channelId?: string | null;
  messageId?: string | null;
  accountId?: string | null;
  memberId?: string | null;
  followId?: string | null;
  needsYou?: boolean;
}

export interface InboxRead { until: number; ids: string[] }

export const READ = 'inbox.read:';
export const SHOWS_PREF = 'inbox.shows';
export const MAX_IDS = 200;

/** "What shows here": key and label, on unless turned off (own switches start off). */
export const SHOWS: [string, string][] = [['mentions', 'Mentions and replies'], ['friend_switches', 'Friends’ switches'],
  ['posts', 'New posts you asked for'], ['likes', 'Likes, quotes and reposts'], ['own_switches', 'Our own switches']];

export function shows(pref: Record<string, unknown> | null | undefined, key: string): boolean {
  const v = pref?.[key];
  return typeof v === 'boolean' ? v : key !== 'own_switches';
}

export function chips(person: boolean): [string, string][] {
  return [['all', 'All'], ['mentions', 'Mentions'], ['friends', 'Friends'], ...(person ? [] : [['system', 'Our system'] as [string, string]]), ['app', 'App']];
}

const text = (o: Record<string, unknown> | null | undefined, k: string): string | null => {
  const v = o?.[k];
  return typeof v === 'string' && v.trim() ? v : null;
};
type Json = Record<string, unknown>;

/** `GET /notifications` items as rows; unknown kinds are skipped. */
export function fromNotifications(items: unknown[] | null | undefined): InboxItem[] {
  const out: InboxItem[] = [];
  for (const raw of items ?? []) {
    const n = raw as Json;
    const id = text(n, 'id');
    if (!id) continue;
    const kind = String(n.kind ?? '');
    const at = Number(n.delivered_at ?? (n.time as Json | undefined)?.at ?? 0);
    const sender = n.sender as Json | undefined;
    const who = text(sender, 'name');
    const title = text(n, 'title') ?? 'Chorus';
    const body = text(n, 'text');
    const look = { color: text(sender, 'color'), avatarBlob: text(sender, 'avatar_blob') };
    const chat = (verb: string): InboxItem => ({ id, kind, category: 'mentions', shows: 'mentions', at, actor: who,
      title: who ? `${who} ${verb}` : title, detail: who && title !== who ? title : null, quote: body, ...look,
      channelId: text(n, 'channel_id'), messageId: text(n, 'message_id') });
    switch (kind) {
      case 'mention': out.push(chat('mentioned you')); break;
      case 'reply': out.push(chat('replied to you')); break;
      case 'dm': out.push(chat('sent you a message')); break;
      case 'message': out.push(chat('wrote')); break;
      case 'member_dm': out.push({ ...chat('wrote in a member chat'), category: 'system' }); break;
      case 'post_reply': out.push({ id, kind, category: 'mentions', shows: 'mentions', at, actor: who, title, quote: body, ...look,
        postId: text(n, 'post_id'), accountId: text(n, 'account_id') }); break;
      case 'post': out.push({ id, kind, category: 'friends', shows: 'posts', at, actor: who, title, detail: body, ...look,
        postId: text(n, 'post_id'), accountId: text(n, 'account_id') }); break;
      case 'switch': {
        const account = n.account as Json | undefined;
        const name = text(account, 'display_name') ?? (text(account, 'handle') ? `@${text(account, 'handle')}` : 'A friend');
        out.push({ id, kind, category: 'friends', shows: 'friend_switches', at, actor: name, title: `${name}: ${body ?? 'switched'}`,
          accountId: text(account, 'id') });
        break;
      }
      case 'own_switch': out.push({ id, kind, category: 'system', shows: 'own_switches', at, actor: null, title, detail: body }); break;
      case 'alert_request': case 'alert_accepted':
        out.push({ id, kind, category: 'friends', shows: null, at, actor: null, title, detail: body,
          memberId: text(n, 'member_id'), accountId: text(n, 'account_id') });
        break;
      case 'export_ready': out.push({ id, kind, category: 'app', shows: null, at, actor: null, title, detail: body }); break;
    }
  }
  return out;
}

export interface ActivityTarget { id: string; kind?: string | null; title?: string | null; text: string; cw?: string | null }
export interface ActivityItem {
  kind: 'reply' | 'quote' | 'repost' | 'reaction';
  at: number;
  post?: Json & { id: string; text: string; occurred_at: number; account_id?: string; author_cards?: Json[] };
  emoji?: string; member_id?: string; member_name?: string; account_id?: string;
  target?: ActivityTarget | null;
}
export interface JournalActivity {
  items: ActivityItem[];
  reply_counts: Record<string, number>;
  reactions: Record<string, { emoji: string; member_id: string; member_name: string }[]>;
}

function about(t: ActivityTarget | null | undefined): string {
  const s = t ? t.title ?? (t.cw ? `CW: ${t.cw}` : t.text) : '';
  if (!s) return 'your post';
  return `“${s.length > 60 ? s.slice(0, 60).replace(/\s+\S*$/, '').replace(/[.,\s]+$/, '') + '…' : s}”`;
}

const names = (post: ActivityItem['post']) =>
  (post?.author_cards ?? []).map((a) => text(a, 'display_name') ?? text(a, 'name')).filter(Boolean).join(' & ') || 'Someone';

/** Hearts, quotes and reposts of our posts by other accounts (replies arrive as notifications).
 * One row per post and emoji: "Kit and Juniper liked “A quiet afternoon”". */
export function fromActivity(activity: JournalActivity | null | undefined): InboxItem[] {
  if (!activity) return [];
  const out: InboxItem[] = [];
  const groups = new Map<string, ActivityItem[]>();
  for (const a of activity.items) if (a.kind === 'reaction') {
    const key = `${a.target?.id}:${a.emoji}`;
    groups.set(key, [...(groups.get(key) ?? []), a]);
  }
  for (const [key, group] of groups) {
    const who = [...new Set(group.map((g) => g.member_name).filter((n): n is string => !!n))];
    const label = who.length === 0 ? 'Someone' : who.length === 1 ? who[0] : who.length === 2 ? `${who[0]} and ${who[1]}` : `${who[0]} and ${who.length - 1} others`;
    const first = group[0];
    const verb = first.emoji === '💜' ? 'liked' : `reacted ${first.emoji} to`;
    out.push({ id: `react:${key}:${group.map((g) => g.member_id).sort().join(',')}`, kind: 'reaction', category: 'friends', shows: 'likes',
      at: Math.max(...group.map((g) => g.at)), actor: label, title: `${label} ${verb} ${about(first.target)}`, postId: first.target?.id });
  }
  for (const a of activity.items) {
    if ((a.kind !== 'quote' && a.kind !== 'repost') || !a.post) continue;
    const who = names(a.post);
    const card = a.post.author_cards?.[0];
    out.push({ id: `${a.kind}:${a.post.id}`, kind: a.kind, category: 'friends', shows: 'likes', at: a.at, actor: who,
      title: `${who} ${a.kind === 'quote' ? 'quoted' : 'reposted'} ${about(a.target)}`,
      quote: a.kind === 'quote' && a.post.text ? a.post.text : null, color: text(card, 'color'), avatarBlob: text(card, 'avatar_blob'),
      postId: a.kind === 'quote' ? a.post.id : a.target?.id, accountId: a.post.account_id ?? null });
  }
  return out;
}

export interface FollowRow { id: string; status: string; account: { id: string; handle: string | null; display_name: string | null } }

/** What waits on us: follow requests, alert requests and front reviews. Always unread. */
export function needsYou(followers: FollowRow[], alerts: Json[], reviews: { id: string }[], person: boolean,
  memberName: (id: string) => string | undefined, now: number): InboxItem[] {
  const out: InboxItem[] = [];
  for (const f of followers.filter((f) => f.status === 'requested')) {
    const who = f.account.display_name || (f.account.handle ? `@${f.account.handle}` : 'Someone');
    out.push({ id: `follow:${f.id}`, kind: 'follow_request', category: 'friends', shows: null, at: now, actor: who,
      title: `${who} wants to follow ${person ? 'you' : 'your system'}`, accountId: f.account.id, followId: f.id, needsYou: true });
  }
  for (const a of alerts.filter((a) => a.status === 'requested')) {
    const account = a.account as Json | undefined;
    const who = text(account, 'display_name') ?? (text(account, 'handle') ? `@${text(account, 'handle')}` : 'Someone');
    const member = String(a.member_id ?? '');
    out.push({ id: `alert:${member}:${text(account, 'id')}`, kind: 'alert_ask', category: 'friends', shows: null,
      at: Number(a.created_at ?? now), actor: who, title: `${who} wants to hear when ${memberName(member) ?? 'a member'} posts`,
      memberId: member, accountId: text(account, 'id'), needsYou: true });
  }
  for (const r of reviews) out.push({ id: `review:${r.id}`, kind: 'review', category: 'system', shows: null, at: now, actor: null,
    title: 'Two devices switched within a minute', detail: 'Check which one is right', needsYou: true });
  return out;
}

export function isRead(read: InboxRead, item: InboxItem): boolean {
  return !item.needsYou && (item.at <= read.until || read.ids.includes(item.id));
}

/** Mark one read; old single marks fall away. */
export function mark(read: InboxRead, item: InboxItem): InboxRead {
  return isRead(read, item) ? read : { until: read.until, ids: [...read.ids, item.id].slice(-MAX_IDS) };
}

/** Mark one unread again: if `until` covers it, `until` moves back before it and every other
 * item it passed keeps its mark one by one. */
export function unmark(read: InboxRead, item: InboxItem, all: InboxItem[]): InboxRead {
  if (item.at > read.until) return { until: read.until, ids: read.ids.filter((id) => id !== item.id) };
  const keep = all.filter((i) => i.at >= item.at && i.at <= read.until && i.id !== item.id).map((i) => i.id);
  return { until: item.at - 1, ids: [...new Set([...read.ids.filter((id) => id !== item.id), ...keep])].slice(-MAX_IDS) };
}

export function parseRead(value: unknown): InboxRead {
  if (!value || typeof value !== 'object') return { until: 0, ids: [] };
  const v = value as Json;
  return { until: Number(v.until) || 0, ids: Array.isArray(v.ids) ? v.ids.filter((x): x is string => typeof x === 'string' && !!x) : [] };
}

/** Rows to show for one chip: what needs us first, then newest first; what "What shows here" hides left out. */
export function visible(items: InboxItem[], chip: string, pref: Record<string, unknown> | null | undefined): InboxItem[] {
  return items.filter((i) => (chip === 'all' || i.category === chip) && (i.shows === null || shows(pref, i.shows)))
    .sort((a, b) => Number(!!b.needsYou) - Number(!!a.needsYou) || b.at - a.at || a.id.localeCompare(b.id));
}

export function unread(items: InboxItem[], read: InboxRead, pref: Record<string, unknown> | null | undefined, chip = 'all'): number {
  return visible(items, chip, pref).filter((i) => !isRead(read, i)).length;
}

export function section(item: InboxItem, startOfToday: number): 'Needs you' | 'Today' | 'Earlier' {
  return item.needsYou ? 'Needs you' : item.at >= startOfToday ? 'Today' : 'Earlier';
}

export const readPayload = (member: string, read: InboxRead) => ({ device: '', key: READ + member, value: read });
export const showsPayload = (current: Record<string, unknown> | null | undefined, key: string, on: boolean) =>
  ({ device: '', key: SHOWS_PREF, value: { ...(current ?? {}), [key]: on } });

type PrefRows = Record<string, { fields: Record<string, unknown> } | undefined>;

/** Account-wide prefs under a prefix (read marks by member), a pending local change winning. */
export function readMarks(prefs: PrefRows | undefined, accountId: string): Map<string, InboxRead> {
  const stamped = new Map<string, unknown>();
  const pending = new Map<string, unknown>();
  for (const [k, row] of Object.entries(prefs ?? {})) {
    const [account, device, key] = k.split('|');
    if (device || (account && account !== accountId) || !key?.startsWith(READ) || !row) continue;
    (account ? stamped : pending).set(key.slice(READ.length), row.fields.value);
  }
  return new Map([...new Map([...stamped, ...pending])].map(([member, v]) => [member, parseRead(v)]));
}

export function showsPref(prefs: PrefRows | undefined, accountId: string): Record<string, unknown> | null {
  const v = (prefs?.[`||${SHOWS_PREF}`] ?? prefs?.[`${accountId}||${SHOWS_PREF}`])?.fields.value;
  return v && typeof v === 'object' ? v as Record<string, unknown> : null;
}
