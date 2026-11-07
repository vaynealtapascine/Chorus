// The Basic follow presets as core makes them (notify.rs `preset`), and which one a ceiling matches,
// read from the preset's own fields only, so Advanced details don't make it "custom" (Android
// FollowPresets). "inherit" is a ceiling with none of them set.
import { notifyPreset } from './core/pkg/chorus_wasm.js';
import { canonical, PRESET_KEYS, withPreset } from './bucket-controls';
import { acceptCeiling, everyoneElse, PRESET_IDS } from './friends-screens';
import { sync, type Projection } from './sync/client';

let presets: Record<string, Record<string, unknown>> | null = null;
function all(): Record<string, Record<string, unknown>> {
  presets ??= Object.fromEntries(PRESET_IDS.map((p) => [p, JSON.parse(notifyPreset(p)) as Record<string, unknown>]));
  return presets;
}

export function presetOf(ceiling: Record<string, unknown> | null | undefined): string {
  const own = Object.fromEntries(Object.entries(ceiling ?? {}).filter(([k]) => PRESET_KEYS.includes(k)));
  if (!Object.keys(own).length) return 'inherit';
  const raw = canonical(own);
  return PRESET_IDS.find((p) => canonical(all()[p]) === raw) ?? 'custom';
}

/** [previous] with one preset's fields; limits, sharing toggles and grants stay. */
export const presetCeiling = (preset: string, previous: Record<string, unknown> = {}): Record<string, unknown> => withPreset(previous, all()[preset]);

/** The account default ceiling (`follow_ceiling`), as Settings and People read it. */
export function accountDefault(p: Projection): Record<string, unknown> {
  return (p.rows.pref?.['||follow_ceiling']?.fields.value ?? p.rows.pref?.[`${sync.accountId}||follow_ceiling`]?.fields.value
    ?? (p.rows.account?.[sync.accountId]?.fields.settings as Record<string, unknown> | undefined)?.follow_ceiling ?? {}) as Record<string, unknown>;
}

export const everyoneElsePreset = (p: Projection): string => everyoneElse(accountDefault(p), presetOf);

/** Accept a request (F7.7): into `bucket` when one is picked, with an override only where it differs. */
export function acceptFollow(p: Projection, followId: string, accountId: string, preset: string | null, bucket: { id: string; ceiling: Record<string, unknown> } | null) {
  sync.create('follow.accept', sync.accountScope, followId, {});
  if (bucket) sync.create('bucket.assign', sync.accountScope, bucket.id, { follower_account_id: accountId });
  const ceiling = preset ? acceptCeiling(preset, bucket?.ceiling ?? null, accountDefault(p), presetOf, (x) => presetCeiling(x)) : null;
  if (ceiling) sync.create('follow.set_ceiling', sync.accountScope, followId, { ceiling });
}

/** The bucket new friends start in (`follow.start_bucket`, F7.8), when it still exists. */
export function startBucket(p: Projection): string | null {
  const v = p.rows.pref?.['||follow.start_bucket']?.fields.value ?? p.rows.pref?.[`${sync.accountId}||follow.start_bucket`]?.fields.value;
  return typeof v === 'string' && v ? v : null;
}
