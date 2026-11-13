// Ticks on our own messages (D-131): a clock until the server has stamped it, one tick once stored,
// two once someone but its authors has read it. The rule is chorus-core's `ticks` (fixtures/ticks).
import { core, type ReadMark, type Tick } from './core';

interface Own { id: string; account_id?: string | null; occurred_at: number; authors: string[]; deleted?: boolean }

/** The tick of each of our own messages in a chat. `members` are our members' marks (only with
 * per-member reading on), `accounts` other accounts' receipts (only with both switches on). A
 * message not yet on the server has no account stamp (data.ts). */
export function ownTicks(messages: Own[], accountId: string, members: ReadMark[], accounts: ReadMark[]): Map<string, Tick> {
  const own = messages.filter((m) => !m.deleted && (!m.account_id || m.account_id === accountId));
  const out = new Map<string, Tick>();
  if (!own.length) return out;
  const ticks = core.ticksFor(own.map((m) => ({ at: m.occurred_at, id: m.id, authors: m.authors, acked: !!m.account_id })), members, accounts);
  own.forEach((m, i) => out.set(m.id, ticks[i]));
  return out;
}
