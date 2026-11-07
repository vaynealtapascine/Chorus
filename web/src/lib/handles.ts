// Member handles (D-126): every member's default `@member.subsystem.system`, worked out by the core
// (handles.rs) from what's on this device, plus the handles a member claims (`member.handles`).
// Another account's member arrives as a card carrying the handle we may see (`handle`) and their
// claims (`handles`). Mirrors Android data/Handles.kt.
import { core } from './core';
import { groups, members, membership, type MemberRow } from './data';
import type { Projection } from './sync/client';

export interface Handles {
  /** Without the `@`: `foxglove.lanterns`. */
  default: string;
  claimed: string[];
  /** The first claimed handle, else the default: the one shown everywhere. */
  shown: string;
}

export const MAX_CLAIMS = 5;

/** A claim (and an account handle): 2–32 of a-z, 0-9 and _. Never a dot, so never a default. */
export const validClaim = (h: string): boolean => /^[a-z0-9_]{2,32}$/.test(h);

/** What the claim box turns typing into (core `normalize_claim`). */
export const normalizeClaim = (h: string): string => h.trim().replace(/^@+/, '').toLowerCase().replace(/[ -]/g, '_');

const EMPTY = {};
const cache = new WeakMap<object, { account: string; deps: unknown[]; map: Map<string, Handles> }>();

/** member id → handles for this account's own members (archived and deleted ones too, so a
 *  default never moves when someone else leaves). Cached until members, groups or the account
 *  change (they are copy-on-write tables). */
export function memberHandles(p: Projection, accountId: string): Map<string, Handles> {
  const table = p.rows.member ?? EMPTY;
  const deps = [p.rows.member_group, p.sets.group_membership, p.rows.account?.[accountId], p.rows.system?.[accountId]];
  const hit = cache.get(table);
  if (hit && hit.account === accountId && hit.deps.every((d, i) => d === deps[i])) return hit.map;
  const account = p.rows.account?.[accountId]?.fields ?? {};
  const system = p.rows.system?.[accountId]?.fields ?? {};
  const text = (v: unknown) => (typeof v === 'string' && v.trim() ? v : null);
  const live = groups(p);
  const pairs: [string, string][] = [];
  for (const [group, ids] of membership(p)) for (const member of ids) pairs.push([group, member]);
  const input = {
    account: { id: accountId, handle: text(account.handle), name: text(system.name) ?? text(account.display_name) },
    members: Object.entries((p.rows.member ?? {}) as Record<string, { exists: boolean; fields: Record<string, unknown> }>)
      .filter(([, r]) => r.exists)
      .map(([id, r]) => ({
        id,
        name: text(r.fields.name),
        display_name: text(r.fields.display_name),
        short_id: text(r.fields.short_id),
        is_self: r.fields.is_self === true || r.fields.is_self === 1,
        handles: Array.isArray(r.fields.handles) ? r.fields.handles : [],
      })),
    groups: live.map((g) => ({ id: g.id, kind: g.kind, parent_id: g.parent_id ?? null, name: g.name })),
    membership: pairs,
  };
  const map = new Map(Object.entries(core.memberHandles(input)));
  cache.set(table, { account: accountId, deps, map });
  return map;
}

/** The handle a member is shown with: ours from the core, another account's from its card. */
export function shownHandle(m: Pick<MemberRow, 'id' | 'handle'> | undefined, own: Map<string, Handles>): string | undefined {
  if (!m) return undefined;
  return own.get(m.id)?.shown ?? m.handle ?? undefined;
}

/** Every handle that names one of [people] (ours: default and claims; others': what their cards
 *  carry), lowercased, for the composer's mention resolver. A handle two members share (two
 *  devices claimed it offline, before the server said no) names neither. */
export function handleMentions(own: Map<string, Handles>, people: Iterable<MemberRow>): Map<string, string> {
  const seen = new Map<string, string | null>();
  const add = (handle: string | undefined, id: string) => {
    if (!handle) return;
    const key = handle.toLowerCase();
    seen.set(key, seen.has(key) && seen.get(key) !== id ? null : id);
  };
  for (const m of people) {
    const h = own.get(m.id);
    if (h) { add(h.default, m.id); for (const c of h.claimed) add(c, m.id); }
    else { add(m.handle, m.id); for (const c of m.handles ?? []) add(c, m.id); }
  }
  return new Map([...seen].filter((e): e is [string, string] => e[1] !== null));
}

/** Who else on this device already has [handle] (another of our members, or our account). */
export function claimedHere(p: Projection, accountId: string, handle: string, member: string): string | null {
  const account = p.rows.account?.[accountId]?.fields.handle;
  if (typeof account === 'string' && account.toLowerCase() === handle) return 'your account';
  for (const m of members(p)) {
    if (m.id !== member && !m.deleted && (m.handles ?? []).includes(handle)) return m.display_name ?? m.name;
  }
  return null;
}
