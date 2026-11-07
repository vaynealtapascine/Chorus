// Who can see each part of a member's profile (D-101). `member.visibility` is who can open the
// profile; `member.field_visibility` maps a part key or custom field id to its own audience. An
// unset built-in part follows the profile; an unset custom field follows the field's
// `default_visibility`. The server applies the same rule (profiles.rs) and is what actually
// decides; this module only shows and edits the choices. Mirrors Android ProfileAudience.kt
// (shared fixture ui_profile_audience.json).

export type Audience =
  | { mode: 'private' }
  | { mode: 'followers' }
  | { mode: 'buckets'; bucket_ids: string[] }
  | { mode: 'server' };

export const PRIVATE: Audience = { mode: 'private' };

/** The built-in parts with their own audience, in the order the editor shows them. The display
 *  name has none: it follows the profile (D-126). */
export const PARTS = ['name', 'pronouns', 'description', 'birthday', 'banner'] as const;
export type Part = (typeof PARTS)[number];

export const PART_LABELS: Record<Part, string> = {
  name: 'Full name',
  pronouns: 'Pronouns',
  description: 'Bio',
  birthday: 'Birthday',
  banner: 'Banner',
};

/** A stored audience, or private when it's missing or of the wrong shape (fails closed, as the server does). */
export function audienceOf(v: unknown): Audience {
  if (!v || typeof v !== 'object' || Array.isArray(v)) return PRIVATE;
  const mode = (v as { mode?: unknown }).mode;
  if (mode === 'followers' || mode === 'server') return { mode };
  if (mode === 'buckets') {
    const ids = (v as { bucket_ids?: unknown }).bucket_ids;
    return { mode, bucket_ids: Array.isArray(ids) ? ids.filter((x): x is string => typeof x === 'string') : [] };
  }
  return PRIVATE;
}

/** A part's or field's own audience, or `null` when it follows the profile (or the field's default). */
export function ownAudience(fieldVisibility: unknown, key: string): Audience | null {
  if (fieldVisibility == null) return null;
  if (typeof fieldVisibility !== 'object' || Array.isArray(fieldVisibility)) return PRIVATE;
  const v = (fieldVisibility as Record<string, unknown>)[key];
  return v == null ? null : audienceOf(v);
}

/** What a part or field is actually shared with, before the profile's own limit. */
export function effectiveAudience(profile: unknown, fieldVisibility: unknown, key: string, fieldDefault?: unknown): Audience {
  return ownAudience(fieldVisibility, key)
    ?? (fieldDefault !== undefined ? audienceOf(fieldDefault) : key === 'name' ? PRIVATE : audienceOf(profile));
}

/** The parts map with [key] set to [audience], or cleared back to "same as profile" with `null`. */
export function withAudience(fieldVisibility: unknown, key: string, audience: Audience | null): Record<string, Audience> {
  const base: Record<string, Audience> = {};
  if (fieldVisibility && typeof fieldVisibility === 'object' && !Array.isArray(fieldVisibility)) {
    for (const [k, v] of Object.entries(fieldVisibility as Record<string, unknown>)) if (v != null) base[k] = audienceOf(v);
  }
  if (audience) base[key] = audience;
  else delete base[key];
  return base;
}

/** A short label: "Only us", "Friends", "Close friends" (one bucket), "2 buckets", "Everyone here". */
export function audienceLabel(a: Audience, bucketNames: Map<string, string> = new Map()): string {
  switch (a.mode) {
    case 'followers': return 'Friends';
    case 'server': return 'Everyone here';
    case 'buckets': {
      const named = a.bucket_ids.filter((id) => bucketNames.has(id));
      if (named.length === 0) return 'Only us';
      return named.length === 1 ? bucketNames.get(named[0])! : `${named.length} buckets`;
    }
    default: return 'Only us';
  }
}

/** Whether nobody outside the account can see it (buckets that no longer exist share with nobody). */
export function isPrivate(a: Audience, liveBuckets: Set<string>): boolean {
  return a.mode === 'private' || (a.mode === 'buckets' && !a.bucket_ids.some((id) => liveBuckets.has(id)));
}

/** Who a new Journal post starts out shared with (D-117), an account pref. */
export const POST_AUDIENCE = 'journal.default_audience';

/** Without buckets that no longer exist; with none left it reaches nobody, so it's private. */
export function liveAudience(a: Audience, liveBuckets: Set<string>): Audience {
  if (a.mode !== 'buckets') return a;
  const ids = a.bucket_ids.filter((id) => liveBuckets.has(id));
  return ids.length ? { mode: 'buckets', bucket_ids: ids } : PRIVATE;
}

export function sameAudience(a: Audience, b: Audience): boolean {
  if (a.mode !== b.mode) return false;
  if (a.mode === 'buckets' && b.mode === 'buckets') return [...a.bucket_ids].sort().join() === [...b.bucket_ids].sort().join();
  return true;
}

/** Whether [next] may add readers over [previous] (Android `Audience.possiblyWidens`): a wider mode
 * does, and so does any bucket that wasn't chosen before, since bucket sets can't be compared. */
export function possiblyWidens(next: Audience, previous: Audience): boolean {
  const rank = { private: 0, buckets: 1, followers: 2, server: 3 } as const;
  if (next.mode === 'buckets' && previous.mode === 'buckets') return next.bucket_ids.some((id) => !previous.bucket_ids.includes(id));
  return rank[next.mode] > rank[previous.mode];
}
