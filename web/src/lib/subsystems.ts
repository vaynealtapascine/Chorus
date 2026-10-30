// Members who are subsystems (D-092): an account pref `member_subsystem:<member id>` = subsystem
// group id links a member to the group whose members are inside them. Display only: while a
// subsystem member (or a subsystem group fronting as a unit) is in a front, everyone inside reads
// as fronting with it. Mirrors android/.../data/Subsystems.kt; reads and authorship keep the front
// exactly as switched.
import { groups, members, membership } from './data';
import type { Projection } from './sync/client';

const KEY = 'member_subsystem:';

/** member id → subsystem group id; a pending local change wins over the stamped row. */
export function memberSubsystems(p: Projection, accountId: string): Map<string, string> {
  const pending = new Map<string, unknown>();
  const stamped = new Map<string, unknown>();
  for (const [key, row] of Object.entries(p.rows.pref ?? {})) {
    const [account, device, name] = key.split('|');
    if (device !== '' || !name?.startsWith(KEY) || (account && account !== accountId) || !row.exists) continue;
    (account ? stamped : pending).set(name.slice(KEY.length), row.fields.value);
  }
  const out = new Map<string, string>();
  for (const [member, value] of new Map([...stamped, ...pending])) if (typeof value === 'string' && value) out.set(member, value);
  return out;
}

export interface Entry { subject_type: string; subject_id: string; level: string; is_primary: boolean }

/** [entries] plus everyone inside a fronting subsystem, recursively, at its layer and never primary;
 * an explicit entry wins. `via` names the subsystem that brought each added member in. */
export function expandFront<T extends Entry>(p: Projection, accountId: string, entries: T[]): { entries: Entry[]; via: Map<string, string> } {
  const links = memberSubsystems(p, accountId);
  const subsystems = new Map(groups(p).filter((g) => g.kind === 'subsystem').map((g) => [g.id, g]));
  const inGroup = membership(p);
  const people = new Map(members(p).filter((m) => !m.deleted && !m.archived).map((m) => [m.id, m]));
  const holderOf = new Map([...links].filter(([m, g]) => people.has(m) && subsystems.has(g)).map(([m, g]) => [g, m]));
  const groupMembers = (groupId: string): string[] => {
    const ids = new Set<string>();
    const seen = new Set<string>();
    const queue = [groupId];
    while (queue.length) {
      const id = queue.shift()!;
      if (seen.has(id)) continue;
      seen.add(id);
      for (const m of inGroup.get(id) ?? []) ids.add(m);
      for (const g of subsystems.values()) if (g.parent_id === id) queue.push(g.id);
    }
    return [...ids].filter((id) => people.has(id) && id !== holderOf.get(groupId));
  };
  const out: Entry[] = [...entries];
  const present = new Set(entries.filter((e) => e.subject_type === 'member').map((e) => e.subject_id));
  const via = new Map<string, string>();
  const queue: Entry[] = [...entries];
  const seenGroups = new Set<string>();
  while (queue.length) {
    const e = queue.shift()!;
    let name: string | undefined;
    let inside: string[] = [];
    if (e.subject_type === 'member') {
      const g = links.get(e.subject_id);
      if (g && subsystems.has(g) && people.has(e.subject_id)) {
        const m = people.get(e.subject_id)!;
        name = m.display_name ?? m.name;
        inside = (inGroup.get(g) ? [...inGroup.get(g)!] : []).filter((id) => id !== e.subject_id && people.has(id));
      }
    } else if (e.subject_type === 'group' && subsystems.has(e.subject_id) && !seenGroups.has(e.subject_id)) {
      seenGroups.add(e.subject_id);
      name = subsystems.get(e.subject_id)!.name;
      inside = groupMembers(e.subject_id);
    }
    for (const id of inside) {
      if (present.has(id)) continue;
      present.add(id);
      const added = { subject_type: 'member', subject_id: id, level: e.level, is_primary: false };
      out.push(added);
      via.set(id, name ?? 'a subsystem');
      queue.push(added);
    }
  }
  return { entries: out, via };
}
