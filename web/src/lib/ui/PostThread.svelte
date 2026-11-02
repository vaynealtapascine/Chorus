<script lang="ts">
  // A post as a thread, the way Twitter reads one: what it answers sits above it (oldest first, one
  // avatar line running down into it), then the post itself, larger, then a reply box that says
  // what it answers, then the replies. Each reply opens as a thread of its own and the browser's
  // back returns here. Own posts and replies come from this device (so they work offline); other
  // accounts' come from `GET /posts/{id}`, read through the server's audience check.
  import { core } from '../core';
  import { members, reactions as allReactions, type MemberRow } from '../data';
  import { canWriteAs, postReaction, posts } from '../posts';
  import { quoteView, remoteNames, threadAncestors, threadQuotes, threadReplies, type PostDetail, type ThreadItem, type ThreadPost } from '../post-threads';
  import QuoteEmbed from './QuoteEmbed.svelte';
  import { apiFetch } from '../http';
  import { apiBase } from '../sync/device';
  import { sync, type Projection } from '../sync/client';
  import { bodyAuthors } from '../front-view';
  import { router } from '../router.svelte';
  import AttachmentView from './AttachmentView.svelte';
  import AvatarImage from './AvatarImage.svelte';
  import Icon from './Icon.svelte';
  import PostCard from './PostCard.svelte';
  import PostComposer from './PostComposer.svelte';
  import RemotePostPoll from './RemotePostPoll.svelte';
  import RichText from './RichText.svelte';
  import PostHistory from './PostHistory.svelte';

  let { projection, id, dark }: { projection: Projection; id: string; dark: boolean } = $props();
  const people = $derived(new Map(members(projection).map((m) => [m.id, m] as [string, MemberRow])));
  const rows = $derived(posts(projection));
  const reacts = $derived(allReactions(projection));
  const active = $derived(members(projection).filter((m) => canWriteAs(projection, m.id, sync.accountId)));
  const speaker = $derived(bodyAuthors(projection, sync.accountId)[0] || active[0]?.id || null);
  const own = $derived(rows.find((post) => post.id === id && !post.deleted));

  let detail = $state<PostDetail | null>(null);
  let error = $state('');
  let loading = $state(false);
  async function load() {
    loading = true;
    try {
      const response = await apiFetch(`${apiBase()}/posts/${encodeURIComponent(id)}?depth=1`, {
        headers: { authorization: `Bearer ${sync.device?.session ?? ''}` },
      });
      if (!response.ok) throw new Error(response.status === 404 ? 'This post isn’t available to you.' : `Could not load the thread (HTTP ${response.status}).`);
      detail = (await response.json()) as PostDetail;
      error = '';
    } catch (cause) {
      error = own ? 'Showing what’s saved on this device. Refresh to load replies from others.'
        : cause instanceof Error ? cause.message : String(cause);
    } finally {
      loading = false;
    }
  }
  $effect(() => {
    id;
    detail = null;
    error = '';
    void load();
  });

  const ancestors = $derived(threadAncestors(rows, id, detail?.ancestors));
  const replies = $derived(threadReplies(rows, id, detail?.replies ?? []));
  const quotes = $derived(threadQuotes(rows, id, detail?.quotes ?? []));
  // Replies or Quotes under the post, like Twitter's tabs; quotes only once there are some
  let showQuotes = $state(false);
  $effect(() => { id; showQuotes = false; });
  const listed = $derived(showQuotes && quotes.length ? quotes : replies);
  const postById = $derived(new Map(rows.map((post) => [post.id, post])));
  const quoteFor = (item: ThreadItem) => quoteView(item.local?.quote_post_id ?? item.remote?.quote?.post_id, postById, name, item.remote?.quoted);
  const focused = $derived(own ?? (detail ? detail as ThreadPost : null));
  const focusedNames = $derived(own ? own.authors.map(name).join(' & ') : detail ? remoteNames(detail) : '');
  const reactionLine = $derived((detail?.reactions ?? []).map((r) => `${r.emoji} ${r.member_name}`).join(' · '));

  function name(memberId: string) {
    return people.get(memberId)?.display_name ?? people.get(memberId)?.name ?? 'Someone';
  }
  function open(next: string) {
    if (next !== id) router.go(`/journal/post/${encodeURIComponent(next)}`);
  }
  function back() {
    if (history.length > 1) history.back();
    else router.go('/journal');
  }
  function react(postId: string, emoji: string, add: boolean) {
    if (!speaker) return;
    sync.create(add ? 'post.react' : 'post.unreact', sync.accountScope, postId, postReaction(postId, emoji, speaker));
  }
  // a click anywhere on a row opens it, except on its own links and controls
  function rowClick(event: MouseEvent, next: string) {
    if ((event.target as HTMLElement).closest('a, button, details, input, select, textarea, video, audio')) return;
    if (window.getSelection()?.toString()) return;
    open(next);
  }
  function itemNames(item: ThreadItem) {
    return item.local ? item.local.authors.map(name).join(' & ') || 'Someone' : remoteNames(item.remote!);
  }
  function look(item: ThreadItem) {
    const lead = item.local ? people.get(item.local.authors[0] ?? '') : (item.remote?.authors ?? []).map((a) => people.get(a)).find(Boolean);
    const color = core.adaptColor(lead?.color ?? item.remote?.author_cards?.[0]?.color ?? '#A09184', dark);
    return { lead, color, glyph: lead?.sigils[0] ?? itemNames(item)[0] ?? '?' };
  }
  const when = (at: number) => new Date(at).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
  const count = (n: number) => `${n} ${n === 1 ? 'reply' : 'replies'}`;
