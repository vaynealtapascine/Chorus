// A reply started under a message and finished in another channel (SPEC §5.3 "Reply in…",
// "Reply privately"): the chat view takes it up when it opens the target.
import type { MessageRow } from './data';

let pending: { message: MessageRow; target: string } | null = null;

/** Carry `message` as the reply target to `target`: a channel id, or `space:<id>` (a space's
 * first channel, e.g. a DM whose channel hasn't synced yet). */
export function carryReply(message: MessageRow, target: string): void {
  pending = { message, target };
}

/** The reply waiting for this channel, once. */
export function takeReply(channelId: string, spaceId: string): MessageRow | null {
  if (!pending || (pending.target !== channelId && pending.target !== `space:${spaceId}`)) return null;
  const { message } = pending;
  pending = null;
  return message;
}
