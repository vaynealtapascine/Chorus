<script lang="ts">
  import { onMount } from 'svelte';
  import { channels, members, spaces } from '../data';
  import { activeViewers, memberVisible } from '../hidden';
  import { JournalIndex, SearchIndex, parseSearch, type SearchHit, type SearchQuery } from '../search';
  import { apiBase } from '../sync/device';
  import { apiFetch } from '../http';
  import { sync, type Projection } from '../sync/client';

  let { projection }: { projection: Projection } = $props();
  let query = $state('');
  let viewingAs = $state<string | null>(null);
  let revision = $state(0);
  let remote = $state<SearchHit[]>([]);
  let remoteCursor = $state<string | null>(null);
  let loadingMore = $state(false);
  let remoteError = $state('');
  let revealed = $state(new Set<string>());
  // journal posts: this account's from the replica (works offline), plus the server's search of
  // everything the account can read (other accounts' posts aren't in this device's replica)
  interface PostHit {
    id: string; account_id: string; kind: string | null; title: string | null; text: string; cw: string | null;
    occurred_at: number; author_cards: { id: string; name: string | null; display_name: string | null }[];
  }
  let tab = $state<'messages' | 'posts' | 'switches'>('messages');
  let posts = $state<PostHit[]>([]);
  let postCursor = $state<string | null>(null);
  let postError = $state('');
  let index: SearchIndex | null = null;
  let journal: JournalIndex | null = null;
  const parsed = $derived(parseSearch(query));
  const local = $derived.by(() => { void revision; return query.trim() ? index?.search(parsed) ?? [] : []; });
  const known = $derived(new Set(local.map((h) => h.id)));
  const all = $derived([...local, ...remote.filter((h) => !known.has(h.id))]);
  const ch = $derived(new Map(channels(projection).map((c) => [c.id, c])));
  const space = $derived(new Map(spaces(projection).map((s) => [s.id, s])));
  const people = $derived(new Map(members(projection).map((m) => [m.id, m])));
  const active = $derived(activeViewers(projection.fronts[sync.accountId]?.current ?? []));
  const shown = $derived(all.filter((h) => memberVisible(h, space.get(ch.get(h.channel_id)?.space_id ?? '')?.kind, active, viewingAs)));

  onMount(() => {
    index = new SearchIndex(projection);
    journal = new JournalIndex(projection, sync.accountId);
    revision++;
    return sync.subscribeProjection((next, delta) => { index?.apply(next, delta); journal?.apply(next, delta); revision++; });
  });
  const localPosts = $derived.by((): PostHit[] => {
    void revision;
    if (tab !== 'posts' || !query.trim()) return [];
    return (journal?.searchPosts(parsed) ?? []).map((h) => ({
      id: h.id, account_id: sync.accountId, kind: h.kind, title: h.title, text: h.text, cw: h.cw, occurred_at: h.occurred_at,
      author_cards: h.authors.map((a) => ({ id: a, name: person(a), display_name: null })),
    }));
  });
  const allPosts = $derived.by(() => {
    const seen = new Set(posts.map((p) => p.id));
    return [...posts, ...localPosts.filter((p) => !seen.has(p.id))].sort((a, b) => b.occurred_at - a.occurred_at);
  });
  const switches = $derived.by(() => { void revision; return tab === 'switches' && query.trim() ? journal?.searchSwitches(parsed) ?? [] : []; });

  const paramsFor = (filter: SearchQuery, cursor?: string) => {
    const params = new URLSearchParams({ q: filter.terms.join(' '), limit: '25' });
    if (filter.in) params.set('in', filter.in);
    if (filter.from) params.set('from', filter.from);
    if (filter.has) params.set('has', filter.has);
    if (filter.before !== undefined) params.set('before', String(filter.before));
    if (filter.after !== undefined) params.set('after', String(filter.after));
    if (cursor) params.set('cursor', cursor);
    return params;
  };

  const fetchPage = async (filter: SearchQuery, cursor: string | null, signal?: AbortSignal) => {
    const response = await apiFetch(`${apiBase()}/search/messages?${paramsFor(filter, cursor ?? undefined)}`, {
      headers: { authorization: `Bearer ${sync.device?.session ?? ''}` }, signal,
    });
    if (!response.ok) throw new Error(`Search HTTP ${response.status}`);
    const body = await response.json() as { items: SearchHit[]; next_cursor: string | null };
    return {
      items: body.items.map((item) => ({ ...item, attachments: [], has_image: false, has_file: false })),
      cursor: body.next_cursor,
    };
  };

  $effect(() => {
    const filter = parsed;
    if (!filter.terms.length || sync.status !== 'live') { remote = []; remoteCursor = null; remoteError = ''; return; }
    remote = [];
    remoteCursor = null;
    const abort = new AbortController();
    const timer = setTimeout(async () => {
      try {
        const page = await fetchPage(filter, null, abort.signal);
        if (abort.signal.aborted) return;
        remote = page.items;
        remoteCursor = page.cursor;
        remoteError = '';
      } catch (error) {
        if (!abort.signal.aborted) remoteError = error instanceof Error ? error.message : String(error);
      }
    }, 250);
    return () => { clearTimeout(timer); abort.abort(); };
  });

  const fetchPosts = async (terms: string, cursor: string | null, signal?: AbortSignal) => {
    const params = new URLSearchParams({ q: terms, limit: '25' });
    if (cursor) params.set('cursor', cursor);
    const response = await apiFetch(`${apiBase()}/search/posts?${params}`, {
      headers: { authorization: `Bearer ${sync.device?.session ?? ''}` }, signal,
    });
    if (!response.ok) throw new Error(`Search HTTP ${response.status}`);
    return await response.json() as { items: PostHit[]; next_cursor: string | null };
  };

  $effect(() => {
    const terms = parsed.terms.join(' ');
    if (tab !== 'posts' || !terms || sync.status !== 'live') { posts = []; postCursor = null; postError = ''; return; }
    const abort = new AbortController();
    const timer = setTimeout(async () => {
      try {
        const page = await fetchPosts(terms, null, abort.signal);
        if (abort.signal.aborted) return;
        posts = page.items;
        postCursor = page.next_cursor;
        postError = '';
      } catch (error) {
        if (!abort.signal.aborted) postError = error instanceof Error ? error.message : String(error);
      }
    }, 250);
    return () => { clearTimeout(timer); abort.abort(); };
  });

  const morePosts = async () => {
    if (!postCursor || loadingMore) return;
    const cursor = postCursor;
    const terms = parsed.terms.join(' ');
    loadingMore = true;
    try {
      const page = await fetchPosts(terms, cursor);
      if (parsed.terms.join(' ') !== terms || postCursor !== cursor) return;
      posts = [...posts, ...page.items];
      postCursor = page.next_cursor;
    } catch (error) {
      postError = error instanceof Error ? error.message : String(error);
    } finally { loadingMore = false; }
  };
  const byline = (p: PostHit) =>
    p.author_cards.map((a) => a.display_name ?? a.name ?? 'Someone').join(' & ') || (p.account_id === sync.accountId ? 'You' : 'Someone');

  const loadMore = async () => {
    if (!remoteCursor || loadingMore) return;
    const cursor = remoteCursor;
    const currentQuery = query;
    loadingMore = true;
    try {
      const page = await fetchPage(parsed, cursor);
      if (query !== currentQuery || remoteCursor !== cursor) return;
      remote = [...remote, ...page.items];
      remoteCursor = page.cursor;
      remoteError = '';
    } catch (error) {
      if (query === currentQuery) remoteError = error instanceof Error ? error.message : String(error);
    } finally { loadingMore = false; }
  };

  const person = (id: string) => people.get(id)?.display_name ?? people.get(id)?.name ?? 'Someone';
  const channel = (id: string) => ch.get(id)?.name ?? 'channel';
  const reveal = (id: string) => { revealed = new Set(revealed).add(id); };
