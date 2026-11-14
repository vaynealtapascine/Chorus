// Who is fronting in a shared space, as the other accounts share it (D-133, `GET /spaces/{id}/fronts`).
// Nothing else feeds this: no follower view, no profile lookups. Account fallbacks for authors whose
// member card a space withholds (`member_names` off) live here too.
import type { MemberRow } from './data';
import type { SpaceAccount, SpaceFront, SpaceFrontEntry } from './spaces';

/** How often an open space asks again: the status changes only at reveal times, minutes after a switch. */
export const REFRESH_MS = 3 * 60_000;

const RANK: Record<string, number> = { front: 0, cocon: 1, present: 2 };

/** The entries to show, the primary fronter first, then by level. */
export function ordered(entries: SpaceFrontEntry[]): SpaceFrontEntry[] {
  return entries
    .map((e, i) => ({ e, i }))
    .sort((a, b) => Number(!!b.e.is_primary) - Number(!!a.e.is_primary) || (RANK[a.e.level] ?? 3) - (RANK[b.e.level] ?? 3) || a.i - b.i)
    .map(({ e }) => e);
}

/** Each account's entries to show, by account id; accounts with nobody to show are left out. */
export function frontsByAccount(items: SpaceFront[]): Map<string, SpaceFrontEntry[]> {
  const out = new Map<string, SpaceFrontEntry[]>();
  for (const it of items) if (it.entries?.length) out.set(it.account_id, ordered(it.entries));
  return out;
}

/** "Kairos & Someone"; null with nobody. */
export const frontLine = (entries: SpaceFrontEntry[] | undefined): string | null =>
  entries?.length ? entries.map((e) => e.name).join(' & ') : null;

/** The glyph of a disc: the entry's own, else the first letter of its name. */
export const discGlyph = (e: SpaceFrontEntry): string => e.glyph ?? ([...e.name.trim()][0] ?? '?').toUpperCase();

/**
 * Stand-ins for message authors with no card (another account's members withheld by `member_names`
 * off): the account's own name and picture, never "Unknown member". Only for authors nobody else knows.
 */
export function accountStandIns(
  msgs: { authors: string[]; account_id?: string }[],
  known: { has(id: string): boolean },
  accounts: SpaceAccount[],
  me: string,
): MemberRow[] {
  const byId = new Map(accounts.map((a) => [a.id, a]));
  const out = new Map<string, MemberRow>();
  for (const m of msgs) {
    const a = m.account_id && m.account_id !== me ? byId.get(m.account_id) : undefined;
    if (!a) continue;
    for (const id of m.authors) {
      if (known.has(id) || out.has(id)) continue;
      out.set(id, {
        id, account_id: a.id, name: a.display_name ?? (a.handle ? `@${a.handle}` : 'Someone'), color: '#A09184', sigils: [],
        proxy_tags: [], avatar_blob: a.avatar_blob ?? undefined, handles: [], archived: false, deleted: false,
      });
    }
  }
  return [...out.values()];
}
