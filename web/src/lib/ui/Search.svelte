<script lang="ts">
  import { onMount } from 'svelte';
  import { channels, members, spaces } from '../data';
  import { activeViewers, memberVisible } from '../hidden';
  import { SearchIndex, parseSearch, type SearchHit } from '../search';
  import { apiBase } from '../sync/device';
  import { sync, type Projection } from '../sync/client';

  let { projection }: { projection: Projection } = $props();
  let query = $state('');
  let viewingAs = $state<string | null>(null);
  let revision = $state(0);
  let remote = $state<SearchHit[]>([]);
  let remoteError = $state('');
  let revealed = $state(new Set<string>());
  let index: SearchIndex | null = null;
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
    revision++;
    return sync.subscribeProjection((next, delta) => { index?.apply(next, delta); revision++; });
  });

  $effect(() => {
    const terms = parsed.terms;
    if (!terms.length || sync.status !== 'live') { remote = []; remoteError = ''; return; }
    const abort = new AbortController();
    const timer = setTimeout(async () => {
      const params = new URLSearchParams({ q: terms.join(' ') });
      if (parsed.in) params.set('in', parsed.in);
      if (parsed.from) params.set('from', parsed.from);
      if (parsed.has) params.set('has', parsed.has);
      if (parsed.before !== undefined) params.set('before', String(parsed.before));
      if (parsed.after !== undefined) params.set('after', String(parsed.after));
      try {
        const response = await fetch(`${apiBase()}/search/messages?${params}`, {
          headers: { authorization: `Bearer ${sync.device?.session ?? ''}` }, signal: abort.signal,
        });
        if (!response.ok) throw new Error(`Search HTTP ${response.status}`);
        const body = await response.json() as { items: SearchHit[] };
        remote = body.items.map((item) => ({ ...item, attachments: [], has_image: false, has_file: false }));
        remoteError = '';
      } catch (error) {
        if (!abort.signal.aborted) remoteError = String(error);
      }
    }, 250);
    return () => { clearTimeout(timer); abort.abort(); };
  });

  const person = (id: string) => people.get(id)?.display_name ?? people.get(id)?.name ?? 'Someone';
  const channel = (id: string) => ch.get(id)?.name ?? 'channel';
  const reveal = (id: string) => { revealed = new Set(revealed).add(id); };
</script>

<section class="search-page">
  <h1 class="display">Search</h1>
  <input type="search" aria-label="Search messages" placeholder="Search messages" bind:value={query} />
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
  {/if}
</section>

<style>
  .search-page { display: grid; gap: var(--s-3); }
  h1 { margin: 0; }
  input, select { font: inherit; color: var(--ink); background: var(--surface); border: 1px solid var(--line); border-radius: var(--r-sm); padding: var(--s-2) var(--s-3); }
  .hint, .meta, .count { margin: 0; color: var(--ink-3); font-size: var(--fs-sm); }
  .viewer { display: flex; align-items: center; gap: var(--s-2); }
  .results { display: grid; gap: var(--s-2); }
  article { padding: var(--s-3); border: 1px solid var(--line); border-radius: var(--r-sm); background: var(--surface); }
  article p { margin: var(--s-2) 0; white-space: pre-wrap; overflow-wrap: anywhere; }
  article a { color: var(--accent); }
  .cw { margin: var(--s-2) 0; background: var(--surface-2); color: var(--ink); border: 1px solid var(--line); border-radius: var(--r-sm); padding: var(--s-2); cursor: pointer; }
</style>
