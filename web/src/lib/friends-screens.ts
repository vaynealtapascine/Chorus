// The Friends screens' rules and words (UI20, Figma F7, D-128): who is "around right now", how a
// friend's shared time reads, follow prefs (NOTIFICATIONS §4) edited one control at a time, and the
// ceiling an accepted request starts with. Pure; Android's data/Friends.kt follows the same rules.

export interface Person { id: string; handle: string | null; display_name: string | null; kind: string; avatar_blob?: string | null }
export interface FollowRow { id: string; account: Person; status: string; created_at: number; prefs?: Record<string, unknown> }
export interface ViewEntry { t?: string; name: string; level: string; color?: string | null; glyph?: string | null; avatar_blob?: string | null }
export interface FollowerView {
  entries: ViewEntry[];
  since: number | null;
  revealed_at?: number | null;
  time?: { mode?: string; round_min?: number; jitter_min?: number } | null;
  shared?: boolean;
  /** additional fronts they granted us (D-084), as revealed */
  fronts?: { id: string; name: string | null; entries: ViewEntry[]; since: number | null }[];
  history?: { entries: { name: string }[]; time: { at: number | null; precision: string; part?: string | null } }[];
  stats?: { days: number; members: { name: string; share_pct: number }[] };
}
export interface LatestPost { occurred_at: number; kind?: string | null; reply_to?: string | null; attachments?: { mime?: string | null }[] }

/** One account on the Friends screens: whom we follow, who follows us, what they share. */
export interface FriendRow { account: Person; following: FollowRow | null; follower: FollowRow | null; view: FollowerView | null; latest: LatestPost | null }

export const personName = (p: Pick<Person, 'display_name' | 'handle'>): string => p.display_name ?? (p.handle ? `@${p.handle}` : 'Someone');
export const isSystem = (p: Pick<Person, 'kind'>): boolean => p.kind === 'system';
export const follows = (r: FriendRow): boolean => r.following?.status === 'active';
export const waiting = (r: FriendRow): boolean => r.following?.status === 'requested';
/** Friends follow each other (D-129); one way is a follow, not a friendship. */
export const mutual = (r: FriendRow): boolean => follows(r) && r.follower?.status === 'active';
/** Since when we've been friends: the newer of the two follows. */
export function connectedSince(r: FriendRow): number {
  return mutual(r) ? Math.max(r.following!.created_at, r.follower!.created_at) : 0;
}

/** Everyone we follow (in the order we followed them), then those who only follow us. */
export function friendRows(following: FollowRow[], followers: FollowRow[], views: Record<string, FollowerView>,
  latest: Record<string, LatestPost | null>): FriendRow[] {
  const out = following.filter((f) => f.status === 'active' || f.status === 'requested');
  const back = followers.filter((f) => f.status === 'active');
  const ids = [...new Set([...out.map((f) => f.account.id), ...back.map((f) => f.account.id)])];
  return ids.map((id) => {
    const o = out.find((f) => f.account.id === id) ?? null;
    const b = back.find((f) => f.account.id === id) ?? null;
    return { account: (o ?? b)!.account, following: o, follower: b, view: views[id] ?? null, latest: latest[id] ?? null };
  });
}

export const AROUND_MS = 24 * 60 * 60 * 1000;
/** Around right now (F7.1): a system that shows someone here, a person who posted lately. */
export function around(r: FriendRow, now: number): boolean {
  if (!follows(r)) return false;
  if (isSystem(r.account)) return !!r.view?.entries?.length;
  return !!r.latest && now - r.latest.occurred_at < AROUND_MS;
}

export function levelWord(level: string): string {
  return ({ front: 'fronting', cocon: 'co-conscious', present: 'present' } as Record<string, string>)[level] ?? 'here';
}

/** "Kit fronting · Juniper co-conscious", front first, at most `max` names. */
export function nowLine(entries: ViewEntry[], max = 3): string {
  const order = ['front', 'cocon', 'present'];
  const rank = (l: string) => (order.indexOf(l) < 0 ? order.length : order.indexOf(l));
  const sorted = [...entries].sort((a, b) => rank(a.level) - rank(b.level));
  const shown = sorted.slice(0, max).map((e) => `${e.name} ${levelWord(e.level)}`).join(' · ');
  return sorted.length > max ? `${shown} · ${sorted.length - max} more` : shown;
}

