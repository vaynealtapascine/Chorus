import type { FrontPresence, ResolvedFront } from './core/fronts';
import type { Projection } from './sync/client';

/** Confirmed display presence only. Authorship, mentions, reads and alerts must use their
 * separate resolved purpose pools rather than treating this display list as eligibility. */
export function frontView(p: Projection | null, account: string, front = account): {
  name: string; configured: boolean; state: ResolvedFront['state']; deleted: boolean; entries: FrontPresence[];
} {
  const named = p?.front_systems?.[account];
  if (named) {
    const resolved = named.resolved[front];
    return {
      name: resolved?.name ?? named.system.fronts[front]?.name ?? 'Front',
      configured: true, state: resolved?.state ?? 'unavailable', deleted: resolved?.deleted ?? false,
      entries: !resolved || resolved.deleted || resolved.state === 'unavailable' ? [] : resolved.effective,
    };
  }
  return {
    name: 'Body', configured: false, state: front === account ? 'independent' : 'unavailable', deleted: false,
    entries: front === account ? (p?.fronts[account]?.current ?? []).map(({ level, ...entry }) => ({ ...entry, layer_id: level })) : [],
  };
}

export interface LayerInfo { id: string; name: string; level: number; custom: boolean }
const LEGACY_LAYERS: LayerInfo[] = [
  { id: 'front', name: 'Fronting', level: 0, custom: false },
  { id: 'cocon', name: 'Co-conscious', level: 1, custom: false },
  { id: 'present', name: 'Present', level: 2, custom: false },
];
const ORIGINAL = new Set(['front', 'cocon', 'present']);

/** Live layers, inner to outer (display level, then ID); the legacy three when unconfigured. */
export function frontLayers(p: Projection | null, account: string): LayerInfo[] {
  const named = p?.front_systems?.[account];
  if (!named) return LEGACY_LAYERS;
  return Object.values(named.system.layers)
    .filter((l) => !l.deleted)
    .map((l) => ({ id: l.id, name: l.name, level: l.level, custom: !ORIGINAL.has(l.id) }))
    .sort((a, b) => a.level - b.level || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
}

export interface FrontSummary {
  id: string; name: string; body: boolean; deleted: boolean;
  state: ResolvedFront['state']; issue: ResolvedFront['issue']; source: string | null; sourceName: string | null;
  entries: FrontPresence[];
}

/** Every front (Body first, then by name); an unconfigured account has only Body. */
export function frontList(p: Projection | null, account: string, includeDeleted = false): FrontSummary[] {
  const named = p?.front_systems?.[account];
  if (!named) {
    const v = frontView(p, account);
    return [{ id: account, name: 'Body', body: true, deleted: false, state: v.state, issue: null, source: null, sourceName: null, entries: v.entries }];
  }
  return Object.values(named.system.fronts)
    .filter((f) => includeDeleted || !f.deleted)
    .map((f) => {
      const r = named.resolved[f.id];
      const source = r?.source ?? f.follow?.source ?? null;
      return {
        id: f.id, name: f.name || (f.id === account ? 'Body' : 'Front'), body: f.id === account, deleted: f.deleted,
        state: r?.state ?? 'unavailable', issue: r?.issue ?? null, source,
        sourceName: source ? named.system.fronts[source]?.name ?? null : null,
        entries: frontView(p, account, f.id).entries,
      };
    })
    .sort((a, b) => Number(b.body) - Number(a.body) || a.name.localeCompare(b.name));
}
