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
