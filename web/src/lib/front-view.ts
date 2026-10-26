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
