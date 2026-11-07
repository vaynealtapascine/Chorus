// Who's fronting in the accounts this one follows, as far as each shares it: the server's
// privacy-filtered follower view (NOTIFICATIONS.md §5), kept for chat headers and Home so a singlet
// (or another system) can see who they're talking to. Mirrors android/.../data/FriendFronts.kt;
// keyed by viewer and followed account.
import { frontMembers, type FrontMember, type ViewIn } from './friends';
import { followerView, following } from './spaces';
import { sync } from './sync/client';

const views = $state<Record<string, FrontMember[]>>({});
const fetched = new Map<string, number>();
const key = (account: string) => `${sync.accountId}|${account}`;

/** Who an account we follow shows as fronting (layer "front" only); undefined until asked. */
export function frontOf(account: string): FrontMember[] | undefined {
  return views[key(account)];
}

export function putFront(account: string, view: ViewIn | null, now = Date.now()) {
  fetched.set(key(account), now);
  views[key(account)] = frontMembers(view);
}

/** Who this account follows, asked at most once a minute: only they have a view for us. */
let followed: { viewer: string; at: number; ids: Set<string> } | null = null;
async function follows(account: string): Promise<boolean> {
  if (!followed || followed.viewer !== sync.accountId || Date.now() - followed.at > 60_000) {
    try {
      const ids = new Set((await following()).filter((f) => f.status === 'active').map((f) => f.account.id));
      followed = { viewer: sync.accountId, at: Date.now(), ids };
    } catch {
      return true; // offline: let the view read decide
    }
  }
  return followed.ids.has(account);
}

/** Ask the server again when the last answer is older than `maxAgeMs`. Someone we don't follow
 * (a DM with an account that only follows us) has no view for us: not asked, nothing shown. */
export async function refreshFront(account: string, maxAgeMs = 60_000): Promise<void> {
  const last = fetched.get(key(account));
  if (last !== undefined && Date.now() - last < maxAgeMs) return;
  fetched.set(key(account), Date.now());
  putFront(account, (await follows(account)) ? await followerView(account) : null);
}
