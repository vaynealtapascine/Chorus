// Room speakers (D-103; Android's RoomSpeakers.kt): who speaks in a channel for this account, from
// its `autoproxy:<channel>` pref (D-074). One of them is the default speaker, the composer offers
// the rest with one tap, and messages by any of them sit on our side of the chat.

export type SpeakerMode = 'off' | 'front' | 'latch' | 'member' | 'members';
export interface SpeakerSetting { mode: SpeakerMode; member?: string; members: string[] }

const MODES: SpeakerMode[] = ['off', 'front', 'latch', 'member', 'members'];

/** The pref's value as a setting; anything unknown is `front` (the default). */
export function readSetting(value: unknown): SpeakerSetting {
  const v = (value && typeof value === 'object' ? value : {}) as { mode?: unknown; member?: unknown; members?: unknown };
  const mode = MODES.includes(v.mode as SpeakerMode) ? (v.mode as SpeakerMode) : 'front';
  const members = Array.isArray(v.members) ? [...new Set(v.members.filter((m): m is string => typeof m === 'string' && !!m))] : [];
  return { mode, member: typeof v.member === 'string' ? v.member : undefined, members };
}

/** The pref value to write; `members` keeps its order (the first is the default speaker). */
export function settingValue(mode: SpeakerMode, member?: string, members: string[] = []): Record<string, unknown> {
  if (mode === 'member' && member) return { mode, member };
  if (mode === 'members') return { mode, members: [...new Set(members)] };
  return { mode };
}

export interface RoomFront { member_id: string; is_primary: boolean; level: string }

/** Who speaks here: everyone fronting or co-conscious (primary first) in `front` mode, the chosen
 * members, the one member, or the authors of our latest message; only those we may write as. */
export function roomSpeakers(s: SpeakerSetting, ctx: { fronting: RoomFront[]; lastAuthors: string[]; writable: (id: string) => boolean }): string[] {
  const ids = s.mode === 'front'
    ? [...ctx.fronting].filter((f) => f.level === 'front' || f.level === 'cocon')
      .sort((a, b) => Number(b.is_primary) - Number(a.is_primary)).map((f) => f.member_id)
    : s.mode === 'members' ? s.members
    : s.mode === 'member' ? (s.member ? [s.member] : [])
    : s.mode === 'latch' ? ctx.lastAuthors
    : [];
  return [...new Set(ids)].filter(ctx.writable);
}

/** Does a message sit on our side? Ours, and by one of `aligned` (the room's speakers, whoever is
 * speaking now, and the members inside any of them that is a subsystem, D-102). */
export function alignsRight(m: { account_id?: string | null; authors: string[] }, me: string, aligned: Set<string>): boolean {
  return (!m.account_id || m.account_id === me) && m.authors.some((a) => aligned.has(a));
}
