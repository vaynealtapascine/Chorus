<script lang="ts">
  import PollCard from './PollCard.svelte';
  import { pollOf } from '../polls';
  import type { Projection } from '../sync/client';
  import { core } from '../core';
  import type { MemberRow } from '../data';
  import { canWriteAs, type PostRow } from '../posts';
  import { sync } from '../sync/client';
  import { postPreview } from '../post-preview';
  import PostHistory from './PostHistory.svelte';
  import PostEdit from './PostEdit.svelte';
  import Icon from './Icon.svelte';
  import RichText from './RichText.svelte';
  import AvatarImage from './AvatarImage.svelte';
  import QuoteEmbed from './QuoteEmbed.svelte';
  import type { QuoteView } from '../post-threads';

  let { post, people, dark, reactions, speaker, full = false, onreply, onreact, projection, replyCount = 0, replyToNames, inThread = false, quote = null }: {
    post: PostRow; people: Map<string, MemberRow>; dark: boolean;
    reactions: Map<string, string[]>; speaker: string | null; full?: boolean;
    onreply: () => void; onreact: (emoji: string, add: boolean) => void;
    /** Replies to this post, shown on its Reply button. */
    replyCount?: number;
    /** Who the post it answers is by, when that post is on this device. */
    replyToNames?: string;
    /** In a thread the post it answers is already shown above it. */
    inThread?: boolean;
    /** What the post quotes (see `quoteView`). */
    quote?: QuoteView | null;
    /** Needed to show a poll (D-081); its totals and own votes live in the projection. */
    projection?: Projection;
  } = $props();
  const poll = $derived(projection ? pollOf(projection, post.id) : null);
  let expanded = $state(false);
  let bodyExpanded = $state(false);
  let editing = $state(false);
  const preview = $derived(postPreview(post.text, post.entities));
  const body = $derived(!full && !bodyExpanded ? preview : post);
  const own = $derived(projection && post.authors.some((id) => canWriteAs(projection, id, sync.accountId)));
  const PALETTE = ['💜', '👍', '🥹', '🎉'];
  const name = (id: string) => people.get(id)?.display_name ?? people.get(id)?.name ?? 'Someone';
  const lead = $derived(people.get(post.authors[0] ?? ''));
  const color = $derived(core.adaptColor(lead?.color ?? '#A09184', dark));
</script>

