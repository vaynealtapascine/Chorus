// Apply a projection delta from the core (projector.rs `Delta`) without re-reading everything
// (SPEC §9: send → rendered ≤ 50 ms). The top level and every touched table are new objects
// (except the in-place tables below), untouched tables are shared, so Svelte sees a change and
// nothing is re-parsed.
import type { Projection } from './client';

export interface Delta {
  rows: Record<string, Record<string, unknown | null>>;
  sets: Record<string, Record<string, boolean | null>>;
  fronts: Record<string, unknown | null>;
  reviews: Record<string, unknown | null>;
  opaque: number;
  full: boolean;
}

/** Tables patched in place instead of copied. Copying a 50k-message table cost ~25 ms of every
 *  send, and as much again in garbage (SPEC §9: send → rendered ≤ 50 ms, R24). Such a table keeps
 *  its object, so the previous projection sees the change too (nothing reads an old one: the app
 *  holds only the newest), and caches keyed on it (data.ts `orderOf`) check `versionOf` and catch
 *  up from `changesSince`. Every other table is copy-on-write. */
const IN_PLACE = new Set(['message']);
const LOG = 16;
const versions = new WeakMap<object, { version: number; log: { version: number; before: Map<string, unknown> }[] }>();

/** How many times an in-place table has been patched (0 for any other table). */
export function versionOf(table: object): number {
  return versions.get(table)?.version ?? 0;
}

/** For an in-place table: every key changed after `version`, with its row as it was then
 *  (undefined: it didn't exist), or null when that is further back than the log keeps. */
export function changesSince(table: object, version: number): Map<string, unknown> | null {
  const v = versions.get(table);
  if (!v || v.version === version) return new Map();
  if (!v.log.length || v.log[0].version > version + 1) return null;
  const out = new Map<string, unknown>();
  for (const step of v.log) {
    if (step.version <= version) continue;
    for (const [k, row] of step.before) if (!out.has(k)) out.set(k, row);
  }
  return out;
}

function patchInPlace<T>(table: Record<string, T>, changes: Record<string, T | null>): Record<string, T> | undefined {
  const before = new Map<string, unknown>();
  let removed = false;
  for (const [k, v] of Object.entries(changes)) {
    before.set(k, table[k]);
    if (v === null) removed = delete table[k];
    else table[k] = v;
  }
  const v = versions.get(table) ?? { version: 0, log: [] };
  v.version += 1;
  v.log.push({ version: v.version, before });
  if (v.log.length > LOG) v.log.shift();
  versions.set(table, v);
  // (`for…in` lists every key of a big table, ~15 ms at 50k: only when something went)
  if (!removed) return table;
  for (const _ in table) return table;
  return undefined;
}

function patch<T>(table: Record<string, T> | undefined, changes: Record<string, T | null>): Record<string, T> | undefined {
  // a key-by-key copy: twice as fast as `{ ...table }` on a big table, and emptiness without
  // listing every key
  const next: Record<string, T> = {};
  for (const k in table) next[k] = table[k];
  for (const [k, v] of Object.entries(changes)) {
    if (v === null) delete next[k];
    else next[k] = v;
  }
  for (const _ in next) return next;
  return undefined;
}

function patchAll<T>(
  group: Record<string, Record<string, T>>,
  changes: Record<string, Record<string, T | null>>,
): Record<string, Record<string, T>> {
  const out = { ...group };
  for (const [table, c] of Object.entries(changes)) {
    const t = IN_PLACE.has(table) && out[table] ? patchInPlace(out[table], c) : patch(out[table], c);
    if (t) out[table] = t;
    else delete out[table];
  }
  return out;
}

/** The next projection: `p` with `d` applied (`p` is unchanged but for its in-place tables). */
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
