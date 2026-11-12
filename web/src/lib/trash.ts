// Trash's rows as the page shows them (Figma F9.5; Android data/Trash.kt): tabs, "This week" and
// "Earlier", and the line under a row.
import { ago } from './ago';
import type { TrashItem, TrashKind } from './data';

export type TrashTab = 'all' | 'messages' | 'posts' | 'members';
const DAY = 86_400_000;

/** The tab a row shows under besides All: channels sit with messages, groups with members. */
export const tabOf = (kind: TrashKind): Exclude<TrashTab, 'all'> =>
  kind === 'message' || kind === 'channel' ? 'messages' : kind === 'post' ? 'posts' : 'members';

/** "Deleted by Nairon · 2 days ago", "Deleted 12 days ago · history kept" for a member. */
export function trashDetail(item: TrashItem, now: number): string {
  const when = ago(item.deletedAt, now);
  const base = item.deletedBy ? `Deleted by ${item.deletedBy} · ${when}` : `Deleted ${when}`;
  return item.kind === 'member' ? `${base} · history kept` : base;
}

export function trashSections(items: TrashItem[], now: number): { label: string; items: TrashItem[] }[] {
  const week = items.filter((i) => now - i.deletedAt < 7 * DAY);
  const earlier = items.filter((i) => now - i.deletedAt >= 7 * DAY);
  return [{ label: 'This week', items: week }, { label: 'Earlier', items: earlier }].filter((s) => s.items.length);
}

export function trashFilter(items: TrashItem[], tab: TrashTab, query: string): TrashItem[] {
  const needle = query.trim().toLowerCase();
  return items.filter((i) => (tab === 'all' || tabOf(i.kind) === tab)
    && (!needle || i.title.toLowerCase().includes(needle) || (i.deletedBy ?? '').toLowerCase().includes(needle)));
}