<article class="post" data-post-id={post.id} style="--author: {color.name}; --avatar-bg: {color.ring}; --avatar-ink: {color.avatar_ink}; --tint: {color.tint}">
  <div class="top">
    <span class="avatar" aria-hidden="true"><AvatarImage hash={lead?.avatar_blob} glyph={lead?.sigils[0] ?? name(post.authors[0] ?? '')[0]} name={name(post.authors[0] ?? '')} /></span>
    <div class="meta"><span class="authors">{#each post.authors as id, i (id)}{#if i} &amp; {/if}<a href="#/profile/{id}">{name(id)}</a>{/each}</span>
      <span class="when">{post.kind === 'entry' ? 'Entry' : 'Note'} · <a href="#/journal/post/{encodeURIComponent(post.id)}" title="Open the thread">{new Date(post.occurred_at).toLocaleString()}</a>{#if post.edited} · edited{/if}</span></div>
    {#if post.visibility.mode !== 'private'}<span class="visibility">{post.visibility.mode === 'buckets' ? 'selected buckets' : post.visibility.mode}</span>{/if}
  </div>
  {#if post.repost_of}<p class="repost"><Icon name="repost" size={16} /> Reposted · <a href="#/journal/post/{encodeURIComponent(post.repost_of)}">Open original post</a></p>{/if}
  {#if post.reply_to && !inThread}<a class="reply-ref" href="#/journal/post/{encodeURIComponent(post.reply_to)}">Replying to {replyToNames ?? 'a post'}</a>{/if}
  {#if post.cw}<button class="cw" aria-expanded={expanded} onclick={() => (expanded = !expanded)}>Content warning: {post.cw} · {expanded ? 'Hide' : 'Show'}</button>{/if}
  {#if !post.cw || expanded}
    {#if post.title}<h3 class="display">{post.title}</h3>{/if}
    <div class="body"><RichText text={body.text} entities={body.entities} />{#if !full && !bodyExpanded && preview.truncated}…{/if}</div>
    {#if !full && preview.truncated}<button class="expand" aria-expanded={bodyExpanded} onclick={() => (bodyExpanded = !bodyExpanded)}>{bodyExpanded ? 'Show less' : 'Show more'}</button>{/if}
    {#if quote}<QuoteEmbed {quote} />{/if}
    {#if poll && projection}<PollCard {projection} parentId={post.id} {poll} member={speaker} />{/if}
    {#if post.mood || post.tags.length}<p class="tags">{#if post.mood}Mood: {post.mood}{/if}{#each post.tags as tag (tag)} <span>#{tag}</span>{/each}</p>{/if}
  {/if}
  <div class="actions">
    {#if own && !post.repost_of}<button onclick={() => (editing = !editing)}>Edit post</button>{/if}
    <button onclick={onreply} aria-label={replyCount ? `Reply (${replyCount} ${replyCount === 1 ? 'reply' : 'replies'})` : 'Reply'}>Reply{replyCount ? ` · ${replyCount}` : ''}</button>
    <a href="#/stage-post/{post.id}">Stage</a>
    {#each PALETTE as emoji (emoji)}
      {@const selected = speaker ? (reactions.get(emoji) ?? []).includes(speaker) : false}
      <button class:selected disabled={!speaker} onclick={() => onreact(emoji, !selected)} aria-label={`${selected ? 'Remove' : 'Add'} ${emoji} reaction`}>{emoji}{reactions.get(emoji)?.length ? ` ${reactions.get(emoji)?.length}` : ''}</button>
    {/each}
  </div>
  {#if editing}<PostEdit {post} onclose={() => (editing = false)} />{/if}
  <PostHistory id={post.id} />
</article>

<style>
  .post { display: grid; gap: var(--s-3); padding: var(--s-4); background: var(--bg); border: 0; border-bottom: 1px solid var(--line); border-radius: 0; min-width: 0; }
  .repost { display: flex; gap: 8px; align-items: center; color: var(--ink-3); }
  .top { display: flex; align-items: center; gap: var(--s-3); }
  .avatar { display: inline-grid; width: 40px; height: 40px; border-radius: 50%; overflow: hidden; flex: none; background: var(--avatar-bg); color: var(--avatar-ink); place-items: center; font-weight: 700; }
  .meta { display: grid; min-width: 0; }
  .authors a { color: var(--author); text-decoration: none; font-weight: 600; }
  .when, .reply-ref, .tags, .visibility { color: var(--ink-3); font-size: var(--fs-xs); }
  .when a { color: inherit; text-decoration: none; }
  .reply-ref { text-decoration: none; }
  .when a:hover, .reply-ref:hover { text-decoration: underline; }
  .reply-ref { justify-self: start; }
  .visibility { margin-left: auto; }
  h3, p { margin: 0; }
  .expand { border: 0; background: none; color: var(--accent); justify-self: start; padding: 8px 0; cursor: pointer; }
  .body { white-space: pre-wrap; overflow-wrap: anywhere; }
  .cw { border: 1px solid var(--line); border-radius: var(--r-sm); background: var(--surface-2); color: var(--ink); padding: var(--s-2); text-align: left; cursor: pointer; }
  .actions { display: flex; flex-wrap: wrap; gap: var(--s-2); align-items: center; }
  .actions button, .actions a { border: 0; background: none; color: var(--ink-2); padding: var(--s-1); font: inherit; font-size: var(--fs-sm); text-decoration: none; cursor: pointer; }
  .actions button.selected { background: var(--accent-soft); border-radius: var(--r-sm); }
</style>
