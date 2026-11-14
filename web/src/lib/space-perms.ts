// Shared-space permissions and our own sharing in a space (D-133, D-160; docs/handoff/d133-clients.md,
// Figma F5.11). The rule itself is core's (`core.spacePerms`); this builds its input from the
// projection, turns the dialog's switches into ops, and says which composer controls to show.
// The server stays the authority: a hidden control is a courtesy, a refusal still arrives.
import { core } from './core';
import type { Projection } from './sync/client';

type Rows = Pick<Projection, 'rows'>;

/** In core's order (`perms::PERMISSIONS`). */
export const PERMISSIONS = ['view', 'send', 'react', 'thread', 'pin', 'manage', 'attach', 'poll', 'aside', 'invite', 'history'] as const;
/** Only meaningful for the whole space; a channel override can't carry them. */
export const SPACE_ONLY = ['invite', 'history'] as const;

export interface Level { allow: string[]; deny: string[] }
export interface Place { account?: Level | null; role?: Level | null; everyone?: Level | null }
export interface Levels { manager: boolean; role: string | null; roles: unknown; channel?: Place; space?: Place }

const list = (v: unknown): string[] => (Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : []);

/** The override stored for one target, or null when nothing was ever written. */
export function levelAt(rows: Rows['rows'], table: 'space_permission' | 'channel_permission', entity: string, type: 'role' | 'account', target: string): Level | null {
  const row = rows[table]?.[`${entity}|${type}|${target}`];
  return row ? { allow: list(row.fields.allow), deny: list(row.fields.deny) } : null;
}

function place(rows: Rows['rows'], table: 'space_permission' | 'channel_permission', entity: string, me: string, role: string | null): Place {
  return {
    account: levelAt(rows, table, entity, 'account', me),
    role: role ? levelAt(rows, table, entity, 'role', role) : null,
    everyone: levelAt(rows, table, entity, 'role', 'everyone'),
  };
}

export interface Who {
  spaceId: string;
  /** A channel to answer for (a thread: its parent message's channel); none for a space-wide question. */
  channelId?: string;
  me: string;
  /** Our role from `roleIn`: owner, admin, member, read_only, a custom id, or null when we aren't in the space. */
  role: string | null;
}

/** The input core's rule takes, from the projection. */
export function levelsFor(rows: Rows['rows'], who: Who): Levels {
  const manager = who.role === 'owner' || who.role === 'admin';
  const role = who.role === 'owner' ? 'admin' : who.role;
  return {
    manager,
    role,
    roles: rows.space?.[who.spaceId]?.fields.roles ?? [],
    ...(who.channelId ? { channel: place(rows, 'channel_permission', who.channelId, who.me, role) } : {}),
    space: place(rows, 'space_permission', who.spaceId, who.me, role),
  };
}

/** What we may do, by core's rule. */
export const permsFor = (rows: Rows['rows'], who: Who): string[] => core.spacePerms(levelsFor(rows, who));

/** The composer controls a space can hide. */
export interface Gates { attach: boolean; poll: boolean; aside: boolean; invite: boolean }
export const OPEN: Gates = { attach: true, poll: true, aside: true, invite: true };

/**
 * Which controls to show. `send` is required for all but invite; `null` (a space that isn't shared, or
 * one whose directory hasn't loaded) hides nothing, as before D-133.
 */
export function gatesFrom(perms: string[] | null): Gates {
  if (!perms) return OPEN;
  const has = (p: string) => perms.includes(p);
  const send = has('send');
  return { attach: send && has('attach'), poll: send && has('poll'), aside: send && has('aside'), invite: has('invite') };
}

// ── the dialog's "everyone" switches ──

/** Rows the dialog draws for everyone, in F5.11's order. */
export const EVERYONE_ROWS: { perm: string; label: string }[] = [
  { perm: 'send', label: 'Send messages' },
  { perm: 'attach', label: 'Send photos and files' },
  { perm: 'poll', label: 'Create polls' },
  { perm: 'pin', label: 'Pin messages' },
  { perm: 'invite', label: 'Invite other systems' },
  { perm: 'aside', label: 'Allow system asides' },
];
export const HISTORY_ROW = { perm: 'history', label: 'History before they joined' };

