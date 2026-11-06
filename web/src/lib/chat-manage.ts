// Making and editing chats on the web (D-098, D-099; Android's ChannelCompose/MemberDms/Spaces):
// who a private chat in a DM belongs to on each side, which channel fields an edit changes, and
// who may change a space. Pure helpers; the dialogs write the ops.
import type { ChannelRow } from './data';

/** The sides of a private chat (D-098): a channel outside our own space whose `member_ids` name
 * someone. `mine` are our members it is kept to (only they speak; it shows only while one of
 * them fronts), `theirs` the other account's. Null when it isn't one. */
export function privateSides(
  channel: { member_ids?: string[] },
  space: { kind: string } | undefined,
  own: (memberId: string) => boolean,
): { mine: string[]; theirs: string[] } | null {
  if (!space || space.kind === 'internal' || !channel.member_ids?.length) return null;
  return { mine: channel.member_ids.filter(own), theirs: channel.member_ids.filter((m) => !own(m)) };
}

/** Our existing private chat with an account's DM, as `member` (and nobody else of ours). */
export function existingPrivate(channels: ChannelRow[], spaceId: string, member: string, own: (id: string) => boolean): ChannelRow | undefined {
  return channels
    .filter((c) => c.space_id === spaceId && !c.archived && c.kind === 'member_dm')
    .find((c) => { const mine = (c.member_ids ?? []).filter(own); return mine.length === 1 && mine[0] === member; });
}

/** Our members' DM or group with exactly these members, if there is one. */
export function existingMemberDm(channels: ChannelRow[], spaceId: string, members: string[]): ChannelRow | undefined {
  const want = new Set(members);
  return channels
    .filter((c) => c.space_id === spaceId && c.kind === 'member_dm' && !c.archived)
    .filter((c) => (c.member_ids ?? []).length === want.size && (c.member_ids ?? []).every((m) => want.has(m)))
    .sort((a, b) => a.id.localeCompare(b.id))[0];
}

export interface ChannelDraft {
  name: string;
  topic: string;
  icon: string;
  color: string | null;
  avatar_blob: string | null;
  /** Internal channels: kept to these members (none: everyone in the system). Members' DMs: their people. */
  member_ids: string[];
}

/** A channel's editable fields as a draft. */
export function draftOf(c: (ChannelRow & { icon?: string; color?: string; avatar_blob?: string }) | undefined): ChannelDraft {
  return {
    name: c?.name ?? '', topic: c?.topic ?? '', icon: c?.icon ?? '', color: c?.color ?? null,
    avatar_blob: c?.avatar_blob ?? null, member_ids: [...(c?.member_ids ?? [])],
  };
}

/** Why the draft can't be saved, or null. */
export function draftProblem(d: ChannelDraft, kind: 'text' | 'member_dm', spaceKind = 'internal'): string | null {
  if (!d.name.trim()) return kind === 'member_dm' ? 'Give this chat a name.' : 'Give this channel a name.';
  if (d.name.trim().length > 80) return 'Keep the name within 80 characters.';
  if (kind === 'member_dm' && spaceKind === 'internal' && d.member_ids.length < 2) return 'A members’ chat needs at least two members.';
  if (kind === 'member_dm' && spaceKind !== 'internal' && !d.member_ids.length) return 'Choose whose private chat this is.';
  if ([...d.icon].length > 8) return 'An icon is one emoji or a few characters.';
  return null;
}

/** Only the fields that changed (field-level LWW keeps other devices' edits to the rest). */
export function channelChanges(before: ChannelDraft, after: ChannelDraft): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  // names are kept as written, spaces, capitals and all (owner, 2026-11-06)
  const name = after.name.trim();
  if (name !== before.name) out.name = name;
  if (after.topic.trim() !== before.topic.trim()) out.topic = after.topic.trim() || null;
  if (after.icon.trim() !== before.icon.trim()) out.icon = after.icon.trim() || null;
  if (after.color !== before.color) out.color = after.color;
  if (after.avatar_blob !== before.avatar_blob) out.avatar_blob = after.avatar_blob;
  const ids = [...new Set(after.member_ids)].sort();
  if (ids.join() !== [...before.member_ids].sort().join()) out.member_ids = ids.length ? ids : null;
  return out;
}

/** The payload for a new channel or members' chat in a space. */
export function channelCreate(spaceId: string, d: ChannelDraft, kind: 'text' | 'member_dm'): Record<string, unknown> {
  const fields = channelChanges(draftOf(undefined), d);
  if (fields.member_ids === null) delete fields.member_ids;
  for (const k of ['topic', 'icon', 'color', 'avatar_blob']) if (fields[k] === null) delete fields[k];
  return { space_id: spaceId, kind, ...fields, name: d.name.trim() };
}

export interface SpaceInfoLike { owner_account_id: string; guest?: boolean; accounts: { id: string }[] }

/** Our role in a space ("owner", "admin", "member", "read_only", a custom one), or null. */
export function roleIn(spaceMembers: Record<string, { fields: Record<string, unknown> } | undefined> | undefined,
  spaceId: string, info: SpaceInfoLike | undefined, me: string): string | null {
  if (info?.owner_account_id === me) return 'owner';
  if (info?.guest) return null;
  const role = spaceMembers?.[`${spaceId}|${me}`]?.fields.role;
  return typeof role === 'string' ? role : info?.accounts.some((a) => a.id === me) ? 'member' : null;
}

/** Rename, describe, set roles (`space.*`): the owner and admins of a shared space (perms.rs). */
export const canEditSpace = (kind: string | undefined, role: string | null) => kind === 'shared' && (role === 'owner' || role === 'admin');
/** Add and remove people: the owner only (spaces.rs, project::space_access). */
export const canInvite = (kind: string | undefined, role: string | null) => kind === 'shared' && role === 'owner';
