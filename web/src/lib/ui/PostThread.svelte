<script lang="ts">
  // A post as a thread, the way Twitter reads one: what it answers sits above it (oldest first, one
  // avatar line running down into it), then the post itself, larger, then a reply box that says
  // what it answers, then the replies. Each reply opens as a thread of its own and the browser's
  // back returns here. Own posts and replies come from this device (so they work offline); other
  // accounts' come from `GET /posts/{id}`, read through the server's audience check.
  import { core } from '../core';
  import { members, reactions as allReactions, type MemberRow } from '../data';
  import { canWriteAs, postReaction, posts } from '../posts';
  import { authorProfileHref, mergedReactions, opensPost, quoteView, remoteNames, tagLine, threadAncestors, threadQuotes, threadReplies, type PostDetail, type ThreadItem, type ThreadPost } from '../post-threads';
  import QuoteEmbed from './QuoteEmbed.svelte';
  import { apiFetch } from '../http';
  import { apiBase } from '../sync/device';
  import { sync, type Projection } from '../sync/client';
  import { bodyAuthors } from '../front-view';
  import { router } from '../router.svelte';
  import PostMedia from './PostMedia.svelte';
  import { savedPosts, socialIndex } from '../journal-social';
  import AvatarImage from './AvatarImage.svelte';
  import Icon from './Icon.svelte';
  import PostCard from './PostCard.svelte';
  import MenuSheet from './kit/MenuSheet.svelte';
  import MenuRow from './kit/MenuRow.svelte';
  import PollVotes from './PollVotes.svelte';
  import { pollOf } from '../polls';
  import ReplyBox from './journal/ReplyBox.svelte';
  import RemotePostActions from './RemotePostActions.svelte';
  import RemotePostPoll from './RemotePostPoll.svelte';
  import RichText from './RichText.svelte';
  import PostHistory from './PostHistory.svelte';
  import { remotePost } from '../remote-posts.svelte';

  let { projection, id, dark }: { projection: Projection; id: string; dark: boolean } = $props();
  const people = $derived(new Map(members(projection).map((m) => [m.id, m] as [string, MemberRow])));
  const rows = $derived(posts(projection));
  const reacts = $derived(allReactions(projection));
  const social = $derived(socialIndex(rows));
  const saved = $derived(savedPosts(projection.rows.pref ?? {}, sync.accountId));
  const active = $derived(members(projection).filter((m) => canWriteAs(projection, m.id, sync.accountId)));
  const speaker = $derived(bodyAuthors(projection, sync.accountId)[0] || active[0]?.id || null);
  const own = $derived(rows.find((post) => post.id === id && !post.deleted));
  const ownPoll = $derived(own ? pollOf(projection, own.id) : null);

  let detail = $state<PostDetail | null>(null);
  // own posts read their reactions from the replica, which has only ours: friends' come with the server read
  const merged = $derived(mergedReactions(detail, (pid) => reacts.get(pid)));
  let error = $state('');
  let menu = $state(false);
  let historyOpen = $state(false);
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
  // our own post quoting another account's: the server's copy, read once (D-127)
  const quoteFor = (item: ThreadItem) => {
    const id = item.local?.quote_post_id ?? item.remote?.quote?.post_id;
    return quoteView(id, postById, name, item.remote?.quoted ?? (id && !postById.has(id) ? remotePost(id) : null));
  };
  const focused = $derived(own ?? (detail ? detail as ThreadPost : null));
  const focusedNames = $derived(own ? own.authors.map(name).join(' & ') : detail ? remoteNames(detail) : '');

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
  function react(postId: string, emoji: string, add: boolean, as = speaker) {
    if (!as) return;
    sync.create(add ? 'post.react' : 'post.unreact', sync.accountScope, postId, postReaction(postId, emoji, as));
  }
  // a click anywhere on a row opens it, except on its own links and controls
  function rowClick(event: MouseEvent, next: string) {
    if (opensPost(event)) open(next);
  }
  // the reply box's field, or on a phone its bar, which opens the full composer
  const focusReply = () => {
    const box = document.getElementById('thread-reply');
    const field = box?.querySelector('textarea');
    if (field) field.focus(); else box?.querySelector<HTMLButtonElement>('.bar button')?.click();
  };
  // a server copy of a post, usually another account's: their picture and profile only where that
  // profile is shared with us
  function look(post: ThreadPost) {
    const lead = (post.authors ?? []).map((a) => people.get(a)).find(Boolean);
    const card = post.author_cards?.[0];
    const color = core.adaptColor(lead?.color ?? card?.color ?? '#A09184', dark);
    return { color, glyph: lead?.sigils[0] ?? remoteNames(post)[0] ?? '?', blob: lead?.avatar_blob ?? card?.avatar_blob,
      href: lead ? null : authorProfileHref(post) };
  }
  const when = (at: number) => new Date(at).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