/** The part of day core uses (notify.rs `part_of`), in this browser's time. */
export function partOfDay(at: number): string {
  const h = new Date(at).getHours();
  return h >= 5 && h <= 11 ? 'morning' : h >= 12 && h <= 16 ? 'afternoon' : h >= 17 && h <= 21 ? 'evening' : 'night';
}
function dayOffset(at: number, now: number): number {
  const day = (t: number) => { const d = new Date(t); d.setHours(12, 0, 0, 0); return d.getTime(); };
  return Math.round((day(now) - day(at)) / 86_400_000);
}
const clock = (t: number) => new Date(t).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });

/** "since about 10:15", "since this morning", "since 10:15": never more precise than they share. */
export function sinceLine(view: FollowerView | null, now: number, fmt: (t: number) => string = clock): string | null {
  const at = view?.since;
  if (at == null) return null;
  const mode = view?.time?.mode ?? '';
  const days = dayOffset(at, now);
  const day = days === 0 ? '' : days === 1 ? 'yesterday ' : `${new Date(at).toLocaleDateString([], { weekday: 'short' })} `;
  if (mode === 'hidden') return null;
  if (mode === 'part_of_day') {
    const part = partOfDay(at);
    if (days === 0) return part === 'night' ? 'since last night' : `since this ${part}`;
    return `since ${day}${part}`.trimEnd();
  }
  if (mode === 'exact') return `since ${day}${fmt(at)}`;
  return `since about ${day}${fmt(at)}`;
}

/** How precise their times are (F7.2): "The Junipers round times to 15 minutes." */
export function timeNote(name: string, time: FollowerView['time']): string | null {
  switch (time?.mode) {
    case 'exact': return `${name} share exact times.`;
    case 'round': return `${name} round times to ${time.round_min ?? 15} minutes.`;
    case 'jitter': return `${name} nudge times by up to ${time.jitter_min ?? 15} minutes.`;
    case 'part_of_day': return `${name} share only the part of the day.`;
    case 'hidden': return `${name} don't share times.`;
    default: return null;
  }
}

/** "friends since March" (this year), "friends since March 2025", "friends since today". */
export function friendsSince(at: number, now: number): string | null {
  if (at <= 0) return null;
  if (dayOffset(at, now) === 0) return 'friends since today';
  const d = new Date(at);
  const sameYear = d.getFullYear() === new Date(now).getFullYear();
  return `friends since ${d.toLocaleDateString([], sameYear ? { month: 'long' } : { month: 'long', year: 'numeric' })}`;
}

/** What a person did last (F7.1, F7.4). */
export function activity(post: LatestPost, ago: string): string {
  if (post.kind === 'entry') return `Wrote a journal entry · ${ago}`;
  if (post.reply_to) return `Replied to a post · ${ago}`;
  if (post.attachments?.some((a) => a.mime?.startsWith('image/'))) return `Posted a photo · ${ago}`;
  return `Posted · ${ago}`;
}

// ─── follow prefs (NOTIFICATIONS §4), the follower's own ────────────────────────────────────

export type Prefs = Record<string, unknown>;
export const DELIVERIES = ['each', 'digest_hourly', 'digest_daily'] as const;
export const CLOCK = /^([01][0-9]|2[0-3]):[0-5][0-9]$/;
export const prefEnabled = (p: Prefs): boolean => p.enabled !== false;
export const switchOuts = (p: Prefs): boolean => p.switch_outs === true;
export const delivery = (p: Prefs): string => ((DELIVERIES as readonly string[]).includes(p.delivery as string) ? (p.delivery as string) : 'each');
export const digestTime = (p: Prefs): string => (typeof p.digest_time === 'string' && CLOCK.test(p.digest_time) ? p.digest_time : '20:00');
export const prefLevels = (p: Prefs): Set<string> =>
  new Set(Array.isArray(p.levels) ? (p.levels as unknown[]).filter((x): x is string => typeof x === 'string') : ['front']);
