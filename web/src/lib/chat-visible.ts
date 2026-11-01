// Which chats this account may look at right now (D-097 members' private chats, D-098 private
// chats in DMs), for every place that lists chats or their messages: the chat list, pickers and
// search. One rule, chat-list.ts `privateVisible`; this binds it to a projection.
import { channels, members, messageById, spaces, type ChannelRow } from './data';
import { chatPrefs, privateVisible } from './chat-list';
import { bodyPresence } from './front-view';
import { expandFront } from './subsystems';
import type { Projection } from './sync/client';

export function chatShown(p: Projection, accountId: string, viewingAs: string | null = null): (c: ChannelRow | undefined) => boolean {
  const own = new Set(members(p).map((m) => m.id));
  const switched = bodyPresence(p, accountId);
  // members inside a fronting subsystem count as here for D-097 (display, D-092); D-098 uses the front as switched
  const front = expandFront(p, accountId, switched).entries;
  const hidePrivate = chatPrefs(p.rows.pref ?? {}, accountId).hidePrivate;
  const bySpace = new Map(spaces(p).map((s) => [s.id, s]));
  const byId = new Map(channels(p).map((c) => [c.id, c]));
  const shown = (c: ChannelRow | undefined, depth = 0): boolean => {
    if (!c) return true;
    // a thread is as visible as the channel its parent message is in
    if (c.kind === 'thread' && c.parent_message_id && depth < 4) {
      const parent = messageById(p, c.parent_message_id);
      if (parent) return shown(byId.get(parent.channel_id), depth + 1);
    }
    return privateVisible(c, bySpace.get(c.space_id), front, viewingAs, hidePrivate, (m) => own.has(m), switched);
  };
  return (c) => shown(c);
}