</script>

{#snippet row(item: ThreadItem, lineBelow: boolean, context: boolean)}
  {@const l = look(item)}
  {@const post = (item.local ?? item.remote)!}
  <!-- clicking the row is a mouse shortcut; keyboards and screen readers open it by its time link -->
  <!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_noninteractive_element_interactions -->
  <article class="row" class:context class:line-below={lineBelow} data-post-id={item.id}
    style="--avatar-bg: {l.color.ring}; --avatar-ink: {l.color.avatar_ink}; --author: {l.color.name}"
    onclick={(event) => rowClick(event, item.id)}>
    <span class="avatar" aria-hidden="true"><AvatarImage hash={l.lead?.avatar_blob} glyph={l.glyph} name={itemNames(item)} /></span>
    <div class="row-body">
      <p class="by"><span class="names">{itemNames(item)}</span> · <a href="#/journal/post/{encodeURIComponent(item.id)}" title="Open this post">{when(post.occurred_at)}</a></p>
      {#if post.cw}
        <details><summary>Content warning: {post.cw}</summary>
          {#if post.title}<strong>{post.title}</strong>{/if}
          <div class="text"><RichText text={post.text} entities={post.entities ?? []} /></div>
        </details>
      {:else}
        {#if post.title}<strong>{post.title}</strong>{/if}
        <div class="text" class:clamp={context}><RichText text={post.text} entities={post.entities ?? []} /></div>
        {#if quoteFor(item)}<QuoteEmbed quote={quoteFor(item)!} />{/if}
        {#if item.remote}
          <RemotePostPoll {projection} post={item.remote} member={speaker} />
          {#each item.remote.attachments ?? [] as attachment (attachment.id)}<AttachmentView {attachment} />{/each}
        {/if}
      {/if}
      {#if !context && item.replyCount}<a class="count" href="#/journal/post/{encodeURIComponent(item.id)}">{count(item.replyCount)}</a>{/if}
    </div>
  </article>
{/snippet}

<section class="thread-page" aria-label="Post thread">
  <header>
    <button class="back" onclick={back} aria-label="Back"><Icon name="chevron" size={20} /></button>
    <h1>{own?.kind === 'entry' ? 'Journal entry' : ancestors.length ? 'Thread' : 'Post'}</h1>
    <button class="refresh" onclick={load} disabled={loading}>{loading ? 'Loading…' : 'Refresh'}</button>
  </header>

  {#if ancestors.length}
    <div class="ancestors">
      {#each ancestors as parent (parent.id)}{@render row(parent, true, true)}{/each}
    </div>
  {/if}

  {#if own}
    <div class="focus" class:joined={ancestors.length > 0}>
      <PostCard {projection} post={own} {people} {dark} reactions={reacts.get(own.id) ?? new Map()} {speaker} inThread full
        quote={quoteView(own.quote_post_id, postById, name, detail?.quoted)}
        replyCount={replies.length} onreply={() => document.getElementById('thread-reply')?.querySelector('textarea')?.focus()}
        onreact={(emoji, add) => react(own.id, emoji, add)} />
    </div>
  {:else if detail}
    <div class="focus remote" class:joined={ancestors.length > 0}>
      {@render row({ id: detail.id, occurred_at: detail.occurred_at, remote: detail, replyCount: replies.length }, false, false)}
    </div>
    <PostHistory id={detail.id} />
  {/if}

  {#if focused}
    <p class="detail">{when(focused.occurred_at)}{#if replies.length}{' · '}<strong>{replies.length}</strong> {replies.length === 1 ? 'reply' : 'replies'}{/if}{#if quotes.length}{' · '}<button class="stat" onclick={() => (showQuotes = true)}><strong>{quotes.length}</strong> {quotes.length === 1 ? 'quote' : 'quotes'}</button>{/if}</p>
    {#if reactionLine}<p class="reactions">{reactionLine}</p>{/if}
    <div class="reply-box" id="thread-reply">
      <p class="replying">Replying to <strong>{focusedNames || 'this post'}</strong></p>
      <!-- another account's reader must be able to read the reply, so it starts server-wide -->
      {#key id}<PostComposer {projection} initialAuthors={speaker ? [speaker] : []} replyTo={id}
        defaultVisibility={own ? 'private' : 'server'} onsent={() => void load()} />{/key}
    </div>
  {/if}

  {#if error}<p class="note" role={own ? undefined : 'alert'}>{error}</p>{/if}

  {#if quotes.length}
    <div class="tabs" role="tablist" aria-label="Under this post">
      <button role="tab" aria-selected={!showQuotes} class:on={!showQuotes} onclick={() => (showQuotes = false)}>Replies{replies.length ? ` · ${replies.length}` : ''}</button>
      <button role="tab" aria-selected={showQuotes} class:on={showQuotes} onclick={() => (showQuotes = true)}>Quotes · {quotes.length}</button>
    </div>
  {/if}
  <div class="replies" aria-label={showQuotes && quotes.length ? 'Quotes' : 'Replies'}>
    {#each listed as reply (reply.id)}
      <div class="reply">
        {#if reply.local}
          <PostCard {projection} post={reply.local} {people} {dark} reactions={reacts.get(reply.id) ?? new Map()} {speaker} inThread={listed === replies} full
            quote={quoteFor(reply)}
            replyCount={reply.replyCount} onreply={() => open(reply.id)} onreact={(emoji, add) => react(reply.id, emoji, add)} />
        {:else}
          {@render row(reply, false, false)}
        {/if}
      </div>
    {:else}
      {#if focused && !loading}<p class="note">No replies yet.</p>{/if}
    {/each}
  </div>
</section>

<style>
  .thread-page { max-width: 760px; margin: 0 auto; display: grid; gap: 0; }
  header { display: flex; align-items: center; gap: var(--s-2); padding-bottom: var(--s-3); border-bottom: 1px solid var(--line); }
  h1 { margin: 0; flex: 1; font-size: var(--fs-lg, 1.25rem); }
  .back { display: grid; place-items: center; width: 40px; height: 40px; border: 0; border-radius: 50%; background: none; color: var(--ink); cursor: pointer; transform: rotate(180deg); }
  .back:hover { background: var(--surface-2); }
  .refresh { border: 0; background: none; color: var(--accent); font: inherit; font-size: var(--fs-sm); cursor: pointer; }
  .refresh:disabled { color: var(--ink-3); cursor: default; }
  p { margin: 0; }

  .row { position: relative; display: grid; grid-template-columns: 40px minmax(0, 1fr); gap: var(--s-3); padding: var(--s-3) var(--s-4); cursor: pointer;
    border-bottom: 1px solid var(--line); animation: arrive .24s cubic-bezier(.16, 1, .3, 1) both; }
  .row:hover { background: color-mix(in srgb, var(--surface) 60%, transparent); }
  .row.context { border-bottom: 0; padding-bottom: var(--s-2); }
  /* the thread line: from under this avatar down to the next one */
  .row.line-below::after { content: ''; position: absolute; left: calc(var(--s-4) + 19px); top: calc(var(--s-3) + 44px); bottom: -4px; width: 2px; border-radius: 1px; background: var(--line); }
  .avatar { display: inline-grid; width: 40px; height: 40px; border-radius: 50%; overflow: hidden; background: var(--avatar-bg); color: var(--avatar-ink); place-items: center; font-weight: 700; }
  .row-body { display: grid; gap: var(--s-1); min-width: 0; }
  .by { color: var(--ink-3); font-size: var(--fs-sm); }
  .names { color: var(--author); font-weight: 600; }
  .by a { color: inherit; text-decoration: none; }
  .by a:hover { text-decoration: underline; }
  .text { white-space: pre-wrap; overflow-wrap: anywhere; }
  .clamp { display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 4; line-clamp: 4; overflow: hidden; color: var(--ink-2); }
  .count { justify-self: start; color: var(--accent); font-size: var(--fs-sm); text-decoration: none; }
  .count:hover { text-decoration: underline; }

  .focus { position: relative; animation: arrive .24s cubic-bezier(.16, 1, .3, 1) both; }
  .focus :global(.body) { font-size: 1.125rem; line-height: 1.55; }
  .focus.joined::before { content: ''; position: absolute; left: calc(var(--s-4) + 19px); top: 0; height: calc(var(--s-4) - 4px); width: 2px; border-radius: 1px; background: var(--line); z-index: 1; }
  .focus.remote .row { cursor: default; }
  .focus.remote .row:hover { background: none; }
  .focus.remote .text { font-size: 1.125rem; line-height: 1.55; }
  .detail, .reactions { padding: var(--s-2) var(--s-4); color: var(--ink-3); font-size: var(--fs-sm); }
  .detail strong { color: var(--ink); }
  .reply-box { display: grid; gap: var(--s-2); padding: var(--s-3) var(--s-4); border-top: 1px solid var(--line); border-bottom: 1px solid var(--line); }
  .replying { color: var(--ink-3); font-size: var(--fs-sm); }
  .replying strong { color: var(--accent); font-weight: 600; }
  .note { padding: var(--s-4); color: var(--ink-3); font-size: var(--fs-sm); }
  .stat { border: 0; background: none; padding: 0; font: inherit; color: inherit; cursor: pointer; }
  .stat:hover { text-decoration: underline; }
  .tabs { display: flex; gap: 4px; margin: var(--s-3) var(--s-4) var(--s-1); padding: 3px; border-radius: 999px; background: var(--surface-2); justify-self: start; }
  .tabs button { border: 0; border-radius: 999px; background: none; color: var(--ink-3); padding: 6px 14px; font: inherit; font-size: var(--fs-sm); cursor: pointer; }
  .tabs button.on { background: var(--surface); color: var(--ink); font-weight: 600; }
  .reply { animation: arrive .24s cubic-bezier(.16, 1, .3, 1) both; }
  @keyframes arrive { from { opacity: 0; transform: translateY(6px); } to { opacity: 1; transform: none; } }
  @media (prefers-reduced-motion: reduce) { .row, .focus, .reply { animation: none; } }
</style>