</script>

<section class="search-page">
  <h1 class="display">Search</h1>
  <div class="tabs" role="tablist" aria-label="What to search">
    <button role="tab" aria-selected={tab === 'messages'} class:on={tab === 'messages'} onclick={() => (tab = 'messages')}>Messages</button>
    <button role="tab" aria-selected={tab === 'posts'} class:on={tab === 'posts'} onclick={() => (tab = 'posts')}>Posts</button>
    <button role="tab" aria-selected={tab === 'switches'} class:on={tab === 'switches'} onclick={() => (tab = 'switches')}>Switches</button>
  </div>
  <input type="search" aria-label={tab === 'posts' ? 'Search posts' : tab === 'switches' ? 'Search switches' : 'Search messages'}
    placeholder={tab === 'posts' ? 'Search journal posts' : tab === 'switches' ? 'Search switches by name or note' : 'Search messages'} bind:value={query} />
  {#if tab === 'switches'}
    <p class="hint">Who was in each switch and its note; before: and after: narrow it. Works offline.</p>
    {#if query.trim()}
      <p class="count">{switches.length} switch{switches.length === 1 ? '' : 'es'}</p>
      <div class="results">
        {#each switches as sw (sw.id)}
          <article>
            <div class="meta">{new Date(sw.occurred_at).toLocaleString()}</div>
            <p>{sw.names.length ? sw.names.join(' & ') : 'Switched out'}</p>
            {#if sw.note}<p class="meta">{sw.note}</p>{/if}
          </article>
        {:else}
          <p class="hint">No matching switches.</p>
        {/each}
      </div>
    {/if}
  {:else if tab === 'posts'}
    <p class="hint">Titles, text and tags of posts you can read: yours, and ones shared with you.</p>
    {#if sync.status !== 'live'}<p class="hint" role="status">Offline: searching your own posts on this device.</p>{/if}
    {#if postError}<p class="hint" role="status">Server post search unavailable; showing your own posts. {postError}</p>{/if}
    {#if query.trim()}
      <p class="count">{allPosts.length}{postCursor ? '+' : ''} post{allPosts.length === 1 ? '' : 's'}</p>
      <div class="results">
        {#each allPosts as post (post.id)}
          <article>
            <div class="meta">{byline(post)} · {new Date(post.occurred_at).toLocaleString()}</div>
            {#if post.cw && !revealed.has(post.id)}
              <button class="cw" onclick={() => reveal(post.id)}>Content warning: {post.cw} · Show content</button>
            {:else}
              {#if post.title}<strong>{post.title}</strong>{/if}
              <p>{post.text.slice(0, 400)}</p>
            {/if}
            {#if post.account_id === sync.accountId}<a href="#/journal">Open journal</a>{/if}
          </article>
        {:else}
          <p class="hint">No matching posts.</p>
        {/each}
      </div>
      {#if postCursor && sync.status === 'live'}
        <button class="load-more" disabled={loadingMore} onclick={morePosts}>{loadingMore ? 'Loading…' : 'Load more'}</button>
      {/if}
    {/if}
  {:else}
  <p class="hint">Use from:, in:, has:image, has:file, before: and after:. Search works offline on this device.</p>
  <label class="viewer">Viewing as
    <select aria-label="Search viewing as member" value={viewingAs ?? ''} onchange={(e) => (viewingAs = e.currentTarget.value || null)}>
      <option value="">Current front</option>
      {#each members(projection).filter((m) => !m.deleted && !m.archived) as member (member.id)}
        <option value={member.id}>{member.display_name ?? member.name}</option>
      {/each}
    </select>
  </label>
  <p class="hint">Member visibility is a view filter inside your system, not a security boundary.</p>
  {#if remoteError}<p class="hint" role="status">Server history unavailable; showing local results. {remoteError}</p>{/if}
  {#if query.trim()}
    <p class="count">{shown.length} result{shown.length === 1 ? '' : 's'}{sync.status === 'live' ? ' · local and server history' : ' · offline'}</p>
    <div class="results">
      {#each shown as hit (hit.id)}
        <article>
          <div class="meta">{hit.authors.map(person).join(' & ')} · #{channel(hit.channel_id)} · {new Date(hit.occurred_at).toLocaleString()}</div>
          {#if hit.cw && !revealed.has(hit.id)}
            <button class="cw" onclick={() => reveal(hit.id)}>Content warning: {hit.cw} · Show content</button>
          {:else}
            {#if hit.cw}<div class="meta">Content warning: {hit.cw}</div>{/if}
            <p>{hit.text.slice(0, 400)}</p>
          {/if}
          <a href="#/chat/{hit.channel_id}/{hit.id}">Open message</a>
        </article>
      {:else}
        <p class="hint">No matching messages here.</p>
      {/each}
    </div>
    {#if remoteCursor && sync.status === 'live'}
      <button class="load-more" disabled={loadingMore} onclick={loadMore}>{loadingMore ? 'Loading…' : 'Load more from server'}</button>
    {/if}
  {/if}
  {/if}
</section>

<style>
  .search-page { display: grid; gap: var(--s-3); }
  .tabs { display: flex; gap: var(--s-1); }
  .tabs button { font: inherit; border: 1px solid var(--line); border-radius: var(--r-full); padding: var(--s-1) var(--s-3); background: var(--surface); color: var(--ink-2); cursor: pointer; }
  .tabs button.on { background: var(--accent); border-color: var(--accent); color: var(--surface); }
  h1 { margin: 0; }
  input, select { font: inherit; color: var(--ink); background: var(--surface); border: 1px solid var(--line); border-radius: var(--r-sm); padding: var(--s-2) var(--s-3); }
  .hint, .meta, .count { margin: 0; color: var(--ink-3); font-size: var(--fs-sm); }
  .viewer { display: flex; align-items: center; gap: var(--s-2); }
  .results { display: grid; gap: var(--s-2); }
  article { padding: var(--s-3); border: 1px solid var(--line); border-radius: var(--r-sm); background: var(--surface); }
  article p { margin: var(--s-2) 0; white-space: pre-wrap; overflow-wrap: anywhere; }
  article a { color: var(--accent); }
  .cw { margin: var(--s-2) 0; background: var(--surface-2); color: var(--ink); border: 1px solid var(--line); border-radius: var(--r-sm); padding: var(--s-2); cursor: pointer; }
  .load-more { justify-self: start; border: 1px solid var(--line); border-radius: var(--r-sm); padding: var(--s-2) var(--s-3); background: var(--surface); color: var(--ink); cursor: pointer; }
</style>
