// Apply a projection delta from the core (projector.rs `Delta`) without re-reading everything
// (SPEC §9: send → rendered ≤ 50 ms). Copy-on-write: the top level and every touched table are
// new objects, untouched tables are shared, so Svelte sees a change and nothing is re-parsed.
import type { Projection } from './client';

export interface Delta {
  rows: Record<string, Record<string, unknown | null>>;
  sets: Record<string, Record<string, boolean | null>>;
  fronts: Record<string, unknown | null>;
  reviews: Record<string, unknown | null>;
  opaque: number;
  full: boolean;
}

function patch<T>(table: Record<string, T> | undefined, changes: Record<string, T | null>): Record<string, T> | undefined {
  const next: Record<string, T> = { ...(table ?? {}) };
  for (const [k, v] of Object.entries(changes)) {
    if (v === null) delete next[k];
    else next[k] = v;
  }
  return Object.keys(next).length ? next : undefined;
}

function patchAll<T>(
  group: Record<string, Record<string, T>>,
  changes: Record<string, Record<string, T | null>>,
): Record<string, Record<string, T>> {
  const out = { ...group };
  for (const [table, c] of Object.entries(changes)) {
    const t = patch(out[table], c);
    if (t) out[table] = t;
    else delete out[table];
  }
  return out;
}

/** The next projection: `p` with `d` applied (neither is modified). */
export function applyDelta(p: Projection, d: Delta): Projection {
  const next = { ...p } as Projection & { reviews?: Projection['reviews'] };
  if (Object.keys(d.rows).length) next.rows = patchAll(p.rows as never, d.rows as never) as Projection['rows'];
  if (Object.keys(d.sets).length) next.sets = patchAll(p.sets, d.sets);
  if (Object.keys(d.fronts).length) next.fronts = (patch(p.fronts as never, d.fronts as never) ?? {}) as Projection['fronts'];
  if (Object.keys(d.reviews).length) {
    const r = patch(p.reviews as never, d.reviews as never) as Projection['reviews'];
    if (r) next.reviews = r;
    else delete next.reviews;
  }
  next.opaque = d.opaque;
  return next;
}