</script>

<!-- what a post says, shown at once or behind its content warning: the same either way -->
{#snippet content(item: ThreadItem, post: ThreadPost, context: boolean)}
  {@const tags = tagLine(post.tags)}
  {#if post.title}<strong>{post.title}</strong>{/if}
  <div class="text" class:clamp={context}><RichText text={post.text} entities={post.entities ?? []} /></div>
  {#if quoteFor(item)}<QuoteEmbed quote={quoteFor(item)!} />{/if}
  <RemotePostPoll {projection} {post} member={speaker} />
  {#if post.attachments?.length}<PostMedia attachments={post.attachments} who={remoteNames(post)} occurredAt={post.occurred_at} />{/if}
  {#if tags}<p class="tags">{tags}</p>{/if}
{/snippet}

{#snippet row(item: ThreadItem, lineBelow: boolean, context: boolean)}
  {@const post = item.remote!}
  {@const l = look(post)}
  {@const names = remoteNames(post)}
  <!-- clicking the row is a mouse shortcut; keyboards and screen readers open it by its time link -->
  <!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_noninteractive_element_interactions -->
  <article class="row" class:context class:line-below={lineBelow} data-post-id={item.id}
    style="--avatar-bg: {l.color.ring}; --avatar-ink: {l.color.avatar_ink}; --author: {l.color.name}"
    onclick={(event) => rowClick(event, item.id)}>
    {#if l.href}<a class="avatar" href={l.href} aria-label="Open {names}’s profile"><AvatarImage hash={l.blob} glyph={l.glyph} /></a>
    {:else}<span class="avatar" aria-hidden="true"><AvatarImage hash={l.blob} glyph={l.glyph} name={names} /></span>{/if}
    <div class="row-body">
      <p class="by"><span class="names">{names}</span> · <a href="#/journal/post/{encodeURIComponent(item.id)}" title="Open this post">{when(post.occurred_at)}</a></p>
      {#if post.cw}
        <details><summary>Content warning: {post.cw}</summary>{@render content(item, post, false)}</details>
      {:else}
        {@render content(item, post, context)}
      {/if}
      <RemotePostActions post={{ ...post, reply_count: item.replyCount }} {projection} {speaker} speakerName={speaker ? name(speaker) : ''}
        onreply={item.id === id ? focusReply : undefined} />
    </div>
  </article>
{/snippet}

{#if menu}
  <MenuSheet title="Post" onclose={() => (menu = false)}>
    <MenuRow label={loading ? 'Loading…' : 'Refresh'} icon="history" disabled={loading} onclick={() => { menu = false; void load(); }} />
    <MenuRow label="History" icon="clock" onclick={() => { menu = false; historyOpen = true; }} />
  </MenuSheet>
{/if}

<section class="thread-page" aria-label="Post thread">
  <header>
    <button class="back" onclick={back} aria-label="Back"><Icon name="chevron" size={20} /></button>
    <h1>{own?.kind === 'entry' ? 'Journal entry' : ancestors.length ? 'Thread' : 'Post'}</h1>
    <button class="more" aria-haspopup="menu" aria-label="More options" onclick={() => (menu = true)}><Icon name="more-vert" size={20} /></button>
  </header>

  {#if ancestors.length}
    <div class="ancestors">
      {#each ancestors as parent (parent.id)}
        <!-- own parents keep every action, as own replies do -->
        {#if parent.local}
          <div class="own-parent"><PostCard {projection} post={parent.local} {people} {dark} reactions={merged.reactions(parent.id)} reactorNames={merged.names} {speaker} {social} {saved} inThread
            quote={quoteFor(parent)} replyCount={parent.replyCount} onreply={() => open(parent.id)} onreact={(emoji, add, as) => react(parent.id, emoji, add, as)} /></div>
        {:else}{@render row(parent, true, true)}{/if}
      {/each}
    </div>
  {/if}

  {#if own}
    <div class="focus" class:joined={ancestors.length > 0}>
      <PostCard {projection} post={own} {people} {dark} reactions={merged.reactions(own.id)} reactorNames={merged.names} {speaker} {social} {saved} inThread full
        quoteCount={quotes.length} onquotes={() => (showQuotes = true)}
        quote={quoteView(own.quote_post_id, postById, name, detail?.quoted ?? (own.quote_post_id && !postById.has(own.quote_post_id) ? remotePost(own.quote_post_id) : null))}
        replyCount={replies.length} onreply={focusReply}
        onreact={(emoji, add, as) => react(own.id, emoji, add, as)} />
      {#if ownPoll}<PollVotes {projection} parentId={own.id} poll={ownPoll} voter={speaker} />{/if}
    </div>
  {:else if detail}
    <div class="focus remote" class:joined={ancestors.length > 0}>
      {@render row({ id: detail.id, occurred_at: detail.occurred_at, remote: detail, replyCount: replies.length }, false, false)}
    </div>
  {/if}
  {#if historyOpen}<PostHistory {id} open />{/if}

  {#if focused}
    {#if !own}<p class="detail">{when(focused.occurred_at)}{#if replies.length}{' · '}<strong>{replies.length}</strong> {replies.length === 1 ? 'reply' : 'replies'}{/if}{#if quotes.length}{' · '}<button class="stat" onclick={() => (showQuotes = true)}><strong>{quotes.length}</strong> {quotes.length === 1 ? 'quote' : 'quotes'}</button>{/if}</p>{/if}
    <div class="reply-box" id="thread-reply">
      <p class="replying">Replying to <strong>{focusedNames || 'this post'}</strong></p>
      <!-- another account's reader must be able to read the reply, so it starts server-wide -->
      {#key id}<ReplyBox {projection} {dark} replyTo={id} authors={speaker ? [speaker] : []} replyNames={focusedNames || 'this post'}
        replyVisibility={own ? 'private' : 'server'} onsent={() => void load()} />{/key}
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
          <PostCard {projection} post={reply.local} {people} {dark} reactions={merged.reactions(reply.id)} reactorNames={merged.names} {speaker} {social} {saved} inThread={listed === replies}
            quote={quoteFor(reply)}
            replyCount={reply.replyCount} onreply={() => open(reply.id)} onreact={(emoji, add, as) => react(reply.id, emoji, add, as)} />
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
  /* one bordered column, as the Journal's (F6.13) */
  @media (min-width: 641px) { .thread-page { border-inline: 1px solid var(--line); min-height: 100dvh; align-content: start; } }
  @media (min-width: 641px) and (max-width: 1023px) { .thread-page { min-height: calc(100dvh - 72px - env(safe-area-inset-bottom)); } }
  /* the column's first cell, as the Journal's tabs (the page has no padding above it) */
  header { display: flex; align-items: center; gap: var(--s-2); min-height: 56px; padding: 0 var(--s-2); border-bottom: 1px solid var(--line); }
  h1 { margin: 0; flex: 1; font-size: var(--fs-lg, 1.25rem); }
  .back { display: grid; place-items: center; width: 40px; height: 40px; border: 0; border-radius: 50%; background: none; color: var(--ink); cursor: pointer; transform: rotate(180deg); }
  .back:hover { background: var(--surface-2); }
  .more { display: grid; place-items: center; width: 40px; height: 40px; border: 0; border-radius: 50%; background: none; color: var(--ink-2); cursor: pointer; }
  .more:hover { background: var(--surface-2); }
  p { margin: 0; }

  .row { position: relative; display: grid; grid-template-columns: 40px minmax(0, 1fr); gap: var(--s-3); padding: var(--s-3) var(--s-4); cursor: pointer;
    border-bottom: 1px solid var(--line); animation: arrive .24s cubic-bezier(.16, 1, .3, 1) both; }
  .row:hover { background: color-mix(in srgb, var(--surface) 60%, transparent); }
  .row.context { border-bottom: 0; padding-bottom: var(--s-2); }
  /* the thread line: from under this avatar down to the next one */
  .row.line-below::after { content: ''; position: absolute; left: calc(var(--s-4) + 19px); top: calc(var(--s-3) + 44px); bottom: -4px; width: 2px; border-radius: 1px; background: var(--line); }
  .avatar { display: inline-grid; width: 40px; height: 40px; border-radius: 50%; overflow: hidden; background: var(--avatar-bg); color: var(--avatar-ink); place-items: center; font-weight: 700; text-decoration: none; }
  .tags { color: var(--accent); font-size: var(--fs-sm); overflow-wrap: anywhere; }
  .row-body { display: grid; gap: var(--s-1); min-width: 0; }
  .by { color: var(--ink-3); font-size: var(--fs-sm); }
  .names { color: var(--author); font-weight: 600; }
  .by a { color: inherit; text-decoration: none; }
  .by a:hover { text-decoration: underline; }
  .text { white-space: pre-wrap; overflow-wrap: anywhere; }
  .clamp { display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 8; line-clamp: 8; overflow: hidden; }
  .own-parent { position: relative; }
  .own-parent :global(.post) { border-bottom: 0; }
  .own-parent::after { content: ''; position: absolute; left: 35px; top: 54px; bottom: 0; width: 2px; border-radius: 1px; background: var(--line); pointer-events: none; }

  .focus { position: relative; animation: arrive .24s cubic-bezier(.16, 1, .3, 1) both; }
  .focus :global(.body) { font-size: 1.125rem; line-height: 1.55; }
  .focus.joined::before { content: ''; position: absolute; left: calc(var(--s-4) + 19px); top: 0; height: calc(var(--s-4) - 4px); width: 2px; border-radius: 1px; background: var(--line); z-index: 1; }
  .focus.remote .row { cursor: default; }
  .focus.remote .row:hover { background: none; }
  .focus.remote .text { font-size: 1.125rem; line-height: 1.55; }
  .detail { padding: var(--s-2) var(--s-4); color: var(--ink-3); font-size: var(--fs-sm); }
  .detail strong { color: var(--ink); }
  /* the reply cell sits between hairlines under the post, its face in line with the replies' */
  .reply-box { display: grid; border-top: 1px solid var(--line); }
  /* our own post ends at the reply cell's hairline: one line under its actions, not three */
  .focus > :global(.post) { border-bottom: 0; }
  .focus :global(.actions.large) { border-bottom: 0; }
  .replying { padding: var(--s-3) var(--s-4) 0 calc(var(--s-4) + 52px); color: var(--ink-3); font-size: var(--fs-sm); }
  .reply-box :global(.composer.reply .cell) { padding-top: var(--s-2); }
  /* a phone's reply bar lies over the end of the page */
  @media (max-width: 640px) { .thread-page { padding-bottom: 64px; } .replying { display: none; } }
  .replying strong { color: var(--accent); font-weight: 600; }
  .note { padding: var(--s-4); color: var(--ink-3); font-size: var(--fs-sm); }
  .stat { border: 0; background: none; padding: 0; font: inherit; color: inherit; cursor: pointer; }
  .stat:hover { text-decoration: underline; }
  .tabs { display: flex; gap: var(--s-1); margin: var(--s-3) var(--s-4) var(--s-1); padding: 3px; border-radius: var(--r-full); background: var(--surface-2); justify-self: start; }
  .tabs button { border: 0; border-radius: var(--r-full); background: none; color: var(--ink-3); padding: 6px 14px; font: inherit; font-size: var(--fs-sm); cursor: pointer; }
  .tabs button.on { background: var(--surface); color: var(--ink); font-weight: 600; }
  .reply { animation: arrive .24s cubic-bezier(.16, 1, .3, 1) both; }
  @keyframes arrive { from { opacity: 0; transform: translateY(6px); } to { opacity: 1; transform: none; } }
  @media (prefers-reduced-motion: reduce) { .row, .focus, .reply { animation: none; } }
</style>
