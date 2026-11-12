import { channels, members, spaces } from './data';
import { existingMemberDm } from './chat-manage';
import { sync, type Projection } from './sync/client';

/** Our chat between `from` and `to` (two of our members), in our own space: the one that exists, or
 * the one to create (Android `MemberDms`). Null when there is no home space yet or `from` is `to`. */
export function memberDmPlan(p: Projection, from: string | null | undefined, to: string, newId: () => string): { id: string; scope: string; create?: Record<string, unknown> } | null {
  const home = spaces(p).find((s) => s.kind === 'internal');
  if (!home || !from || from === to) return null;
  const scope = `space:${home.id}`;
  const existing = existingMemberDm(channels(p, home.id), home.id, [from, to]);
  if (existing) return { id: existing.id, scope };
  const named = new Map(members(p).map((m) => [m.id, m.name] as const));
  const pair = [from, to];
  return { id: newId(), scope, create: { space_id: home.id, kind: 'member_dm', name: pair.map((id) => named.get(id) ?? '?').join(' & '), member_ids: pair } };
}

/** Open (creating if need be) the chat between two of our members. */
export function openMemberDm(p: Projection, from: string | null | undefined, to: string): void {
  const plan = memberDmPlan(p, from, to, () => sync.newId());
  if (!plan) return;
  if (plan.create) sync.create('channel.create', plan.scope, plan.id, plan.create);
  location.hash = `#/chat/${plan.id}`;
}
