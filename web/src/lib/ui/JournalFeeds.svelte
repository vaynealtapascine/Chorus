<script lang="ts">
  import { core } from '../core';
  import { members, reactions, type MemberRow } from '../data';
  import { feedCandidates, feedContext, filterFeed, savedFeeds } from '../feeds';
  import { canWriteAs, postReaction, type PostRow } from '../posts';
  import { sync, type Projection } from '../sync/client';
  import { apiBase } from '../sync/device';
  import { apiFetch } from '../http';
  import type { AttachmentRow } from '../data';
  import type { Entity } from '../core';
  import AttachmentView from './AttachmentView.svelte';
  import PostCard from './PostCard.svelte';
  import PostComposer from './PostComposer.svelte';
  import PostReplies from './PostReplies.svelte';
  import RichText from './RichText.svelte';

  let { projection, dark }: { projection: Projection; dark: boolean } = $props();
  const feeds = $derived(savedFeeds(projection));
  const candidates = $derived(feedCandidates(projection));
  const people = $derived(new Map(members(projection).map((m) => [m.id, m] as [string, MemberRow])));
  const reacts = $derived(reactions(projection));
  const speaker = $derived(members(projection).find((m) => canWriteAs(projection, m.id, sync.accountId))?.id ?? null);
  let selected = $state('');
  let name = $state('');
  let description = $state('');
  let query = $state('');
  let saveError = $state('');
  let copied = $state(false);
  let replyTo = $state<PostRow | null>(null);
  // who can open this feed (SPEC §6.4): the filter is shared, each reader sees only posts they can read
  let sharing = $state<'private' | 'followers'>('private');
  const filtersByFronting = (q: string | null) => /(^|[\s(-])fronting:/i.test(q ?? '');
  const usesFronting = $derived(filtersByFronting(query));
  // D-069: said wherever such a feed is shared or read
  const FRONTING_NOTE = 'This feed shows who was fronting when these posts were written';

  // feeds other accounts share with this one, evaluated by the server over what we may read
  interface SharedFeed { id: string; name: string | null; description: string | null; query: string | null;
    owner: { handle: string | null; display_name: string | null }; shared: boolean }
  interface SharedPost { id: string; kind: string; title: string | null; text: string; cw: string | null; occurred_at: number;
    entities: Entity[]; attachments: AttachmentRow[]; author_cards: { name: string | null; display_name: string | null }[] }
  let sharedFeeds = $state<SharedFeed[]>([]);
  let openShared = $state<SharedFeed | null>(null);
  let sharedItems = $state<SharedPost[]>([]);
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
      sharedItems = [...sharedItems, ...(j.items as SharedPost[])];
      sharedCursor = j.next_cursor;
    } catch (cause) { sharedError = cause instanceof Error ? cause.message : String(cause); }
  }
  const owner = (f: SharedFeed) => f.owner.display_name ?? (f.owner.handle ? `@${f.owner.handle}` : 'Someone');
  const byline = (p: SharedPost) => p.author_cards.map((a) => a.display_name ?? a.name ?? 'Someone').join(' & ');

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
    name = feed.name;
    description = feed.description ?? '';
    query = feed.query;
    sharing = feed.visibility.mode === 'followers' ? 'followers' : 'private';
    saveError = '';
  }
  function clear() { selected = name = description = query = saveError = ''; sharing = 'private'; }
  function save(e: SubmitEvent) {
    e.preventDefault();
    saveError = '';
    try {
      const ast = core.feedParse(query);
      const id = selected || sync.newId();
      sync.create('feed.set', sync.accountScope, id, { name: name.trim(), description: description.trim() || null,
        query: query.trim(), query_ast: ast, visibility: { mode: sharing } });
      selected = id;
    } catch (cause) { saveError = cause instanceof Error ? cause.message : String(cause); }
  }
  function react(post: PostRow, emoji: string, add: boolean) {
    if (speaker) sync.create(add ? 'post.react' : 'post.unreact', sync.accountScope, post.id, postReaction(post.id, emoji, speaker));
  }
  async function copyQuery() {
    try { await navigator.clipboard.writeText(query); copied = true; } catch { copied = false; }
  }
</script>

