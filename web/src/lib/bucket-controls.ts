// Privacy buckets (UI11c, D-115): their icons, how a bucket audience reads, and the Advanced
// notification ceiling fields with the values each accepts. Shared with Android through
// fixtures/ui_bucket_controls.json; notification semantics stay in chorus-core::notify.
// named imports: the fixture's validation cases stay out of the bundle
import {
  choice_labels, custom_icon_max, default_icon, fields, icons, limits, menu_collapse_after, swatches,
} from '../../../fixtures/ui_bucket_controls.json';
import type { Audience } from './profile-audience';

export const BUCKET_ICONS: readonly string[] = icons;
export const DEFAULT_BUCKET_ICON: string = default_icon;
export const BUCKET_SWATCHES: readonly string[] = swatches;
/** An audience menu lists this many buckets, then folds the rest under "N more". */
export const COLLAPSE_AFTER: number = menu_collapse_after;

/** A custom icon: a short glyph such as an emoji (every code point non-ASCII). */
export function isCustomIcon(v: string): boolean {
  return v.length >= 1 && v.length <= custom_icon_max && [...v].every((ch) => (ch.codePointAt(0) ?? 0) >= 0x80);
}
/** A bucket's icon as it shows: a built-in icon's name, its custom glyph, or the default. */
export const bucketIcon = (v: unknown): string =>
  typeof v === 'string' && (BUCKET_ICONS.includes(v) || isCustomIcon(v)) ? v : DEFAULT_BUCKET_ICON;
export const isBuiltInIcon = (icon: string): boolean => BUCKET_ICONS.includes(icon);
/** A bucket's colour, `#RRGGBB`, or none. */
export const bucketColor = (v: unknown): string | null =>
  typeof v === 'string' && /^#[0-9a-f]{6}$/i.test(v) ? v.toUpperCase() : null;

export type BucketPresentation = { id: string; name: string; icon?: string; color?: string | null };

/** The buckets a menu lists: all of them up to COLLAPSE_AFTER, else the first ones plus any
 *  chosen further down, and how many are folded away. */
export function listedBuckets<T extends { id: string }>(buckets: T[], chosen: string[], all: boolean): { shown: T[]; folded: number } {
  if (all || buckets.length <= COLLAPSE_AFTER) return { shown: buckets, folded: 0 };
  const shown = buckets.filter((b, i) => i < COLLAPSE_AFTER || chosen.includes(b.id));
  return { shown, folded: buckets.length - shown.length };
}

/** What a bucket audience reads as: the live buckets it names; a deleted one shares with nobody. */
export function bucketAudience(a: Audience, buckets: BucketPresentation[]): string {
  const selected = a.mode === 'buckets' ? buckets.filter((b) => a.bucket_ids.includes(b.id)) : [];
  return selected.length ? selected.map((b) => b.name).join(', ') : 'Only us';
}
/** The icon for an audience naming buckets: the bucket's own when it names one live bucket. */
export function bucketAudienceIcon(a: Audience, buckets: BucketPresentation[]): string {
  const selected = a.mode === 'buckets' ? buckets.filter((b) => a.bucket_ids.includes(b.id)) : [];
  return selected.length === 1 ? bucketIcon(selected[0].icon) : selected.length ? 'users' : 'lock';
}
/** A bucket audience can only be saved naming live buckets, at least one. */
export function validBucketAudience(a: Audience, buckets: BucketPresentation[]): boolean {
  return a.mode !== 'buckets' || (a.bucket_ids.length > 0 && a.bucket_ids.every((id) => buckets.some((b) => b.id === id)));
}

export type FieldKind = 'bool' | 'enum' | 'levels' | 'range' | 'time' | 'limit' | 'quiet';
export interface CeilingField { key: string; label: string; hint?: string; kind: FieldKind; default: unknown; choices?: string[] }
export const CEILING_FIELDS = fields as CeilingField[];
const LABELS: Record<string, string> = choice_labels;
export const choiceLabel = (id: string): string => LABELS[id] ?? id.replaceAll('_', ' ');
const LIMITS = limits;

const whole = (v: unknown, min: number, max: number): v is number => typeof v === 'number' && Number.isInteger(v) && v >= min && v <= max;
const clock = (v: unknown) => typeof v === 'string' && /^([01]\d|2[0-3]):[0-5]\d$/.test(v);

/** Whether `value` may be saved for the ceiling field `key`. */
export function validCeilingValue(key: string, value: unknown): boolean {
  const f = CEILING_FIELDS.find((x) => x.key === key);
  if (!f) return false;
  const o = value && typeof value === 'object' && !Array.isArray(value) ? (value as Record<string, unknown>) : null;
  switch (f.kind) {
    case 'bool': return typeof value === 'boolean';
    case 'enum': return typeof value === 'string' && !!f.choices?.includes(value);
    case 'levels': return Array.isArray(value) && value.every((x) => typeof x === 'string' && f.choices!.includes(x)) && new Set(value).size === value.length;
    case 'range': return !!o && whole(o.min_s, 0, LIMITS.range_max_s) && whole(o.max_s, 0, LIMITS.range_max_s) && (o.max_s as number) >= (o.min_s as number);
    case 'time': {
      if (!o || typeof o.mode !== 'string' || !f.choices!.includes(o.mode)) return false;
      if (o.mode === 'round') return whole(o.round_min, 1, LIMITS.minutes_max);
      if (o.mode === 'jitter') return whole(o.jitter_min, 1, LIMITS.minutes_max);
      return true;
    }
    case 'limit': return value === null || whole(value, 0, LIMITS.per_day_max);
    case 'quiet': return value === null || (!!o && clock(o.from) && clock(o.to));
  }
}

/** Ceiling field `key` set to `value`, or back to inherited (removed) for `undefined`. */
export function withField(ceiling: Record<string, unknown>, key: string, value: unknown): Record<string, unknown> {
  const next = { ...ceiling };
  if (value === undefined) delete next[key]; else next[key] = value;
  return next;
}

// Presets own only these fields; other limits, sharing toggles, grants and unknown keys survive.
export const PRESET_KEYS = ['switch_notifications', 'levels', 'announce_leaving', 'delay', 'time', 'digest_only'];
export function withPreset(previous: Record<string, unknown>, preset: Record<string, unknown>): Record<string, unknown> {
  return { ...Object.fromEntries(Object.entries(previous).filter(([k]) => !PRESET_KEYS.includes(k))), ...preset };
}
export function canonical(v: unknown): string {
  if (Array.isArray(v)) return `[${v.map(canonical).join(',')}]`;
  if (v && typeof v === 'object') return `{${Object.entries(v).sort(([a], [b]) => a.localeCompare(b)).map(([k, x]) => `${JSON.stringify(k)}:${canonical(x)}`).join(',')}}`;
  return JSON.stringify(v);
}
