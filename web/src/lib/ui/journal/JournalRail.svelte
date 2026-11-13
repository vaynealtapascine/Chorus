<script lang="ts">
  import { members, selfMember } from '../../data';
  import { posts } from '../../posts';
  import { savedFeeds } from '../../feeds';
  import { BUILT_IN_NAMES, pinnedTabs, storedPins } from '../../feed-pins';
  import { trendingTags, tagLine } from '../../journal-rail';
  import { sync, type Projection } from '../../sync/client';
  import Icon from '../Icon.svelte';

  let { projection, section, onsection }: { projection: Projection; section: string; onsection: (id: string) => void } = $props();

  const singlet = $derived(!!selfMember(projection));
  const names = $derived(new Map(members(projection).map((m) => [m.id, m.display_name ?? m.name] as [string, string])));
  const trends = $derived(trendingTags(posts(projection), Date.now()));
  const feeds = $derived(savedFeeds(projection));
  const tabIds = $derived(pinnedTabs(storedPins(projection, sync.accountId), new Set(feeds.map((f) => f.id))));
  // saved feeds in the pinned list are this account's own (feeds shared by others are not pinnable)
  const rows = $derived(tabIds.map((id) => {
    const builtIn = !!BUILT_IN_NAMES[id];
    return { id, section: id === 'system' ? 'timeline' : id, builtIn,
      name: BUILT_IN_NAMES[id] ?? feeds.find((f) => f.id === id)?.name ?? 'Feed', line: builtIn ? 'Pinned' : 'Made by you' };
  }));
</script>

<aside class="journal-context" aria-label="Journal">
  <a class="search" href="#/search/posts"><Icon name="search" size={18} />Search posts, tags and members</a>

  {#if trends.length}
    <section class="card" aria-labelledby="rail-tags">
      <h2 id="rail-tags">{singlet ? 'Your tags' : 'Trending in our system'}</h2>
      <ul>
        {#each trends as t (t.tag.toLowerCase())}
          <li><a class="row" href="#/search/posts"><span class="tag">#{t.tag}</span><span class="line">{tagLine(t, t.newestBy ? names.get(t.newestBy) ?? null : null)}</span></a></li>
        {/each}
      </ul>
    </section>
  {/if}

  <section class="card" aria-labelledby="rail-feeds">
    <h2 id="rail-feeds">Your feeds</h2>
    <ul>
      {#each rows as r (r.id)}
        <li><button type="button" class="row feed" class:on={section === r.section} aria-current={section === r.section ? 'true' : undefined} onclick={() => onsection(r.section)}>
          <span class="tile"><Icon name={r.builtIn ? 'journal' : 'hash'} size={20} /></span>
          <span class="text"><span class="name">{r.name}</span><span class="line">{r.line}</span></span></button></li>
      {/each}
      <li><button type="button" class="row all" onclick={() => onsection('feeds')}><span class="name">All feeds</span></button></li>
    </ul>
  </section>

  <section class="card formatting" aria-label="Formatting">
    <h2>Formatting</h2>
    <p>Use <strong>bold</strong>, <em>italic</em>, <code>code</code>, lists and <span class="spoiler" aria-hidden="true"></span> (a spoiler). Long posts fold after 3 lines in feeds.</p>
  </section>

  <footer>
    <button type="button" onclick={() => onsection('saved')}>Saved</button>
    <span aria-hidden="true">·</span>
    <button type="button" onclick={() => onsection('lists')}>Lists</button>
  </footer>
</aside>

<style>
  .journal-context { display: grid; align-content: start; gap: var(--s-4); min-width: 0; }
  .search { display: flex; align-items: center; gap: var(--s-3); min-height: 44px; padding: 0 var(--s-4); border-radius: var(--r-full); background: var(--surface-2); color: var(--ink-3); text-decoration: none; font-size: var(--fs-sm); }
  .search :global(svg) { flex: none; }
  .card { background: var(--surface); border-radius: var(--r-md); padding: var(--s-4); min-width: 0; }
  h2 { margin: 0 0 var(--s-2); font-size: 15px; font-weight: 600; color: var(--ink); }
  ul { list-style: none; margin: 0; padding: 0; display: grid; }
  .row { display: flex; align-items: center; gap: var(--s-3); width: 100%; min-height: 44px; box-sizing: border-box; padding: var(--s-2) 0; border: 0; background: none; text-align: left; font: inherit; color: var(--ink-2); text-decoration: none; cursor: pointer; min-width: 0; }
  a.row { display: grid; align-content: center; gap: 0; }
  .tag { color: var(--accent); font-size: var(--fs-sm); font-weight: 600; }
  .line { color: var(--ink-3); font-size: var(--fs-xs); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .tile { display: grid; place-items: center; width: 40px; height: 40px; flex: none; border-radius: var(--r-row); background: var(--accent-soft); color: var(--accent); }
  .text { display: grid; min-width: 0; flex: 1; }
  .name { font-size: var(--fs-sm); font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .feed.on { color: var(--ink); }
  .all { color: var(--ink-3); min-height: 40px; }
  .all .name { font-weight: 400; }
  .row:hover .name, .row:hover .tag { text-decoration: underline; }
  .formatting { background: none; border: 1px solid var(--line); color: var(--ink-2); }
  .formatting h2 { font-size: var(--fs-xs); font-weight: 400; color: var(--ink-3); }
  .formatting p { margin: 0; font-size: var(--fs-sm); line-height: 1.5; }
  .formatting strong, .formatting em { color: var(--ink); }
  code { color: var(--accent); font-family: inherit; }
  .spoiler { display: inline-block; width: 3.2em; height: 1em; vertical-align: text-bottom; border-radius: 4px; background: var(--surface-3); }
  footer { display: flex; align-items: center; gap: var(--s-2); padding: 0 var(--s-1); color: var(--ink-3); font-size: var(--fs-xs); }
  footer button { border: 0; background: none; padding: var(--s-2) 0; min-height: 32px; font: inherit; color: var(--ink-3); cursor: pointer; }
  footer button:hover { color: var(--ink); text-decoration: underline; }
  :focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
</style>
