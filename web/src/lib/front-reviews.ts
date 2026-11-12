import type { FrontChange, FrontHistoryRow } from './core/fronts';

/** Payloads for merging the two saved sides of a named-front review. */
export function namedReviewMerge(rows: FrontHistoryRow[], review: { switch_a: string; switch_b: string }): FrontChange[] | null {
  const a = rows.find((r) => r.id === review.switch_a && !r.retracted);
  const b = rows.find((r) => r.id === review.switch_b && !r.retracted);
  if (!a || !b || a.id === b.id) return null;
  const seen = new Set<string>();
  const entries = [...a.resulting_front, ...b.resulting_front].filter((entry) => {
    const key = `${entry.subject_type}:${entry.subject_id}`;
    return seen.has(key) ? false : (seen.add(key), true);
  });
  // A backdated switch alone would still set a live manual override. history_only keeps
  // control state intact (D-083), and also works for add/remove/update review sides: core
  // permits replacement entries in an amend only for switch rows.
  return [
    { action: 'switch', entries, effective_at: Math.max(a.occurred_at, b.occurred_at), history_only: true },
    { action: 'retract', target_op_id: a.id },
    { action: 'retract', target_op_id: b.id },
  ];
}

/** The words of the Review page (F3.6); Android's `FrontReviews`. */
export const THIS_DEVICE = 'This device';
export const OTHER_DEVICE = 'Another device';

/** "Keep Pixel 8’s", "Keep this device’s". */
export const keepLabel = (device: string): string =>
  device === THIS_DEVICE ? 'Keep this device’s' : device === OTHER_DEVICE ? 'Keep the other device’s' : `Keep ${device}’s`;

/** What "Both are right" means, in names. */
export const together = (a: string, b: string): string => (a === b ? `${a} fronted` : `${a} and ${b} fronted together`);

/** "Phone says Foxglove, laptop says Amarynthe" (F11.1): what each device recorded. */
export function disagree(aDevice: string, aWho: string, bDevice: string, bWho: string): string {
  const quiet = (d: string) => (d === THIS_DEVICE || d === OTHER_DEVICE ? d.toLowerCase() : d);
  return `${aDevice} says ${aWho}, ${quiet(bDevice)} says ${bWho}`;
}

/** A device by its saved name; this one, or another one, when it has none. */
export const deviceName = (names: ReadonlyMap<string, string>, id: string | null | undefined, me: string | undefined): string =>
  (id ? names.get(id) || (id === me ? THIS_DEVICE : null) : null) ?? OTHER_DEVICE;

export interface ReviewSide { id: string; occurred_at: number; device_id?: string | null; entries: { subject_type: string; subject_id: string }[] }

/** The Needs-you row's detail and time for a review, from the two switches it is about; null while either is unknown. */
export function summary(a: ReviewSide | undefined, b: ReviewSide | undefined, who: (type: string, id: string) => string, device: (s: ReviewSide) => string): [string, number] | null {
  if (!a || !b) return null;
  const names = (s: ReviewSide) => s.entries.map((e) => who(e.subject_type, e.subject_id)).join(' & ') || 'No one';
  return [disagree(device(a), names(a), device(b), names(b)), Math.max(a.occurred_at, b.occurred_at)];
}
