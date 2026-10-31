// Who's fronting in the accounts this one follows, as far as each shares it: the server's
// privacy-filtered follower view (NOTIFICATIONS.md §5), kept for chat headers and Home so a singlet
// (or another system) can see who they're talking to. Mirrors android/.../data/FriendFronts.kt;
// keyed by viewer and followed account.
import { frontMembers, type FrontMember, type ViewIn } from './friends';
import { followerView } from './spaces';
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

/** Ask the server again when the last answer is older than `maxAgeMs`. */
export async function refreshFront(account: string, maxAgeMs = 60_000): Promise<void> {
  const last = fetched.get(key(account));
  if (last !== undefined && Date.now() - last < maxAgeMs) return;
  fetched.set(key(account), Date.now());
  putFront(account, await followerView(account));
}
