// The parts of a member's profile as Who can see shows them (F4.3): four rows, each holding the
// part keys of D-101 it writes. Mirrors Android MemberSharing.kt. The server decides; this only
// shows and edits the choices.
import { audienceLabel, audienceOf, effectiveAudience, isPrivate, ownAudience, sameAudience, withAudience, type Audience } from './profile-audience';

export interface PartGroup { id: 'name' | 'pronouns' | 'description'; title: string; keys: string[] }

/** The built-in rows in order; "Description and fields" also holds every custom field id. */
export const PART_GROUPS: PartGroup[] = [
  { id: 'name', title: 'Name and picture', keys: ['name', 'banner'] },
  { id: 'pronouns', title: 'Pronouns', keys: ['pronouns'] },
  { id: 'description', title: 'Description and fields', keys: ['description', 'birthday'] },
];

export const KEY_LABELS: Record<string, string> = { name: 'Full name', banner: 'Banner', pronouns: 'Pronouns', description: 'Description', birthday: 'Birthday' };

/** The group's keys, with the custom fields added to the description row. */
export function keysOf(group: PartGroup, fieldIds: string[]): string[] {
  return group.id === 'description' ? [...group.keys, ...fieldIds] : group.keys;
}

type Defaults = Map<string, unknown>;

const effective = (profile: unknown, parts: unknown, key: string, defaults: Defaults) =>
  effectiveAudience(profile, parts, key, defaults.has(key) ? defaults.get(key) : undefined);

/** What a row is shared with: one audience when every key agrees, else null ("Mixed"). A closed profile reaches nobody. */
export function summary(profile: unknown, parts: unknown, keys: string[], defaults: Defaults, live: Set<string>): Audience | null {
  if (isPrivate(audienceOf(profile), live)) return { mode: 'private' };
  const all = keys.map((k) => effective(profile, parts, k, defaults));
  return all.every((a) => sameAudience(a, all[0])) ? all[0] : null;
}

/** The row's own choice when every key has the same one; null when all follow their default. */
export function ownOf(parts: unknown, keys: string[]): Audience | null {
  const own = keys.map((k) => ownAudience(parts, k));
  if (own.every((a) => a === null)) return null;
  return own.every((a) => a && sameAudience(a, own[0]!)) ? own[0] : null;
}

/** The parts map with every key of a row set to [audience] (null: back to its default). */
export function withAll(parts: unknown, keys: string[], audience: Audience | null): Record<string, Audience> {
  let next: Record<string, Audience> = withAudience(parts, keys[0], audience);
  for (const k of keys.slice(1)) next = withAudience(next, k, audience);
  return next;
}

export function label(a: Audience | null, names: Map<string, string>): string {
  return a ? audienceLabel(a, names) : 'Mixed';
}
