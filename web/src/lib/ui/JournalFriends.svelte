<script lang="ts">
  // Journal › Friends: recent posts the accounts this one follows share with it, and anyone's
  // replies to our posts that we may read (D-127; a server read, like Android's JournalFriends;
  // your own journal stays offline in Our system).
  import { apiFetch } from '../http';
  import { apiBase } from '../sync/device';
  import { sync, type Projection } from '../sync/client';
  import { FRIEND_PAGE, friendsFeed, olderToLoad, REPLIES, type FriendPost, type FriendPosts } from '../friends-feed';
  import { inbox, loadActivity } from '../inbox-state.svelte';
  import FriendPostCard from './FriendPostCard.svelte';

  let { projection, speaker, speakerName, dark }: { projection: Projection; speaker: string | null; speakerName: string; dark: boolean } = $props();
  interface Follow { status: string; account: { id: string; handle: string | null; display_name: string | null } }
  // each followed account's pages so far (older ones load at the bottom, owner 2026-11-07)
  let sources = $state.raw<(FriendPosts & { id: string })[]>([]);
  const items = $derived(friendsFeed(sources));
  const hasOlder = $derived(olderToLoad(sources).length > 0);
  let loading = $state(true);
  let loadingOlder = $state(false);
  let error = $state('');
  const page = async (id: string, before?: number) =>
    (await api(`/posts?account=${encodeURIComponent(id)}&limit=${FRIEND_PAGE}${before === undefined ? '' : `&before=${before}`}`)).items as FriendPost[];

  async function api(path: string) {
    const r = await apiFetch(`${apiBase()}${path}`, { headers: { authorization: `Bearer ${sync.device?.session ?? ''}` } });
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
    return r.json();
  }
  async function load() {
    loading = true; error = '';
    try {
      // those who only follow us can still share with people they follow (D-129)
      const follows = await api('/follows');
      const active = [...new Map(([...follows.following, ...follows.followers] as Follow[]).filter((f) => f.status === 'active').map((f) => [f.account.id, f])).values()];
      const loaded: (FriendPosts & { id: string })[] = [];
      for (const f of active) {
        const account = f.account.display_name || (f.account.handle ? `@${f.account.handle}` : 'Someone');
        try { const posts = await page(f.account.id); loaded.push({ id: f.account.id, account, posts, more: posts.length >= FRIEND_PAGE }); }
        catch { error = 'Some friends’ posts could not be loaded. Try again when connected.'; }
      }
      try {
        const activity = await loadActivity();
        inbox.activity = activity;
        const replies = activity.items.filter((i) => i.kind === 'reply' && i.post).map((i) => i.post as unknown as FriendPost);
        loaded.push({ id: REPLIES, account: 'Someone', posts: replies, more: activity.items.length >= 50 });
      } catch { error = 'Replies to your posts could not be loaded. Try again when connected.'; }
      sources = loaded;
    } catch {
      error = 'Friends’ posts need a connection. Your own journal is available in Our system.';
    } finally { loading = false; }
  }
  $effect(() => { void sync.device?.session; void load(); });
  /** The next page for the accounts the feed is waiting on. */
  async function loadOlder() {
    if (loadingOlder || loading) return;
    loadingOlder = true;
    try {
      const next = [...sources];
      for (const i of olderToLoad(next)) {
        const s = next[i];
        const before = Math.min(...s.posts.map((p) => p.occurred_at));
        if (s.id === REPLIES) {
          const activity = await loadActivity(before);
          const posts = activity.items.filter((a) => a.kind === 'reply' && a.post).map((a) => a.post as unknown as FriendPost);
          next[i] = { ...s, posts: [...s.posts, ...posts], more: activity.items.length >= 50 && posts.length > 0 };
          continue;
        }
        const posts = await page(s.id, before);
        next[i] = { ...s, posts: [...s.posts, ...posts], more: posts.length >= FRIEND_PAGE };
      }
      sources = next;
    } catch {
      error = 'Older posts could not be loaded. Try again when connected.';
    } finally { loadingOlder = false; }
  }
  // the bottom of the list coming into view asks for older posts
  function nearEnd(node: HTMLElement) {
    const seen = new IntersectionObserver((entries) => { if (entries.some((e) => e.isIntersecting)) void loadOlder(); }, { rootMargin: '600px' });
    seen.observe(node);
    return { destroy: () => seen.disconnect() };
  }
</script>

<div class="friends" aria-busy={loading}>
  {#if loading}<p class="quiet">Loading friends’ posts…</p>{/if}
  {#if error}<div class="quiet"><p>{error}</p><button onclick={load}>Try again</button></div>{/if}
  {#each items as item (item.post.id)}
    <FriendPostCard post={item.post} account={item.account} {projection} {speaker} {speakerName} {dark} />
  {:else}
    {#if !loading && !error}<p class="quiet">No shared posts yet. Posts from accounts you follow, and replies to yours, will appear here.</p>{/if}
  {/each}
  <!-- re-created after each page, so a list still short of the screen keeps loading -->
  {#if hasOlder}{#key sources}<div class="quiet older" use:nearEnd><button onclick={loadOlder} disabled={loadingOlder}>{loadingOlder ? 'Loading older posts…' : 'Older posts'}</button></div>{/key}{/if}
</div>

<style>
  .friends { display: grid; border-top: 1px solid var(--line); }
  .quiet { margin: 0; padding: var(--s-5) var(--s-4); color: var(--ink-3); }
  .quiet p { margin: 0 0 var(--s-2); }
  .older { text-align: center; }
  .quiet button { border: 0; background: none; color: var(--accent); font: inherit; font-weight: 600; cursor: pointer; padding: 0; }
</style>
