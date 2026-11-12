<script lang="ts">
  import Button from './kit/Button.svelte';
  import AudienceChip from './AudienceChip.svelte';
  import { audienceOf, type Audience } from '../profile-audience';
  import { validBucketAudience } from '../bucket-controls';
  import { buckets } from '../data';
  import { core } from '../core';
  import { members, reactions, type MemberRow } from '../data';
  import { feedCandidates, feedContext, filterFeed, savedFeeds } from '../feeds';
  import { canWriteAs, postReaction, posts, type PostRow } from '../posts';
  import { sync, type Projection } from '../sync/client';
  import { apiBase } from '../sync/device';
  import { apiFetch } from '../http';
  import AttachmentView from './AttachmentView.svelte';
  import FriendPostCard from './FriendPostCard.svelte';
  import type { FriendPost } from '../friends-feed';
  import PostCard from './PostCard.svelte';
  import { quoteView } from '../post-threads';
  import { savedPosts, socialIndex } from '../journal-social';
  import { router } from '../router.svelte';
  import RichText from './RichText.svelte';
  import DragList from './kit/DragList.svelte';
  import SectionLabel from './kit/SectionLabel.svelte';
  import Icon from './Icon.svelte';
  import { BUILT_IN_NAMES, builtInLine, pinned as withPin, pinnedTabs, savePins, storedPins } from '../feed-pins';

  let { projection, dark, onlists }: { projection: Projection; dark: boolean; onlists?: () => void } = $props();
  const feeds = $derived(savedFeeds(projection));
  // a configured account's "was fronting" reads Body's canonical history (cached per front change)
  // (empty while it loads: never the stale legacy timeline)
  const configured = $derived(!!projection.front_systems?.[sync.accountId]);
  const named = $derived.by(() => { projection.front_systems; return configured ? sync.frontHistory() : null; });
  const candidates = $derived(feedCandidates(projection, Date.now(), configured ? named?.effective.intervals ?? [] : undefined));
  const social = $derived(socialIndex(posts(projection)));
  const saved = $derived(savedPosts(projection.rows.pref ?? {}, sync.accountId));
  const postById = $derived(new Map(posts(projection).map((p) => [p.id, p])));
  const memberName = (m: string) => people.get(m)?.display_name ?? people.get(m)?.name ?? 'Someone';
  const people = $derived(new Map(members(projection).map((m) => [m.id, m] as [string, MemberRow])));
  const reacts = $derived(reactions(projection));
  const speaker = $derived(members(projection).find((m) => canWriteAs(projection, m.id, sync.accountId))?.id ?? null);
  const tabIds = $derived(pinnedTabs(storedPins(projection, sync.accountId), new Set(feeds.map((f) => f.id))));
  const pinnedRows = $derived(tabIds.map((id) => ({ id, name: BUILT_IN_NAMES[id] ?? feeds.find((f) => f.id === id)?.name ?? 'Feed',
    line: BUILT_IN_NAMES[id] ? builtInLine(id) : feeds.find((f) => f.id === id)?.description ?? '', builtIn: !!BUILT_IN_NAMES[id] })));
  let find = $state('');
  const matches = (n: string) => !find.trim() || n.toLowerCase().includes(find.trim().toLowerCase());
  let editing = $state(false);
  let selected = $state('');
  let name = $state('');
  let description = $state('');
  let query = $state('');
  let saveError = $state('');
  let copied = $state(false);
  // who can open this feed (SPEC §6.4): the filter is shared, each reader sees only posts they can read
  let sharing = $state<Audience>({ mode: 'private' });
  const sharingBuckets = $derived(buckets(projection));
  const filtersByFronting = (q: string | null) => /(^|[\s(-])fronting:/i.test(q ?? '');
  const usesFronting = $derived(filtersByFronting(query));
  // D-069: said wherever such a feed is shared or read
  const FRONTING_NOTE = 'This feed shows who was fronting when these posts were written';

  // feeds other accounts share with this one, evaluated by the server over what we may read
  interface SharedFeed { id: string; name: string | null; description: string | null; query: string | null;
    owner: { handle: string | null; display_name: string | null }; shared: boolean }
  let sharedFeeds = $state<SharedFeed[]>([]);
  let openShared = $state<SharedFeed | null>(null);
  let sharedItems = $state<FriendPost[]>([]);
  let sharedCursor = $state<string | null>(null);
  let sharedError = $state('');
  async function api(path: string) {
    const r = await apiFetch(`${apiBase()}${path}`, { headers: { authorization: `Bearer ${sync.device?.session ?? ''}` } });
    const j = await r.json().catch(() => ({}));
    if (!r.ok) throw new Error(j?.error?.message ?? `HTTP ${r.status}`);
    return j;
  }
  $effect(() => {
    if (!sync.device) return;
    api('/feeds').then((j) => { sharedFeeds = (j.items as SharedFeed[]).filter((f) => f.shared); }).catch(() => {});
  });
  async function showShared(feed: SharedFeed, more = false) {
    sharedError = '';
    try {
      const cursor = more && sharedCursor ? `&cursor=${encodeURIComponent(sharedCursor)}` : '';
      const j = await api(`/feeds/${encodeURIComponent(feed.id)}/items?limit=20${cursor}`);
      if (!more) { openShared = feed; sharedItems = []; }
      sharedItems = [...sharedItems, ...(j.items as FriendPost[])];
      sharedCursor = j.next_cursor;
    } catch (cause) { sharedError = cause instanceof Error ? cause.message : String(cause); }
  }
  const owner = (f: SharedFeed) => f.owner.display_name ?? (f.owner.handle ? `@${f.owner.handle}` : 'Someone');

  const preview = $derived.by(() => {
    if (!query.trim()) return { rows: [] as typeof candidates, error: '' };
    try {
      const ast = core.feedParse(query);
      return { rows: filterFeed(candidates, ast, feedContext(projection, ast)).slice(0, 20), error: '' };
    } catch (cause) {
      const raw = cause instanceof Error ? cause.message : String(cause);
      try {
        const details = JSON.parse(raw.slice(raw.indexOf('{'))) as { pos: number; message: string };
        return { rows: [] as typeof candidates, error: `${details.message} at character ${details.pos + 1}` };
      } catch { return { rows: [] as typeof candidates, error: raw }; }
    }
  });

  function choose(id: string) {
    const feed = feeds.find((row) => row.id === id);
    if (!feed) return;
    selected = id;
    editing = true;
    name = feed.name;
    description = feed.description ?? '';
    query = feed.query;
    sharing = audienceOf(feed.visibility);
    saveError = '';
  }
  function clear() { selected = name = description = query = saveError = ''; sharing = { mode: 'private' }; editing = false; }
  function newFeed() { clear(); editing = true; }
  function save(e: SubmitEvent) {
    e.preventDefault();
    saveError = '';
    try {
      if (!validBucketAudience(sharing, sharingBuckets)) throw new Error('Choose existing buckets before sharing.');
      const ast = core.feedParse(query);
      const id = selected || sync.newId();
      sync.create('feed.set', sync.accountScope, id, { name: name.trim(), description: description.trim() || null,
        query: query.trim(), query_ast: ast, visibility: sharing });
      clear();
    } catch (cause) { saveError = cause instanceof Error ? cause.message : String(cause); }
  }
  function react(post: PostRow, emoji: string, add: boolean, as = speaker) {
    if (as) sync.create(add ? 'post.react' : 'post.unreact', sync.accountScope, post.id, postReaction(post.id, emoji, as));
  }
  async function copyQuery() {
    try { await navigator.clipboard.writeText(query); copied = true; } catch { copied = false; }
  }
</script>

<section class="feeds" aria-label="Custom feeds">
  {#if !editing}
    <div class="find"><Icon name="search" size={18} /><input bind:value={find} placeholder="Search feeds" aria-label="Search feeds" /><button type="button" class="plus" aria-label="New feed" title="New feed" onclick={newFeed}><Icon name="plus" size={22} /></button></div>
    {@const shownPinned = pinnedRows.filter((r) => matches(r.name))}
    {#if shownPinned.length}
      <SectionLabel label="Pinned" />
      <DragList items={shownPinned} label="Reorder pinned feeds" onreorder={(ids) => savePins(ids.length === shownPinned.length ? ids : [...ids, ...tabIds.filter((t) => !ids.includes(t))])}>
        {#snippet row(item)}
          <div class="card"><span class="tile"><Icon name={item.builtIn ? "journal" : "hash"} size={20} /></span>{#if item.builtIn}<span class="text"><strong>{item.name}</strong>{#if item.line}<span>{item.line}</span>{/if}</span>{:else}<button type="button" class="text open" onclick={() => choose(item.id)}><strong>{item.name}</strong>{#if item.line}<span>{item.line}</span>{/if}</button>{/if}
            {#if tabIds.length > 1}<button type="button" class="pin on" aria-label="Unpin {item.name}" onclick={() => savePins(withPin(tabIds, item.id, false))}><Icon name="pin" size={18} /></button>{/if}</div>
        {/snippet}
      </DragList>
    {/if}
    {@const mine = feeds.filter((f) => matches(f.name) && !tabIds.includes(f.id))}
    {@const unpinnedBuiltIns = Object.keys(BUILT_IN_NAMES).filter((id) => !tabIds.includes(id) && matches(BUILT_IN_NAMES[id]))}
    {#if unpinnedBuiltIns.length || mine.length}
      <SectionLabel label="Made by you" />
      {#each unpinnedBuiltIns as id (id)}
        <div class="card"><span class="tile"><Icon name="journal" size={20} /></span><span class="text"><strong>{BUILT_IN_NAMES[id]}</strong><span>{builtInLine(id)}</span></span>
          <button type="button" class="pin" aria-label="Pin {BUILT_IN_NAMES[id]}" onclick={() => savePins(withPin(tabIds, id, true))}><Icon name="pin" size={18} /></button></div>
      {/each}
      {#each mine as feed (feed.id)}
        <div class="card"><span class="tile"><Icon name="hash" size={20} /></span><button type="button" class="text open" onclick={() => choose(feed.id)}><strong>{feed.name}</strong>{#if feed.description}<span>{feed.description}</span>{/if}</button>
          <button type="button" class="pin" aria-label="Pin {feed.name}" onclick={() => savePins(withPin(tabIds, feed.id, true))}><Icon name="pin" size={18} /></button></div>
      {/each}
    {/if}
  {:else}
    <div class="head"><button type="button" class="link-btn" onclick={clear}>Back to feeds</button></div>
  {/if}
  {#if editing}
  <form class="editor" onsubmit={save}>
    <div class="head"><h2>{selected ? 'Edit feed' : 'New feed'}</h2>{#if selected}<button type="button" onclick={clear}>New feed</button>{/if}</div>
    <label>Name <input bind:value={name} required maxlength="80" placeholder="Writing from friends" /></label>
    <label>Description <input bind:value={description} maxlength="200" /></label>
    <label>Filter <textarea bind:value={query} required rows="3" spellcheck="false" placeholder="from:@kai kind:entry -tag:vent"></textarea></label>
    <p class="hint">Use <code>from:</code>, <code>kind:</code>, <code>tag:</code>, <code>mood:</code>, <code>has:</code>, <code>reply:</code>, <code>in:</code>, <code>since:</code>, <code>until:</code>, <code>fronting:</code>, <code>or</code>, parentheses and <code>-</code> to exclude. Names resolve from this account's members, groups and lists.</p>
    <div>Who can open it <AudienceChip label="This feed" value={sharing} own buckets={sharingBuckets} onchange={(a) => (sharing = a ?? { mode: 'private' })} /></div>
    {#if sharing.mode !== 'private'}
      {#if usesFronting}<p class="banner" role="note">{FRONTING_NOTE}. Each follower only sees what their notifications have already shown them, no sooner.</p>{/if}
      <p class="hint">Followers get the filter, not the posts: each of them only sees posts they can already read.</p>
    {/if}
    {#if preview.error}<p class="error" role="alert">{preview.error}</p>{/if}
    {#if saveError}<p class="error" role="alert">{saveError}</p>{/if}
    <div class="actions"><Button variant="primary" class="primary" disabled={!name.trim() || !query.trim() || !!preview.error}>Save feed</Button>
      {#if query.trim()}<button type="button" onclick={copyQuery}>{copied ? 'Query copied' : 'Copy query'}</button>{/if}
      {#if selected}<button type="button" onclick={() => { sync.create('feed.delete', sync.accountScope, selected, {}); clear(); }}>Delete feed</button>{/if}</div>
  </form>
  {/if}
  {#if !editing && sharedFeeds.length}
    <div class="shared" aria-label="Feeds shared with you">
      <h2>Shared with you</h2>
      <nav>{#each sharedFeeds as feed (feed.id)}<button class:on={openShared?.id === feed.id} onclick={() => showShared(feed)}>{feed.name ?? 'Untitled'} · {owner(feed)}</button>{/each}</nav>
      {#if sharedError}<p class="error" role="alert">{sharedError}</p>{/if}
      {#if openShared}
        {#if openShared.description}<p class="muted">{openShared.description}</p>{/if}
        {#if filtersByFronting(openShared.query)}<p class="banner" role="note">{FRONTING_NOTE}, as far as your notifications from {owner(openShared)} have shown you.</p>{/if}
        {#each sharedItems as post (post.id)}
          <FriendPostCard {post} account={owner(openShared)} {projection} {speaker} speakerName={speaker ? memberName(speaker) : ''} {dark} />
        {:else}<p class="muted">Nothing in this feed that you can read yet.</p>{/each}
        {#if sharedCursor}<button onclick={() => showShared(openShared!, true)}>Load more</button>{/if}
      {/if}
    </div>
  {/if}
  {#if editing && query.trim() && !preview.error}
    <div class="preview"><h2>Preview · newest {preview.rows.length} matches</h2>
      {#each preview.rows as result (`${result.item.kind}:${result.id}`)}
        {#if result.post}
          <PostCard {projection} post={result.post} quote={quoteView(result.post.quote_post_id, postById, memberName)} {people} {dark} reactions={reacts.get(result.post.id) ?? new Map()} {speaker} {social} {saved} replyCount={social.replies.get(result.post.id) ?? 0} onreply={() => router.go(`/journal/post/${encodeURIComponent(result.post!.id)}`)} onreact={(emoji, add, as) => react(result.post!, emoji, add, as)} />
        {:else if result.message}
          <article class="message"><p class="muted">#{result.channelName} · {new Date(result.occurred_at).toLocaleString()}</p>
            {#if result.message.cw}<details><summary>Content warning: {result.message.cw}</summary><p><RichText text={result.message.text} entities={result.message.entities} /></p>{#each result.message.attachments as attachment (attachment.id)}<AttachmentView {attachment} />{/each}</details>
            {:else}<p><RichText text={result.message.text} entities={result.message.entities} /></p>{#each result.message.attachments as attachment (attachment.id)}<AttachmentView {attachment} />{/each}{/if}
            <a href="#/chat/{result.message.channel_id}/{result.message.id}">Open in chat</a>
          </article>
        {/if}
      {:else}<p class="muted">No matches in this device's saved posts and messages.</p>{/each}
    </div>
  {/if}
  {#if !editing && onlists}<button type="button" class="lists-row" onclick={onlists}><Icon name="members" size={20} /><span>Lists</span><Icon name="chevron-right" size={18} /></button>{/if}
</section>

<style>
  .find { display: flex; align-items: center; gap: var(--s-2); padding: 0 var(--s-1) 0 14px; border-radius: var(--r-full); background: var(--surface-2); color: var(--ink-3); }
  .find input { flex: 1; min-width: 0; border: 0; background: none; color: var(--ink); padding: var(--s-3) 0; font: inherit; }
  .find .plus { display: grid; place-items: center; width: 44px; height: 44px; border: 0; border-radius: 50%; background: none; color: var(--accent); }
  .card { flex: 1; display: flex; align-items: center; gap: var(--s-3); min-width: 0; padding: var(--s-2) var(--s-2) var(--s-2) 0; }
  .feeds > .card { padding: var(--s-2) var(--s-2) var(--s-2) 0; border-bottom: 1px solid var(--line); }
  .tile { display: grid; place-items: center; width: 40px; height: 40px; flex: none; border-radius: var(--r-row); background: var(--accent-soft); color: var(--accent); }
  .card .text { flex: 1; min-width: 0; display: grid; text-align: left; border: 0; background: none; padding: 0; font: inherit; color: var(--ink); }
  .card .text strong { font-weight: 600; font-size: var(--fs-md); }
  .card .text span { color: var(--ink-3); font-size: var(--fs-sm); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .pin { display: grid; place-items: center; width: 40px; height: 40px; border: 0; border-radius: 50%; background: none; color: var(--ink-3); }
  .pin.on { color: var(--accent); }
  .link-btn { border: 0; background: none; color: var(--accent); font: inherit; padding: var(--s-2) 0; }
  .lists-row { display: flex; align-items: center; gap: 14px; padding: 14px var(--s-1); border: 0; border-top: 1px solid var(--line); background: none; color: var(--ink); font: inherit; font-weight: 600; text-align: left; }
  .lists-row span { flex: 1; }
  .feeds, .editor, .preview, .shared { display: grid; gap: var(--s-3); }
  p, h2 { margin: 0; }
  h2 { font-size: var(--fs-md); }
  .muted, .hint { color: var(--ink-3); font-size: var(--fs-sm); }
  .banner { margin: 0; padding: var(--s-2) var(--s-3); border-radius: var(--r-sm); background: var(--surface-2); border: 1px solid var(--line); color: var(--ink-2); font-size: var(--fs-sm); }
  nav, .actions, .head { display: flex; align-items: center; flex-wrap: wrap; gap: var(--s-2); }
  .head { justify-content: space-between; }
  nav button { border: 1px solid var(--line); border-radius: var(--r-full); background: var(--surface); padding: var(--s-1) var(--s-3); }
  nav button.on { background: var(--accent-soft); }
  .editor, .message { padding: var(--s-3); border: 1px solid var(--line); border-radius: var(--r-md); background: var(--surface); }
  .editor label { display: grid; gap: var(--s-1); }
  input, textarea { box-sizing: border-box; width: 100%; padding: var(--s-2); border: 1px solid var(--line); border-radius: var(--r-sm); background: var(--surface-2); color: var(--ink); font: inherit; }
  button { color: var(--accent); cursor: pointer; }
  button:disabled { opacity: .5; cursor: default; }
  .error { color: var(--danger); }
  .preview { margin-top: var(--s-3); }
  .message { display: grid; gap: var(--s-2); }
  .message a { color: var(--accent); }
</style>