/** Who they hear about: null for everyone, else the member ids picked. */
export function subjects(p: Prefs): Set<string> | null {
  const s = p.subjects as { mode?: string; ids?: unknown[] } | undefined;
  return s?.mode === 'only' ? new Set((s.ids ?? []).filter((x): x is string => typeof x === 'string')) : null;
}
/** Tap one layer chip: at least one stays on. */
export function withLevel(p: Prefs, level: string): Prefs {
  const now = prefLevels(p);
  if (now.has(level)) now.delete(level); else now.add(level);
  if (!now.size) return p;
  return { ...p, levels: ['front', 'cocon', 'present'].filter((l) => now.has(l)) };
}
/** Tap one member chip: from everyone keeps all but that one; all picked again is everyone, so new members count too. */
export function withSubject(p: Prefs, id: string, all: string[]): Prefs {
  const now = subjects(p) ?? new Set(all);
  if (now.has(id)) now.delete(id); else now.add(id);
  if (!now.size) return p;
  if (all.every((x) => now.has(x))) return { ...p, subjects: { mode: 'all' } };
  return { ...p, subjects: { mode: 'only', ids: [...all.filter((x) => now.has(x)), ...[...now].filter((x) => !all.includes(x)).sort()] } };
}
export function quiet(p: Prefs): [string, string] | null {
  const q = p.quiet_hours as { from?: string; to?: string } | null | undefined;
  return q && CLOCK.test(q.from ?? '') && CLOCK.test(q.to ?? '') ? [q.from!, q.to!] : null;
}
export const quietHold = (p: Prefs): boolean => p.quiet_behaviour !== 'drop';
export function withQuiet(p: Prefs, hours: [string, string] | null, hold = quietHold(p)): Prefs {
  return { ...p, quiet_hours: hours ? { from: hours[0], to: hours[1] } : null, quiet_behaviour: hold ? 'hold' : 'drop' };
}
export function mutedUntil(p: Prefs, now: number): number | null {
  return typeof p.mute_until === 'number' && p.mute_until > now ? p.mute_until : null;
}
/** Prefs as saved: this browser's UTC offset rides along so their quiet hours are ours (§4). */
export const forSaving = (p: Prefs, tzOffsetMin = -new Date().getTimezoneOffset()): Prefs => ({ ...p, tz_offset_min: tzOffsetMin });

/** "Kit and Juniper · each switch", "Off", "Muted for now" (F7.2 "Notifications from them"). */
export function prefsSummary(p: Prefs, names: Map<string, string>, now: number): string {
  if (!prefEnabled(p)) return 'Off';
  if (mutedUntil(p, now) != null) return 'Muted for now';
  const picked = subjects(p);
  const list = picked ? [...picked].map((id) => names.get(id)).filter((x): x is string => !!x) : null;
  const who = !list ? 'Everyone' : list.length === 0 ? 'Some members' : list.length === 1 ? list[0]
    : list.length === 2 ? `${list[0]} and ${list[1]}` : `${list[0]}, ${list[1]} and ${list.length - 2} more`;
  const how = delivery(p) === 'digest_hourly' ? 'hourly' : delivery(p) === 'digest_daily' ? `daily at ${digestTime(p)}` : 'each switch';
  return `${who} · ${how}`;
}

// ─── sharing (our side) ───────────────────────────────────────────────────────────────────────

export const PRESET_IDS = ['close', 'gentle', 'private', 'digest', 'off'] as const;
export function presetName(choice: string): string {
  return ({ close: 'Close', gentle: 'Gentle', private: 'Private', digest: 'Digest', off: 'Off', inherit: 'From their buckets' } as Record<string, string>)[choice] ?? 'Custom';
}
export function presetAbout(choice: string): string {
  return ({
    close: 'Right away, with the exact time. Switch-outs too.',
    gentle: '5–20 min later, rounded to 15 min.',
    private: '30–90 min later, only “this evening”; co-conscious hidden.',
    digest: 'One summary a day.',
    off: 'Your profile only, no switch pings.',
  } as Record<string, string>)[choice] ?? 'Set detail by detail.';
}

/** What everyone else hears (the account default): an empty one is core's default, Gentle. */
export function everyoneElse(accountDefault: Record<string, unknown>, presetOf: (c: Record<string, unknown>) => string): string {
  const c = presetOf(accountDefault);
  return c === 'inherit' ? 'gentle' : c;
}