<section class="feeds" aria-label="Custom feeds">
  <p class="muted">Saved feed definitions sync to your devices. Each device shows matches from posts and messages it has received.</p>
  {#if feeds.length}<nav aria-label="Saved feeds">{#each feeds as feed (feed.id)}<button class:on={feed.id === selected} onclick={() => choose(feed.id)}>{feed.name}</button>{/each}</nav>{/if}
  <form class="editor" onsubmit={save}>
    <div class="head"><h2>{selected ? 'Edit feed' : 'New feed'}</h2>{#if selected}<button type="button" onclick={clear}>New feed</button>{/if}</div>
    <label>Name <input bind:value={name} required maxlength="80" placeholder="Writing from friends" /></label>
    <label>Description <input bind:value={description} maxlength="200" /></label>
    <label>Filter <textarea bind:value={query} required rows="3" spellcheck="false" placeholder="from:@kai kind:entry -tag:vent"></textarea></label>
    <p class="hint">Use <code>from:</code>, <code>kind:</code>, <code>tag:</code>, <code>mood:</code>, <code>has:</code>, <code>reply:</code>, <code>in:</code>, <code>since:</code>, <code>until:</code>, <code>fronting:</code>, <code>or</code>, parentheses and <code>-</code> to exclude. Names resolve from this account's members, groups and lists.</p>
    <label>Who can open it
      <select bind:value={sharing}>
        <option value="private">Only us</option>
        <option value="followers">Our followers</option>
      </select>
    </label>
    {#if sharing === 'followers'}
      {#if usesFronting}<p class="banner" role="note">{FRONTING_NOTE}. Each follower only sees what their notifications have already shown them, no sooner.</p>{/if}
      <p class="hint">Followers get the filter, not the posts: each of them only sees posts they can already read.</p>
    {/if}
    {#if preview.error}<p class="error" role="alert">{preview.error}</p>{/if}
    {#if saveError}<p class="error" role="alert">{saveError}</p>{/if}
    <div class="actions"><button class="primary" disabled={!name.trim() || !query.trim() || !!preview.error}>Save feed</button>
      {#if query.trim()}<button type="button" onclick={copyQuery}>{copied ? 'Query copied' : 'Copy query'}</button>{/if}
      {#if selected}<button type="button" onclick={() => { sync.create('feed.delete', sync.accountScope, selected, {}); clear(); }}>Delete feed</button>{/if}</div>
  </form>
  {#if sharedFeeds.length}
    <div class="shared" aria-label="Feeds shared with you">
      <h2>Shared with you</h2>
      <nav>{#each sharedFeeds as feed (feed.id)}<button class:on={openShared?.id === feed.id} onclick={() => showShared(feed)}>{feed.name ?? 'Untitled'} · {owner(feed)}</button>{/each}</nav>
      {#if sharedError}<p class="error" role="alert">{sharedError}</p>{/if}
      {#if openShared}
        {#if openShared.description}<p class="muted">{openShared.description}</p>{/if}
        {#if filtersByFronting(openShared.query)}<p class="banner" role="note">{FRONTING_NOTE}, as far as your notifications from {owner(openShared)} have shown you.</p>{/if}
        {#each sharedItems as post (post.id)}
          <article class="message"><p class="muted">{byline(post)} · {new Date(post.occurred_at).toLocaleString()}</p>
            {#if post.cw}<details><summary>Content warning: {post.cw}</summary>{#if post.title}<strong>{post.title}</strong>{/if}<p><RichText text={post.text} entities={post.entities ?? []} /></p>{#each post.attachments ?? [] as attachment (attachment.id)}<AttachmentView {attachment} />{/each}</details>
            {:else}{#if post.title}<strong>{post.title}</strong>{/if}<p><RichText text={post.text} entities={post.entities ?? []} /></p>{#each post.attachments ?? [] as attachment (attachment.id)}<AttachmentView {attachment} />{/each}{/if}
          </article>
        {:else}<p class="muted">Nothing in this feed that you can read yet.</p>{/each}
        {#if sharedCursor}<button onclick={() => showShared(openShared!, true)}>Load more</button>{/if}
      {/if}
    </div>
  {/if}
  {#if query.trim() && !preview.error}
    <div class="preview"><h2>Preview · newest {preview.rows.length} matches</h2>
      {#if replyTo}<p>Replying to a feed post <button onclick={() => (replyTo = null)}>Cancel</button></p><PostComposer {projection} initialAuthors={speaker ? [speaker] : []} replyTo={replyTo.id} onsent={() => (replyTo = null)} />{/if}
      {#each preview.rows as result (`${result.item.kind}:${result.id}`)}
        {#if result.post}
          <PostCard post={result.post} {people} {dark} reactions={reacts.get(result.post.id) ?? new Map()} {speaker} onreply={() => (replyTo = result.post ?? null)} onreact={(emoji, add) => react(result.post!, emoji, add)} />
          <PostReplies postId={result.post.id} />
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
</section>

<style>
  .feeds, .editor, .preview, .shared { display: grid; gap: var(--s-3); }
  select { padding: var(--s-2); border: 1px solid var(--line); border-radius: var(--r-sm); background: var(--surface-2); color: var(--ink); font: inherit; }
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
  .primary { background: var(--accent); color: var(--bg); border: 0; padding: var(--s-2) var(--s-4); border-radius: var(--r-sm); }
  .error { color: var(--danger); }
  .preview { margin-top: var(--s-3); }
  .message { display: grid; gap: var(--s-2); }
  .message a { color: var(--accent); }
</style>