export type Switches = Record<string, boolean>;

/** What a plain member gets now (the switches show this); admins always have everything. */
export function memberSwitches(rows: Rows['rows'], spaceId: string): Switches {
  const have = core.spacePerms({
    manager: false, role: 'member', roles: rows.space?.[spaceId]?.fields.roles ?? [],
    space: place(rows, 'space_permission', spaceId, '', 'member'),
  });
  const out: Switches = {};
  for (const p of [...EVERYONE_ROWS.map((r) => r.perm), HISTORY_ROW.perm]) out[p] = have.includes(p);
  return out;
}

/**
 * The `space.set_permission` payload for the `everyone` role after the switches change, or null when
 * nothing did. LWW is per target, so the whole lists go, with permissions we don't draw left as they were.
 */
export function everyonePayload(rows: Rows['rows'], spaceId: string, before: Switches, after: Switches): Record<string, unknown> | null {
  const changed = Object.keys(after).filter((p) => after[p] !== before[p]);
  if (!changed.length) return null;
  const now = levelAt(rows, 'space_permission', spaceId, 'role', 'everyone') ?? { allow: [], deny: [] };
  const allow = now.allow.filter((p) => !changed.includes(p));
  const deny = now.deny.filter((p) => !changed.includes(p));
  for (const p of changed) (after[p] ? allow : deny).push(p);
  return { target_type: 'role', target_id: 'everyone', allow, deny };
}

/** What a new shared space starts with (F5.11): history from when people join, no inviting. */
export const NEW_SPACE_EVERYONE = { target_type: 'role', target_id: 'everyone', allow: [] as string[], deny: ['invite', 'history'] };

// ── our own sharing here (`space.set_sharing`) ──

export interface Sharing { member_names: boolean; fronts: boolean; fronts_delay_min: number }
export const SHARING_DEFAULT: Sharing = { member_names: true, fronts: false, fronts_delay_min: 15 };

/** Our settings in a space; what was never written is the default. */
export function sharingOf(rows: Rows['rows'], spaceId: string, account: string): Sharing {
  const f = rows.space_sharing?.[`${spaceId}|${account}`]?.fields ?? {};
  return {
    member_names: typeof f.member_names === 'boolean' ? f.member_names : SHARING_DEFAULT.member_names,
    fronts: typeof f.fronts === 'boolean' ? f.fronts : SHARING_DEFAULT.fronts,
    fronts_delay_min: typeof f.fronts_delay_min === 'number' ? f.fronts_delay_min : SHARING_DEFAULT.fronts_delay_min,
  };
}

/** Only the fields that changed (a field of ours another device set stays); null when none. */
export function sharingChanges(before: Sharing, after: Sharing): Partial<Sharing> | null {
  const out: Partial<Sharing> = {};
  if (after.member_names !== before.member_names) out.member_names = after.member_names;
  if (after.fronts !== before.fronts) out.fronts = after.fronts;
  if (after.fronts_delay_min !== before.fronts_delay_min) out.fronts_delay_min = after.fronts_delay_min;
  return Object.keys(out).length ? out : null;
}

const DELAYS = [0, 15, 60, 360, 1440];
export function delayLabel(min: number): string {
  if (min === 0) return 'Now';
  if (min < 60) return `${min} min`;
  if (min % 1440 === 0) return min === 1440 ? '1 day' : `${min / 1440} days`;
  if (min % 60 === 0) return min === 60 ? '1 hour' : `${min / 60} hours`;
  return `${Math.floor(min / 60)} h ${min % 60} min`;
}
/** The delay choices; a value another client set stays visible as its own choice. */
export function delayOptions(current: number): { id: string; label: string }[] {
  return [...new Set([...DELAYS, current])].sort((a, b) => a - b).map((m) => ({ id: String(m), label: delayLabel(m) }));
}

/** Whether the permissions part of the dialog is ours to change. */
export const canSetPermissions = (kind: string | undefined, role: string | null) => kind === 'shared' && (role === 'owner' || role === 'admin');
/** Our own sharing rows: any present member of a shared space. */
export const canShare = (kind: string | undefined, role: string | null) => kind === 'shared' && role !== null;
