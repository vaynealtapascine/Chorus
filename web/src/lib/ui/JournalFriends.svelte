<script lang="ts">
  // Journal › Friends: recent posts the accounts this one follows share with it (a server read,
  // like Android's JournalFriends; your own journal stays offline in Our system).
  import { apiFetch } from '../http';
  import { apiBase } from '../sync/device';
  import { sync, type Projection } from '../sync/client';
  import { friendsFeed, type FriendPost, type FriendPosts } from '../friends-feed';
  import FriendPostCard from './FriendPostCard.svelte';

  let { projection, speaker, speakerName, dark }: { projection: Projection; speaker: string | null; speakerName: string; dark: boolean } = $props();
  interface Follow { status: string; account: { id: string; handle: string | null; display_name: string | null } }
  let items = $state<{ account: string; post: FriendPost }[]>([]);
  let loading = $state(true);
  let error = $state('');

  async function api(path: string) {
    const r = await apiFetch(`${apiBase()}${path}`, { headers: { authorization: `Bearer ${sync.device?.session ?? ''}` } });
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
    return r.json();
  }
  async function load() {
    loading = true; error = '';
    try {
      const following = ((await api('/follows')).following as Follow[]).filter((f) => f.status === 'active');
      const sources: FriendPosts[] = [];
      for (const f of following) {
        const account = f.account.display_name || (f.account.handle ? `@${f.account.handle}` : 'Someone');
        try { sources.push({ account, posts: (await api(`/posts?account=${encodeURIComponent(f.account.id)}&limit=5`)).items as FriendPost[] }); }
        catch { error = 'Some friends’ posts could not be loaded. Try again when connected.'; }
      }
      items = friendsFeed(sources);
    } catch {
      error = 'Friends’ posts need a connection. Your own journal is available in Our system.';
    } finally { loading = false; }
  }
  $effect(() => { void sync.device?.session; void load(); });
</script>

<div class="friends" aria-busy={loading}>
  {#if loading}<p class="quiet">Loading friends’ posts…</p>{/if}
  {#if error}<div class="quiet"><p>{error}</p><button onclick={load}>Try again</button></div>{/if}
  {#each items as item (item.post.id)}
    <FriendPostCard bind:post={item.post} account={item.account} {projection} {speaker} {speakerName} {dark} />
  {:else}
    {#if !loading && !error}<p class="quiet">No shared posts yet. Posts from accounts you follow will appear here.</p>{/if}
  {/each}
</div>

<style>
  .friends { display: grid; border-top: 1px solid var(--line); }
  .quiet { margin: 0; padding: 20px 16px; color: var(--ink-3); }
  .quiet p { margin: 0 0 8px; }
  .quiet button { border: 0; background: none; color: var(--accent); font: inherit; font-weight: 600; cursor: pointer; padding: 0; }
</style>
