// The desktop chat list (Figma F5.21): one list of every conversation across spaces, newest
// activity first, split into folders like Android's chat list. Display only: reads stay in data.ts.
import type { ChannelRow, MessageRow, SpaceRow } from './data';
import type { Interval } from './sync/client';

export type Folder = 'all' | 'internal' | 'shared' | 'direct';
export const FOLDERS: { id: Folder; label: string; icon: string }[] = [
  { id: 'all', label: 'All chats', icon: 'chat' },
  { id: 'internal', label: 'Internal', icon: 'lock' },
  { id: 'shared', label: 'Shared', icon: 'globe' },
  { id: 'direct', label: 'Direct', icon: 'users' },
];

/** Member DMs and account DMs are Direct; the system's own space is Internal; the rest Shared. */
export function folderOf(channel: ChannelRow, space: SpaceRow | undefined): Exclude<Folder, 'all'> {
  if (channel.kind === 'member_dm' || space?.kind === 'dm') return 'direct';
  return space?.kind === 'internal' ? 'internal' : 'shared';
}

/** "10:41" today, "Tue" this week, then a short date. */
export function chatTime(at: number, now: number = Date.now(), locale?: string): string {
  const day = (t: number) => { const d = new Date(t); d.setHours(0, 0, 0, 0); return d.getTime(); };
  const days = Math.round((day(now) - day(at)) / 86_400_000);
  if (days <= 0) return new Intl.DateTimeFormat(locale, { hour: '2-digit', minute: '2-digit' }).format(at);
  if (days < 7) return new Intl.DateTimeFormat(locale, { weekday: 'short' }).format(at);
  return new Intl.DateTimeFormat(locale, { month: 'short', day: 'numeric' }).format(at);
}

/** "Today", "Yesterday", a weekday this week, then a date: the pill between days of messages. */
export function dayLabel(at: number, now: number = Date.now(), locale?: string): string {
  const day = (t: number) => { const d = new Date(t); d.setHours(0, 0, 0, 0); return d.getTime(); };
  const days = Math.round((day(now) - day(at)) / 86_400_000);
  if (days <= 0) return 'Today';
  if (days === 1) return 'Yesterday';
  if (days < 7) return new Intl.DateTimeFormat(locale, { weekday: 'long' }).format(at);
  const sameYear = new Date(at).getFullYear() === new Date(now).getFullYear();
  return new Intl.DateTimeFormat(locale, { month: 'long', day: 'numeric', ...(sameYear ? {} : { year: 'numeric' }) }).format(at);
}

/** The second line of a row: who wrote last (when it isn't the only voice there) and what. */
export function previewOf(m: MessageRow | undefined, nameOf: (id: string) => string, showWho = true): { who: string | null; text: string } {
  if (!m) return { who: null, text: 'No messages yet' };
  // an author this device can't name (another system's member) is left out rather than "Someone"
  const names = m.authors.map(nameOf).filter(Boolean);
  const who = showWho && names.length ? names.join(' & ') : null;
  const text = m.cw ? `Content warning: ${m.cw}`
    : m.forward_snapshot?.length ? `Forwarded · ${m.forward_snapshot[0].text}`
    : m.text.trim() ? hideSpoilers(m.text, m.entities)
    : m.attachments.length ? (m.attachments.every((a) => a.mime.startsWith('image/')) ? (m.attachments.length > 1 ? `${m.attachments.length} photos` : 'Photo') : `File · ${m.attachments[0].filename}`)
    : 'Message';
  return { who, text: text.replace(/\s+/g, ' ') };
}

/** Spoiler ranges become dots, so a preview never gives one away (UTF-16 offsets, like entities). */
export function hideSpoilers(text: string, entities: { type: string; offset: number; length: number }[]): string {
  let out = text;
  for (const e of entities) {
    if (e.type !== 'spoiler') continue;
    const hidden = out.slice(e.offset, e.offset + e.length).replace(/\S/g, '•');
    out = out.slice(0, e.offset) + hidden + out.slice(e.offset + e.length);
  }
  return out;
}

/** A steady colour for a channel's disc, from the member palette. */
const DISCS = ['#B99CE8', '#F08C7F', '#EEAA62', '#DCA3CC', '#8FB8E8', '#86BFA5'];
export function discColor(id: string): string {
  let h = 0;
  for (let i = 0; i < id.length; i++) h = (h * 31 + id.charCodeAt(i)) >>> 0;
  return DISCS[h % DISCS.length];
}

/** Newest activity first; quiet chats after, by title. */
export function byActivity<T extends { at: number; title: string }>(rows: T[]): T[] {
  return [...rows].sort((a, b) => b.at - a.at || a.title.localeCompare(b.title));
}

export interface FrontEvent { at: number; text: string }

/** "Foxglove started fronting · 10:30" lines for an internal chat: switches that began inside
 * the stretch of time the shown messages cover. Starts in the same minute and layer read as one. */
export function frontEvents(intervals: Interval[], from: number, to: number, nameOf: (type: string, id: string) => string | undefined,
  layerName: (level: string) => string | undefined): FrontEvent[] {
  const groups = new Map<string, { at: number; level: string; names: string[] }>();
  for (const iv of intervals) {
    if (iv.start_at <= from || iv.start_at > to) continue;
    const name = nameOf(iv.subject_type, iv.subject_id);
    if (!name) continue;
    // one switch can stamp its subjects a few ms apart: a minute is one moment here
    const key = `${Math.floor(iv.start_at / 60_000)}|${iv.level}`;
    const g = groups.get(key) ?? { at: iv.start_at, level: iv.level, names: [] };
    g.names.push(name);
    groups.set(key, g);
  }
  return [...groups.values()].sort((a, b) => a.at - b.at).map((g) => {
    const layer = (layerName(g.level) ?? g.level).toLowerCase();
    const who = g.names.length > 2 ? `${g.names.slice(0, -1).join(', ')} & ${g.names.at(-1)}` : g.names.join(' & ');
    const verb = layer === 'co-conscious' ? 'became co-conscious' : layer === 'present' ? 'came near'
      : layer.endsWith('ing') ? `started ${layer}` : `joined ${layer}`;
    return { at: g.at, text: `${who} ${verb}` };
  });
}
