// Shared spaces and DMs (M6.2, API.md §3 "Shared spaces"): who is in each space, and author
// cards for other accounts' members, which never sync into this account's projection.
import { apiBase } from './sync/device';
import { apiFetch } from './http';
import { sync } from './sync/client';
import type { MemberRow } from './data';

export interface SpaceAccount { id: string; handle: string | null; display_name: string | null; kind: string;
  /** The account's own picture (a singlet's is usually on their member instead). */
  avatar_blob?: string | null }
/** `guest`: you're not in it; some of its channels are shared with you (channel permissions). */
export interface SpaceInfo { id: string; kind: 'internal' | 'shared' | 'dm'; name: string | null; owner_account_id: string; guest?: boolean; accounts: SpaceAccount[] }
interface Card { id: string; account_id: string; name: string | null; display_name: string | null; pronouns: string | null; color: string | null; sigils: string[]; avatar_blob: string | null; handle?: string | null; handles?: string[] }

async function call(method: string, path: string, body?: unknown) {
  const r = await apiFetch(`${apiBase()}${path}`, {
    method,
    headers: { 'content-type': 'application/json', authorization: `Bearer ${sync.device?.session ?? ''}` },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await r.text();
  const j = text ? JSON.parse(text) : null;
  if (!r.ok) throw new Error(j?.error?.message ?? `HTTP ${r.status}`);
  return j;
}

export async function listSpaces(): Promise<SpaceInfo[]> {
  return (await call('GET', '/spaces')).items;
}

/** Open (or find) the DM with an account; resolves to the space id. */
export async function openDm(accountId: string): Promise<string> {
  return (await call('POST', '/spaces', { kind: 'dm', accounts: [accountId] })).id;
}

export async function createShared(name: string, accounts: string[]): Promise<string> {
  return (await call('POST', '/spaces', { kind: 'shared', name, accounts })).id;
}

export async function addToSpace(spaceId: string, accounts: string[]): Promise<void> {
  await call('POST', `/spaces/${spaceId}/members`, { accounts });
}

export async function leaveSpace(spaceId: string): Promise<void> {
  await call('DELETE', `/spaces/${spaceId}/members/me`);
}

/** Author cards as member rows, ready to merge into a name lookup. */
export async function authorCards(spaceId: string): Promise<{ accounts: SpaceAccount[]; members: MemberRow[] }> {
  const j = await call('GET', `/spaces/${spaceId}/authors`);
  const members = (j.members as Card[]).map(
    (c): MemberRow => ({
      id: c.id,
      account_id: c.account_id,
      name: c.name ?? 'Someone',
      display_name: c.display_name ?? undefined,
      pronouns: c.pronouns ?? undefined,
      color: c.color ?? '#A09184',
      sigils: c.sigils ?? [],
      proxy_tags: [],
      avatar_blob: c.avatar_blob ?? undefined,
      handle: c.handle ?? undefined,
      handles: c.handles ?? [],
      archived: false,
      deleted: false,
    }),
  );
  return { accounts: j.accounts, members };
}

/** What to call a space in the rail: its name, or for a DM the other account. */
export function spaceTitle(s: { id: string; kind: string; name: string }, info: SpaceInfo | undefined, me: string): string {
  if (info?.guest) {
    const owner = info.accounts.find((a) => a.id === info.owner_account_id);
    const who = owner ? (owner.display_name ?? `@${owner.handle}`) : 'Shared';
    return s.kind === 'internal' ? `${who} · shared with you` : `${s.name || who} · shared with you`;
  }
  if (s.kind === 'internal') return s.name || 'Home';
  if (s.kind === 'dm') {
    const other = info?.accounts.find((a) => a.id !== me);
    return other ? (other.display_name ?? `@${other.handle}`) : 'Direct message';
  }
  return s.name;
}

/** What we may see of an account we follow (NOTIFICATIONS.md §5); null when we don't follow it. */
export async function followerView(accountId: string): Promise<{ entries: { level: string; name: string; color?: string | null }[]; shared?: boolean } | null> {
  try {
    return await call('GET', `/accounts/${encodeURIComponent(accountId)}/view`);
  } catch {
    return null;
  }
}

/** Accounts connected to this one by an active follow either way: who can join our spaces (spaces.rs). */
export async function connections(): Promise<SpaceAccount[]> {
  const j = await call('GET', '/follows');
  const seen = new Map<string, SpaceAccount>();
  for (const f of [...(j?.following ?? []), ...(j?.followers ?? [])] as Follow[]) {
    if (f.status === 'active' && f.account && f.account.id !== sync.accountId) seen.set(f.account.id, f.account);
  }
  return [...seen.values()].sort((a, b) => (a.display_name ?? a.handle ?? '').localeCompare(b.display_name ?? b.handle ?? ''));
}

export const accountName = (a: { display_name: string | null; handle: string | null }) => a.display_name ?? (a.handle ? `@${a.handle}` : 'Someone');

export interface Follow { id: string; status: string; account: SpaceAccount & { avatar_blob?: string | null } }

/** The accounts this one follows (active and asked), from /follows. */
export async function following(): Promise<Follow[]> {
  return ((await call('GET', '/follows'))?.following ?? []) as Follow[];
}
