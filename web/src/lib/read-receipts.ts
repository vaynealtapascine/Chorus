// What other accounts have read of our messages (D-131, NOTIFICATIONS §5.9): memory only, never
// stored. Fetched when a shared or direct chat opens (`GET /channels/{id}/reads`) and kept up by
// the socket's `read` frames. Android: data/ReadReceipts.kt.
import type { ReadMark } from './core';

export interface Receipt { account_id: string; message_id: string; at: number }
export interface ReadFrame { t: 'read'; channel: string; account: string; message_id: string; at: number }

export class ReadReceipts {
  private byChannel = new Map<string, Map<string, { id: string; at: number }>>();

  /** What a fetch says for one channel replaces what was held for it. */
  replace(channel: string, rows: Receipt[]): void {
    if (!rows.length) { this.byChannel.delete(channel); return; }
    this.byChannel.set(channel, new Map(rows.map((r) => [r.account_id, { id: r.message_id, at: r.at }])));
  }

  /** A live frame only ever moves a reader forward. */
  receive(f: ReadFrame): void {
    const channel = this.byChannel.get(f.channel) ?? new Map<string, { id: string; at: number }>();
    const cur = channel.get(f.account);
    if (cur && (cur.at > f.at || (cur.at === f.at && cur.id >= f.message_id))) return;
    channel.set(f.account, { id: f.message_id, at: f.at });
    this.byChannel.set(f.channel, channel);
  }

  /** The readers' marks, as `ticksFor` takes them (`member` holds the account). */
  marks(channel: string): ReadMark[] {
    return [...(this.byChannel.get(channel) ?? [])].map(([member, m]) => ({ member, at: m.at, id: m.id }));
  }

  drop(channel: string): void { this.byChannel.delete(channel); }
  clear(): void { this.byChannel.clear(); }
}