/**
 * The override an accepted request gets (F7.7): none when the chosen preset is what they'd get
 * anyway (their bucket, else everyone else), so the bucket stays in charge whenever it can.
 */
export function acceptCeiling(preset: string, bucketCeiling: Record<string, unknown> | null, accountDefault: Record<string, unknown>,
  presetOf: (c: Record<string, unknown>) => string, ceiling: (p: string) => Record<string, unknown>): Record<string, unknown> | null {
  const fromBucket = bucketCeiling ? presetOf(bucketCeiling) : 'inherit';
  const inherited = fromBucket !== 'inherit' ? fromBucket : everyoneElse(accountDefault, presetOf);
  return preset === inherited ? null : ceiling(preset);
}

/** Whether a follower in `buckets` hears when a member with this `notify_policy` fronts (§2.4). */
export function announcedTo(policy: unknown, buckets: Set<string>): boolean {
  let p = policy;
  if (typeof p === 'string') { try { p = JSON.parse(p); } catch { p = null; } }
  const announce = (p as { announce?: unknown } | null)?.announce;
  if (announce === 'nobody') return false;
  if (announce && typeof announce === 'object') {
    const ids = (announce as { buckets?: unknown[] }).buckets ?? [];
    return ids.some((b) => typeof b === 'string' && buckets.has(b));
  }
  return true;
}

/** "Everyone", "Everyone except Violet", "Kit and Juniper", "Nobody" (F7.9). */
export function hearsAbout(members: [string, boolean][]): string {
  const heard = members.filter(([, on]) => on).map(([n]) => n);
  const not = members.filter(([, on]) => !on).map(([n]) => n);
  const list = (n: string[]) => (n.length === 1 ? n[0] : n.length === 2 ? `${n[0]} and ${n[1]}` : `${n[0]}, ${n[1]} and ${n.length - 2} more`);
  if (!not.length) return 'Everyone';
  if (!heard.length) return 'Nobody';
  return not.length <= heard.length ? `Everyone except ${list(not)}` : list(heard);
}

/** A ceiling detail's value as a row shows it (F7.10): "0 min", "5–20 min", "Exact", "Collapse". */
export function fieldValue(key: string, value: unknown): string {
  const min = (s: number) => (s % 60 === 0 ? `${s / 60}` : (s / 60).toFixed(1));
  if (value == null) return key === 'quiet_hours' || key === 'max_per_day' ? 'None' : 'Off';
  if (typeof value === 'boolean') return value ? 'On' : 'Off';
  if (key === 'levels' && Array.isArray(value)) {
    return value.map((l) => ({ front: 'Front', cocon: 'Co-conscious', present: 'Present' } as Record<string, string>)[l as string] ?? String(l)).join(', ') || 'None';
  }
  if (key === 'delay' || key === 'extra_delay') {
    const r = value as { min_s?: number; max_s?: number };
    const a = r.min_s ?? 0;
    const b = r.max_s ?? 0;
    return a === b ? `${min(a)} min` : `${min(a)}–${min(b)} min`;
  }
  if (key === 'time') {
    const t = value as { mode?: string; round_min?: number; jitter_min?: number };
    return ({ exact: 'Exact', round: `${t.round_min ?? 15} min`, jitter: `±${t.jitter_min ?? 15} min`, part_of_day: 'Part of day', hidden: 'Hidden' } as Record<string, string>)[t.mode ?? ''] ?? 'Custom';
  }
  if (key === 'stacking') return value === 'sequence' ? 'Each one' : 'Collapse';
  if (key === 'hidden_subjects') return value === 'someone' ? 'As “someone”' : 'Left out';
  if (key === 'quiet_hours') { const q = value as { from?: string; to?: string }; return `${q.from}–${q.to}`; }
  if (key === 'max_per_day') return `${value} a day`;
  return String(value);
}

/** An account's colour where it has none of its own, by id (Android `accountColor`). */
export function accountColor(id: string, swatches: readonly string[]): string {
  let h = 0;
  for (let i = 0; i < id.length; i++) h = (Math.imul(31, h) + id.charCodeAt(i)) | 0;
  return swatches[((h % swatches.length) + swatches.length) % swatches.length];
}
