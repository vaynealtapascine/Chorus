// What the Members screens count and say (UI21, F4.1 and F4.4): the chips' counts, the groups'
// lines, and who can join a group. Display only; the writes are ordinary group ops
// (`group.add_member` / `group.remove_member`, DATA_MODEL §5), so plain groups fill offline too.
// Mirrors Android's MemberDirectory.kt.
import { groups, members, membership, type GroupRow, type MemberRow } from './data';
import { bodyPresence, frontLayers } from './front-view';
import { orderedMembers } from './member-order';
import { audienceLabel, audienceOf, isPrivate } from './profile-audience';
import { memberSubsystems } from './subsystems';
import type { Projection } from './sync/client';

type Rows = Record<string, { exists: boolean; fields: Record<string, unknown> }>;

/** This account's own members: not in Trash, and none another account made (a friend's card). */
export function eligible(p: Projection, accountId: string | null | undefined, archived: boolean): MemberRow[] {
  const rows = (p.rows.member ?? {}) as Rows;
  return orderedMembers(members(p).filter((m) => {
    if (m.deleted || m.archived !== archived) return false;
    const by = rows[m.id]?.fields.created_by_account_id;
    return !by || by === accountId;
  }));
}

/** Members in the front as switched (D-102): a fronting subsystem's parts aren't counted. */
export function frontingIds(p: Projection, accountId: string): Set<string> {
  return new Set(bodyPresence(p, accountId).filter((e) => e.subject_type === 'member').map((e) => e.subject_id));
}

export function counts(p: Projection, accountId: string): { all: number; fronting: number; archived: number } {
  const active = eligible(p, accountId, false);
  const fronting = frontingIds(p, accountId);
  return { all: active.length, fronting: active.filter((m) => fronting.has(m.id)).length, archived: eligible(p, accountId, true).length };
}

/** The layer a member is fronting at, by its own name ("Steering"), innermost first; null when not in the front. */
export function layerName(p: Projection, accountId: string, memberId: string): string | null {
  const layers = frontLayers(p, accountId);
  let best: { rank: number; name: string } | null = null;
  for (const e of bodyPresence(p, accountId)) {
    if (e.subject_type !== 'member' || e.subject_id !== memberId) continue;
    const i = layers.findIndex((l) => l.id === e.level);
    const rank = i < 0 ? layers.length : i;
    if (!best || rank < best.rank) best = { rank, name: layers[i]?.name ?? e.level };
  }
  return best ? best.name.charAt(0).toUpperCase() + best.name.slice(1) : null;
}

/** A group's active members, in the members' own order (D-090). */
export function groupMembers(p: Projection, groupId: string): MemberRow[] {
  const ids = membership(p).get(groupId);
  return orderedMembers(members(p).filter((m) => !m.deleted && !m.archived && ids?.has(m.id)));
}

/** "Foxglove, Amarynthe, Nairon" or "Foxglove, Kai, Wren +2" (F4.1 group rows). */
export function names(list: MemberRow[], shown = 3): string {
  const head = list.slice(0, shown).map((m) => m.display_name ?? m.name).join(', ');
  return list.length > shown ? `${head} +${list.length - shown}` : head;
}

/** "3 members", and who can see the group once a write has chosen it (nothing reads it yet, so an unset one says nothing). */
export function groupLine(count: number, visibility: unknown, bucketNames: Map<string, string> = new Map()): string {
  const n = `${count} ${count === 1 ? 'member' : 'members'}`;
  if (visibility == null) return n;
  const a = audienceOf(visibility);
  return `${n} · ${isPrivate(a, new Set(bucketNames.keys())) ? 'only our system' : `${audienceLabel(a, bucketNames).toLowerCase()} can see it`}`;
}

/** The member who is subsystem [groupId], if any. */
export function subsystemOwner(p: Projection, accountId: string, groupId: string): MemberRow | undefined {
  const links = memberSubsystems(p, accountId);
  const subsystems = new Set(groups(p).filter((g) => g.kind === 'subsystem').map((g) => g.id));
  for (const [memberId, gid] of links) {
    if (gid !== groupId || !subsystems.has(gid)) continue;
    const m = members(p).find((x) => x.id === memberId);
    if (m && !m.deleted && !m.archived) return m;
  }
  return undefined;
}

/** The live subsystem group [memberId] is, if any. */
export function subsystemOf(p: Projection, accountId: string, memberId: string): GroupRow | undefined {
  const gid = memberSubsystems(p, accountId).get(memberId);
  return gid ? groups(p).find((g) => g.id === gid && g.kind === 'subsystem') : undefined;
}

/** Active members directly inside [memberId], never themselves. */
export function partsOf(p: Projection, accountId: string, memberId: string): MemberRow[] {
  const g = subsystemOf(p, accountId, memberId);
  return g ? groupMembers(p, g.id).filter((m) => m.id !== memberId) : [];
}

/** Whether putting [candidate] inside [memberId] would put them inside themselves. */
export function wouldLoop(p: Projection, accountId: string, memberId: string, candidate: string): boolean {
  if (candidate === memberId) return true;
  const seen = new Set<string>();
  const queue = [candidate];
  while (queue.length) {
    const next = queue.shift()!;
    if (seen.has(next)) continue;
    seen.add(next);
    for (const part of partsOf(p, accountId, next)) {
      if (part.id === memberId) return true;
      queue.push(part.id);
    }
  }
  return false;
}

export interface Candidate { member: MemberRow; inside: boolean; loops: boolean }

/** This account's active members who could be in [group], those already in it first. A subsystem's
 *  own member can't be inside it, nor can anyone the subsystem is already inside. */
export function candidates(p: Projection, accountId: string, group: GroupRow): Candidate[] {
  const inside = membership(p).get(group.id) ?? new Set<string>();
  const owner = subsystemOwner(p, accountId, group.id);
  return eligible(p, accountId, false)
    .filter((m) => m.id !== owner?.id)
    .map((member) => ({ member, inside: inside.has(member.id), loops: !inside.has(member.id) && !!owner && wouldLoop(p, accountId, owner.id, member.id) }))
    .sort((a, b) => Number(b.inside) - Number(a.inside));
}

/** The op that puts a member in a group or takes them out. */
export const membershipOp = (inside: boolean) => (inside ? 'group.remove_member' : 'group.add_member');

/** The groups [memberId] is in. */
export function groupsOf(p: Projection, memberId: string): GroupRow[] {
  const m = membership(p);
  return groups(p).filter((g) => m.get(g.id)?.has(memberId));
}
