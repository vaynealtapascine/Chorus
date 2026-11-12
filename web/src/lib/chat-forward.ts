// Forward to… and Reply in… (Figma F5.20): where a message can go, the red card when the copy leaves
// where it was written, and the snapshot as it goes. Port of Android's data/ChatForward.kt.
import type { SnapshotItem } from './data';
import { forTarget } from './selection';

/** Chats whose names match `query`, newest first, without the ones `exclude` names. */
export function destinations<T>(items: T[], query: string, names: (t: T) => (string | null | undefined)[], latest: (t: T) => number,
  exclude: (t: T) => boolean = () => false): T[] {
  const q = query.trim().toLowerCase();
  return items.filter((t) => !exclude(t) && (!q || names(t).some((n) => !!n && n.toLowerCase().includes(q))))
    .sort((a, b) => latest(b) - latest(a));
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
