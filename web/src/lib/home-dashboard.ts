// Chorus Home (D-130, Figma F0): which sections show and in what order, who leads Quick switch, what
// waits for us, how friends' cards sort and what happened today. Pure; Android's data/HomeDashboard.kt
// is the reference and `home-dashboard.test.ts` runs the same cases. Each reader keeps their own
// sections in `home.sections:<member>`; a person (or a system with nobody reading) uses `home.sections`,
// stored as `[{id, on}]` so both clients read and write the same pref.
import { around, follows, isSystem, type FriendRow } from './friends-screens';

export interface HomeSection { id: string; on: boolean }
export interface TodayEvent { id: string; at: number; kind: 'switch' | 'post'; text: string }
/** A friend's card under *Friends right now*, most telling first (the order is the sort order). */
export type FriendCard = 'around' | 'not_yet' | 'posted' | 'quiet' | 'not_sharing';
const CARD_ORDER: FriendCard[] = ['around', 'not_yet', 'posted', 'quiet', 'not_sharing'];

export const PREF = 'home.sections';
export const READER = 'home.sections:';
export const SYSTEM = ['fronting', 'quick_switch', 'handover', 'waiting', 'friends', 'today', 'new_from_friends'];
export const PERSON = ['write', 'friends', 'waiting', 'new_from_friends'];
/** Sections waiting on a feature: Handover is D-132. They keep their place but never show. */
export const UNBUILT = new Set(['handover']);

export const SECTION_TEXT: Record<string, string> = {
  fronting: 'Fronting now', quick_switch: 'Quick switch', handover: 'Handovers and reviews', waiting: 'Waiting for you',
  friends: 'Friends right now', today: 'Today', new_from_friends: 'New from friends', write: 'Write something',
};

export const key = (reader: string | null | undefined): string => (reader ? READER + reader : PREF);

/** The stored order and switches, with unknown ids dropped and missing ones put back where the
 * default order has them (after the nearest earlier one), shown. */
export function parse(raw: unknown, person: boolean): HomeSection[] {
  const defaults = person ? PERSON : SYSTEM;
  const stored: HomeSection[] = [];
  if (Array.isArray(raw)) {
    for (const v of raw) {
      let s: HomeSection | null = null;
      if (typeof v === 'string') s = defaults.includes(v) ? { id: v, on: true } : null;
      else if (v && typeof v === 'object' && !Array.isArray(v)) {
        const o = v as Record<string, unknown>;
        const id = typeof o.id === 'string' ? o.id : '';
        s = defaults.includes(id) ? { id, on: typeof o.on === 'boolean' ? o.on : true } : null;
      }
      if (s && !stored.some((x) => x.id === s!.id)) stored.push(s);
    }
  }
  const out = [...stored];
  defaults.forEach((id, i) => {
    if (out.some((x) => x.id === id)) return;
    const before = defaults.slice(0, i).reverse().find((prev) => out.some((x) => x.id === prev));
    out.splice(before ? out.findIndex((x) => x.id === before) + 1 : 0, 0, { id, on: true });
  });
  return out;
}

/** A reader's sections, else the account's, else the defaults. */
export function forReader(byReader: Record<string, unknown>, accountWide: unknown, reader: string | null | undefined, person: boolean): HomeSection[] {
  return parse((reader ? byReader[reader] : undefined) ?? accountWide, person);
}

/** What the drawer lists: everything built. */
export const editable = (sections: HomeSection[]): HomeSection[] => sections.filter((s) => !UNBUILT.has(s.id));
/** What Home draws, in order. */
export const shown = (sections: HomeSection[]): string[] => sections.filter((s) => s.on && !UNBUILT.has(s.id)).map((s) => s.id);
export const value = (sections: HomeSection[]): { id: string; on: boolean }[] => sections.map((s) => ({ id: s.id, on: s.on }));

type PrefRows = Record<string, { exists?: boolean; fields: Record<string, unknown> } | undefined>;
/** Every `home.sections` pref this account has: the account-wide one and each reader's. A pending
 * local change (`||key`) wins over the stamped row, as everywhere. */
export function storedSections(prefs: PrefRows | undefined, accountId: string): { byReader: Record<string, unknown>; accountWide: unknown } {
  const byReader: Record<string, unknown> = {};
  let accountWide: unknown;
  const stamped: Record<string, unknown> = {};
  const pending: Record<string, unknown> = {};
  for (const [k, row] of Object.entries(prefs ?? {})) {
    const [acct, device, name] = k.split('|');
    if (device !== '' || !name || (acct !== '' && acct !== accountId) || row?.exists === false) continue;
    if (name !== PREF && !name.startsWith(READER)) continue;
    (acct === '' ? pending : stamped)[name] = row?.fields.value;
  }
  for (const [name, v] of Object.entries({ ...stamped, ...pending })) {
    if (v == null) continue;
    if (name === PREF) accountWide = v; else byReader[name.slice(READER.length)] = v;
  }
  return { byReader, accountWide };
}

/** Quick switch (F0.1): pinned members, then the recently switched in, then everyone else, as many as fit. */
export function quickSwitch(own: string[], pins: string[], recents: string[], limit: number): string[] {
  const ours = new Set(own);
  return [...new Set([...pins, ...recents, ...own].filter((id) => ours.has(id)))].slice(0, Math.max(0, limit));
}

