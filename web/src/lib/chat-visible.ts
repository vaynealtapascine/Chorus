import { chatMembers } from './subsystems';
// Which chats this account may look at right now (D-097 members' private chats, D-098 private
// chats in DMs), for every place that lists chats or their messages: the chat list, pickers and
// search. One rule, chat-list.ts `privateVisible`; this binds it to a projection.
import { channels, members, messageById, spaces, type ChannelRow } from './data';
import { chatPrefs, privateVisible } from './chat-list';
import { bodyPresence } from './front-view';
import type { Projection } from './sync/client';

/** Resolve thread privacy through every ancestor, refusing incomplete or cyclic ancestry. */
export function privacyChannel(p: Projection, channel: ChannelRow | undefined, byId = new Map(channels(p, undefined, true).map((c) => [c.id, c]))): ChannelRow | undefined {
  const seen = new Set<string>();
  let c = channel;
  while (c?.kind === 'thread') {
    if (seen.has(c.id) || !c.parent_message_id) return undefined;
    seen.add(c.id);
    const parent = messageById(p, c.parent_message_id);
    const next = parent && byId.get(parent.channel_id);
    if (!next || next.space_id !== c.space_id) return undefined;
    c = next;
  }
  return c;
}

export function chatShown(p: Projection, accountId: string, viewingAs: string | null = null): (c: ChannelRow | undefined) => boolean {
  const own = new Set(members(p).map((m) => m.id));
  const inside = chatMembers(p, accountId);
  const switched = bodyPresence(p, accountId);
  // the front exactly as switched for both (D-102)
  const front = switched;
  const hidePrivate = chatPrefs(p.rows.pref ?? {}, accountId).hidePrivate;
  const bySpace = new Map(spaces(p).map((s) => [s.id, s]));
  const byId = new Map(channels(p, undefined, true).map((c) => [c.id, c]));
  const shown = (channel: ChannelRow | undefined): boolean => {
    const c = privacyChannel(p, channel, byId);
    if (!c || !bySpace.has(c.space_id)) return false;
    return privateVisible(c, bySpace.get(c.space_id), front, viewingAs, hidePrivate, (m) => own.has(m), switched, inside);
  };
  return (c) => shown(c);
}
