// The system's own Handover and Notes channels (D-132, D-162; docs/handoff/d132-clients.md §1). They are
// ordinary channels in the account's own internal space, told apart by `special`, never by name. A
// person's (singlet's) internal space exists only to hold Notes (D-093), so it is never shown as a
// space: only the Notes chat is, as one row.
import { channels, selfMember, type ChannelRow } from './data';
import type { Projection } from './sync/client';

export type Special = 'handover' | 'notes';

/** The icon and the data-free line under a special chat's name (Figma F5.15, F5.19). */
export const SPECIAL_LOOK: Record<Special, { icon: string; subtitle: string }> = {
  handover: { icon: 'handover', subtitle: 'notes between fronts' },
  notes: { icon: 'bookmark', subtitle: 'saved for every member' },
};

/** The line under a special chat's name; a person has one member, so Notes has none to say it for. */
export function specialSubtitle(special: Special, person: boolean): string | null {
  return special === 'notes' && person ? null : SPECIAL_LOOK[special].subtitle;
}

type Rows = Record<string, { exists?: boolean; fields: Record<string, unknown> } | undefined>;

/** Internal spaces this account made (the system's home; a person's holds only Notes). Another
 * system's internal space, seen as a guest, is not ours. A space whose creator isn't known yet
 * (a local op the server hasn't stamped) counts as ours: only our own ops are ever local. */
export function ownInternalSpaces(p: Projection, accountId: string): Set<string> {
  const out = new Set<string>();
  for (const [id, row] of Object.entries((p.rows.space ?? {}) as Rows)) {
    if (!row?.exists || row.fields.deleted_at != null || row.fields.kind !== 'internal') continue;
    const by = row.fields.created_by_account_id;
    if (by == null || by === accountId) out.add(id);
  }
  return out;
}

/** A person's own internal space: it holds only Notes and is shown as nothing but that row (D-093). */
export function isPersonHome(p: Projection, accountId: string, spaceId: string | undefined): boolean {
  return !!spaceId && !!selfMember(p) && ownInternalSpaces(p, accountId).has(spaceId);
}

export interface SpecialChannels {
  /** the live ones: at most one of each in our own internal space */
  handover?: ChannelRow;
  notes?: ChannelRow;
  /** deleted ones: offer Restore (`channel.restore`), never a second `channel.create` */
  trashed: Partial<Record<Special, ChannelRow>>;
}

/** Our Handover and Notes channels, by role. */
export function specialChannels(p: Projection, accountId: string): SpecialChannels {
  const home = ownInternalSpaces(p, accountId);
  const live = new Set(channels(p).map((c) => c.id));
  const out: SpecialChannels = { trashed: {} };
  for (const c of channels(p, undefined, true)) {
    if (!c.special || c.kind !== 'text' || !home.has(c.space_id)) continue;
    if (live.has(c.id)) out[c.special] ??= c;
    else out.trashed[c.special] ??= c;
  }
  return out;
}

/** What a chat's notification level is until someone sets one: Handover rings for everything,
 * Notes stays quiet (D-162); `fallback` is any other chat's default. */
export function defaultLevel(special: Special | undefined, fallback: string): string {
  return special === 'handover' ? 'all' : special === 'notes' ? 'none' : fallback;
}

/** The "#from chats" chip's value: no tag can hold a `#`, so it never clashes with one. */
export const FROM_CHATS = '#from chats';

/** Whether a Notes message shows under the chosen chip ('' is All): it carries the tag, or for
 * "#from chats" it was forwarded from another chat (`forward_of_id`). */
export function noteMatches(m: { forward_of?: string }, tags: string[], filter: string): boolean {
  return !filter || (filter === FROM_CHATS ? !!m.forward_of : tags.includes(filter));
}
