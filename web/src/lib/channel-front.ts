import { core, type SpeakerContext } from './core';
import type { Projection } from './sync/client';

/** A channel's selected front (D-080/D-085) is a private account pref, separate from the
 * channel's autoproxy pref so changing one never overwrites the other. Body when unset. */
export const channelFrontKey = (channel: string) => `authorship_front:${channel}`;

export function channelFrontId(p: Projection, account: string, channel: string): string {
  const rows = p.rows.pref ?? {};
  const key = channelFrontKey(channel);
  const v = (rows[`||${key}`] ?? rows[`${account}||${key}`])?.fields.value;
  return typeof v === 'string' && v ? v : account;
}

/** Fronts a channel can select; empty for an unconfigured account (the picker stays hidden). */
export function channelFrontChoices(p: Projection, account: string): { id: string; name: string }[] {
  const named = p.front_systems?.[account];
  if (!named) return [];
  return Object.values(named.system.fronts)
    .filter((f) => !f.deleted)
    .map((f) => ({ id: f.id, name: f.id === account ? (f.name || 'Body') : f.name }))
    .sort((a, b) => (a.id === account ? -1 : b.id === account ? 1 : a.name.localeCompare(b.name)));
}

/** Default speaker through the core rule: a configured account uses the channel front's
 * authorship layers; an unconfigured one keeps the legacy fronting list. */
export function channelSpeaker(p: Projection, account: string, channel: string, context: SpeakerContext): string[] {
  const named = p.front_systems?.[account];
  if (!named) return core.defaultSpeaker(context);
  return core.defaultSpeakerForFront(context, named.system, account, channelFrontId(p, account, channel));
}

/** Who a read marks: the account plus, with per-member tracking, the channel front's `reading`
 * pool (configured) or the legacy fronting/co-conscious members. */
export function channelReaders(
  p: Projection, account: string, channel: string, perMember: boolean,
  fronting: NonNullable<SpeakerContext['fronting']>, ownMembers: string[],
): string[] {
  const named = p.front_systems?.[account];
  if (!named) return core.readReaders(perMember, fronting);
  const front = named.resolved[channelFrontId(p, account, channel)];
  const pool = front && !front.deleted ? front.purpose_candidates.reading ?? [] : [];
  return core.readReadersForFront(perMember, pool, ownMembers);
}