/** *Waiting for you* (F0.1): chats with unread messages, not muted or archived, newest first. */
export function waiting<T>(rows: T[], unread: (r: T) => number, muted: (r: T) => boolean, archived: (r: T) => boolean, latest: (r: T) => number, limit = 3): T[] {
  return rows.filter((r) => unread(r) > 0 && !muted(r) && !archived(r)).sort((a, b) => latest(b) - latest(a)).slice(0, limit);
}

export function card(row: FriendRow, now: number): FriendCard {
  if (!isSystem(row.account)) return around(row, now) ? 'posted' : 'quiet';
  if (row.view?.shared === false) return 'not_sharing';
  if (row.view?.entries?.length) return 'around';
  if (row.view?.since == null) return 'not_yet';
  return 'quiet';
}

/** *Friends right now*: whom we follow, those around first (newest arrival first), then those we'll
 * see after their next switch, people who posted lately, the quiet ones and those who don't share.
 * A person who hasn't posted lately isn't "right now": left out. */
export function friends(rows: FriendRow[], now: number): { row: FriendRow; card: FriendCard }[] {
  const when = (r: FriendRow) => r.view?.since ?? r.latest?.occurred_at ?? r.following?.created_at ?? 0;
  return rows.filter((r) => follows(r)).map((row) => ({ row, card: card(row, now) }))
    .filter(({ row, card: c }) => isSystem(row.account) || c === 'posted')
    .sort((a, b) => CARD_ORDER.indexOf(a.card) - CARD_ORDER.indexOf(b.card) || when(b.row) - when(a.row));
}

const WORD: Record<string, string> = { front: 'fronting', cocon: 'co-conscious', present: 'present' };
/** "Kit started steering · Juniper co-conscious" for one of our switches: the innermost layer
 * "started" its layer word, the next layer after it; two layers at most. `layers` are (id, name), inner first. */
export function switchLine(entries: { level: string; is_primary?: boolean; name: string | undefined }[], layers: { id: string; name: string }[]): string {
  const order = layers.map((l) => l.id);
  const groups = new Map<string, typeof entries>();
  for (const e of entries) groups.set(e.level, [...(groups.get(e.level) ?? []), e]);
  const rank = (l: string) => (order.indexOf(l) < 0 ? Number.MAX_SAFE_INTEGER : order.indexOf(l));
  const parts = [...groups].sort((a, b) => rank(a[0]) - rank(b[0])).slice(0, 2).flatMap(([level, here]) => {
    const names = [...here].sort((a, b) => Number(!!b.is_primary) - Number(!!a.is_primary)).map((e) => e.name).filter((n): n is string => !!n);
    if (!names.length) return [];
    const who = names.length <= 2 ? names.join(' & ') : `${names[0]}, ${names[1]} & ${names.length - 2} more`;
    const word = layers.find((l) => l.id === level)?.name.toLowerCase() ?? WORD[level] ?? 'here';
    return [level === order[0] ? `${who} started ${word}` : `${who} ${word}`];
  });
  return parts.join(' · ') || 'No one fronting';
}

export interface SwitchIn { id: string; occurred_at: number; retracted: boolean; resulting_front: { level: string; is_primary?: boolean; name: string | undefined }[] }
export interface PostIn { id: string; occurred_at: number; reply_to?: string | null; repost_of?: string | null; title?: string | null; text: string; author: string | null }

/** *Today* (F0.2): our switches and posts since `dayStart`, newest first. Retracted switches, replies and reposts are left out. */
export function today(switches: SwitchIn[], posts: PostIn[], dayStart: number, layers: { id: string; name: string }[]): TodayEvent[] {
  const s = switches.filter((x) => !x.retracted && x.occurred_at >= dayStart)
    .map((x): TodayEvent => ({ id: x.id, at: x.occurred_at, kind: 'switch', text: switchLine(x.resulting_front, layers) }));
  const p = posts.filter((x) => x.occurred_at >= dayStart && !x.reply_to && !x.repost_of).map((post): TodayEvent => {
    const first = post.text.split('\n').find((l) => l.trim());
    const title = post.title?.trim() ? post.title : first ? (first.length > 40 ? first.slice(0, 39).trimEnd() + '…' : first) : null;
    const who = post.author ?? 'We';
    return { id: post.id, at: post.occurred_at, kind: 'post', text: title === null ? `${who} posted` : `${who} wrote “${title}”` };
  });
  return [...s, ...p].sort((a, b) => b.at - a.at || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
}

export interface FirstDay { welcome: boolean; post: boolean; profile: boolean; any: boolean }
/** A person's first day (F0.6): what is still to do. */
export function firstDay(followCount: number, ownPosts: number, self: { pronouns?: string | null; avatar_blob?: string | null; description?: string | null } | undefined): FirstDay {
  const welcome = followCount === 0;
  const post = ownPosts === 0;
  const profile = !!self && !self.pronouns?.trim() && !self.avatar_blob && !self.description?.trim();
  return { welcome, post, profile, any: welcome || post || profile };
}
