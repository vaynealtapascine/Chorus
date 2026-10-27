/** Ephemeral socket state; never passed to the replica or persistence. */
export interface TypingEntry { account: string; member: string | null; until: number }
export interface TypingFrame extends TypingEntry { t: 'typing'; channel: string; state: 'start' | 'stop' }
export interface TypingInput { t: 'typing'; channel: string; member: string | null; audience: 'all' | 'system'; state: 'start' | 'stop' }

export class TypingState {
  private channels = new Map<string, TypingEntry[]>();
  receive(f: TypingFrame, now: number): void {
    const rest = this.entries(f.channel, now).filter((e) => e.account !== f.account || e.member !== f.member);
    if (f.state === 'start' && f.until > now) rest.push({ account: f.account, member: f.member, until: f.until });
    if (rest.length) this.channels.set(f.channel, rest);
    else this.channels.delete(f.channel);
  }
  entries(channel: string, now: number): TypingEntry[] {
    return (this.channels.get(channel) ?? []).filter((e) => e.until > now);
  }
  expire(now: number): void {
    for (const ch of this.channels.keys()) {
      const live = this.entries(ch, now);
      if (live.length) this.channels.set(ch, live); else this.channels.delete(ch);
    }
  }
  get nextExpiry(): number | undefined {
    const times = [...this.channels.values()].flat().map((e) => e.until);
    return times.length ? Math.min(...times) : undefined;
  }
  clear(): void { this.channels.clear(); }
}

/** Per-device throttle also lives in the leader, so two tabs share the same limit. */
export class TypingThrottle {
  private last = new Map<string, number>();
  allow(channel: string, start: boolean, now: number): boolean {
    if (!start) return true;
    const last = this.last.get(channel);
    if (last !== undefined && now - last < 2000) return false;
    this.last.set(channel, now);
    return true;
  }
  clear(): void { this.last.clear(); }
}

/** Resolver only uses names already available to the reader; never fetches a member by ID. */
export function typingLabel(entries: TypingEntry[], resolve: (entry: TypingEntry) => string): string | null {
  const unique = entries.filter((e, i) => entries.findIndex((p) => p.account === e.account && p.member === e.member) === i);
  if (!unique.length) return null;
  if (unique.length > 2) return 'Several people are typing…';
  const names = unique.map(resolve);
  return names.length === 1 ? `${names[0]} is typing…` : `${names[0]} and ${names[1]} are typing…`;
}


export function typingName(entry: TypingEntry,
  visibleMembers: ReadonlyMap<string, { name: string; display_name?: string; deleted?: boolean }>,
  accounts: readonly { id: string; display_name: string | null; handle: string | null }[]): string {
  const person = entry.member ? visibleMembers.get(entry.member) : undefined;
  if (person && !person.deleted) return person.display_name ?? person.name;
  const account = accounts.find((a) => a.id === entry.account);
  return account?.display_name ?? (account?.handle ? `@${account.handle}` : 'Someone');
}
