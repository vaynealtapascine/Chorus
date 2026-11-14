// Forward to… and Reply in… (Figma F5.20): where a message can go, the red card when the copy leaves
// where it was written, and the snapshot as it goes. Port of Android's data/ChatForward.kt.
import type { SnapshotItem } from './data';
import { forTarget } from './selection';

/** Chats whose names match `query`, newest first (those `first` names ahead of the rest), without the
 * ones `exclude` names. */
export function destinations<T>(items: T[], query: string, names: (t: T) => (string | null | undefined)[], latest: (t: T) => number,
  exclude: (t: T) => boolean = () => false, first: (t: T) => boolean = () => false): T[] {
  const q = query.trim().toLowerCase();
  return items.filter((t) => !exclude(t) && (!q || names(t).some((n) => !!n && n.toLowerCase().includes(q))))
    .sort((a, b) => Number(first(b)) - Number(first(a)) || latest(b) - latest(a));
}

/** A destination as the forward sheet lists it. */
export interface Place { id: string; title: string; names: (string | null | undefined)[]; disc: { glyph: string; color: string; blob?: string; icon?: string }; first?: boolean }

/** Save to notes (D-132): our Notes chat, if it is there, leads the destinations under that name,
 * with the bookmark where its picture goes; any other chat stays as it was. */
export function withSaveToNotes<T extends Place>(targets: T[], notesId: string | undefined): T[] {
  return targets.map((t) => t.id === notesId
    ? { ...t, title: 'Save to notes', names: [...t.names, 'notes'], first: true, disc: { ...t.disc, glyph: '', blob: undefined, icon: 'bookmark' } } : t);
}

/** The red card: "This leaves The Lanterns" / "The Junipers and Driftwood will see it." `audience` is who
 * else is in the target, else `fallback`. */
export function warning(source: string, audience: string[], fallback: string): { title: string; text: string } {
  const names = [...new Set(audience.filter((n) => n.trim()))];
  const who = names.length === 0 ? fallback : names.length === 1 ? names[0] : `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`;
  return { title: `This leaves ${source}`, text: `${who} will see it.` };
}

/** The snapshots as they go to another chat: in another space the source chat's name stays hidden, and
 * with "Show who originally wrote it" off no author goes at all (F5.20). */
export function snapshotsFor(items: SnapshotItem[], sourceSpaceId: string, targetSpaceId: string, showAuthors: boolean): SnapshotItem[] {
  const moved = forTarget(items, sourceSpaceId, targetSpaceId);
  return showAuthors ? moved : moved.map((item) => ({ ...item, authors: [] }));
}
