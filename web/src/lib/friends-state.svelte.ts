// What the Friends screens read from the server (UI20): follows, what each followed account shares
// (the filtered follower view), their newest post we may read, and post alerts on our members. Never
// stored; the list and its pages (a friend, sharing with one friend, notifications from them) share
// one load. Android: FriendsScreen's FriendsData.
import { apiFetch } from './http';
import { apiBase } from './sync/device';
import { sync } from './sync/client';
import { putFront } from './friend-fronts.svelte';
import { friendRows, type FollowRow, type FollowerView, type FriendRow, type LatestPost, type Person } from './friends-screens';

export interface Alert { member_id: string; status: 'requested' | 'active'; account: Person }

export async function api(path: string, init: RequestInit = {}): Promise<any> {
  const r = await apiFetch(`${apiBase()}${path}`, {
    ...init,
    headers: { 'content-type': 'application/json', authorization: `Bearer ${sync.device?.session ?? ''}` },
  });
  const text = await r.text();
  const j = text ? JSON.parse(text) : {};
  if (!r.ok) throw new Error(j?.error?.message ?? `HTTP ${r.status}`);
  return j;
}

class FriendsState {
  following = $state<FollowRow[]>([]);
  followers = $state<FollowRow[]>([]);
  views = $state<Record<string, FollowerView>>({});
  latest = $state<Record<string, LatestPost | null>>({});
  alerts = $state<Alert[]>([]);
  loaded = $state(false);
  error = $state('');
  /** The tab Friends was on, kept while a friend's page is open. */
  tab = $state(0);
  rows: FriendRow[] = $derived(friendRows(this.following, this.followers, this.views, this.latest));
  requests = $derived(this.followers.filter((f) => f.status === 'requested'));
  activeFollowers = $derived(this.followers.filter((f) => f.status === 'active'));
  private loading: Promise<void> | null = null;

  row(account: string): FriendRow | undefined { return this.rows.find((r) => r.account.id === account); }

  load(): Promise<void> {
    this.loading ??= this.fetch().finally(() => (this.loading = null));
    return this.loading;
  }

  private async fetch() {
    try {
      const j = await api('/follows');
      const active = (j.following as FollowRow[]).filter((f) => f.status === 'active');
      const views: Record<string, FollowerView> = {};
      const latest: Record<string, LatestPost | null> = {};
      await Promise.all(active.map(async (f) => {
        if (f.account.kind === 'system') {
          try { views[f.account.id] = await api(`/accounts/${encodeURIComponent(f.account.id)}/view`); putFront(f.account.id, views[f.account.id]); } catch { /* not shared yet */ }
        }
        try { latest[f.account.id] = ((await api(`/posts?account=${encodeURIComponent(f.account.id)}&limit=1`)).items?.[0] ?? null); } catch { /* offline */ }
      }));
      this.alerts = (await api('/alerts').catch(() => ({ items: this.alerts }))).items;
      this.following = j.following;
      this.followers = j.followers;
      this.views = views;
      this.latest = latest;
      this.loaded = true;
      this.error = '';
    } catch (e) {
      this.error = e instanceof Error ? e.message : String(e);
    }
  }
}

export const friends = new FriendsState();
