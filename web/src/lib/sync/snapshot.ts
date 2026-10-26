import { projectionSnapshotCompatible } from '../core/pkg/chorus_wasm.js';
import type { Projection } from './client';
import type { Snapshot } from './persist';

const record = (value: unknown): value is Record<string, unknown> =>
  value !== null && typeof value === 'object' && !Array.isArray(value);

/** A saved projection is disposable. Check the core schema before exposing it to the UI. */
export function readSnapshot(snapshot: Snapshot | null): Projection | null {
  if (!snapshot) return null;
  try {
    if (!projectionSnapshotCompatible(snapshot.digest)) return null;
    const p: unknown = JSON.parse(snapshot.projection);
    if (!record(p) || !record(p.rows) || !record(p.sets) || !record(p.fronts) ||
      typeof p.opaque !== 'number' || !Number.isSafeInteger(p.opaque) || p.opaque < 0) return null;
    return p as unknown as Projection;
  } catch {
    return null;
  }
}
