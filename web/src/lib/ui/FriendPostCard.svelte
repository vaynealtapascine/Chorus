<script lang="ts">
  // Another account's post, framed like PostCard: Journal › Friends, shared feeds, People, Search.
  import { core } from '../core';
  import type { Projection } from '../sync/client';
  import { router } from '../router.svelte';
  import { entryMeta, fullTimestamp, shortAgo } from '../journal-presentation';
  import type { FriendPost } from '../friends-feed';
  import { authorProfileHref, opensPost, tagLine } from '../post-threads';
  import AvatarImage from './AvatarImage.svelte';
  import PostMedia from './PostMedia.svelte';
  import RemotePostActions from './RemotePostActions.svelte';
  import RemotePostPoll from './RemotePostPoll.svelte';
  import RichText from './RichText.svelte';

  let { post, account, projection, speaker, speakerName, dark }: {
    post: FriendPost; account: string; projection: Projection;
    speaker: string | null; speakerName: string; dark: boolean;
  } = $props();
  let expanded = $state(false);
  const names = $derived((post.author_cards ?? []).map((a) => a.display_name || a.name || '').filter(Boolean).join(' & ') || account);
  const lead = $derived(post.author_cards?.[0]);
  const color = $derived(core.adaptColor(lead?.color ?? '#A09184', dark));
  // the author's picture and profile, where their system shares that profile with us (D-101)
  const profileHref = $derived(authorProfileHref(post));
  const initial = $derived((names.trim()[0] ?? '?').toUpperCase());
  const tags = $derived(tagLine(post.tags));
  const threadHref = $derived(`#/journal/post/${encodeURIComponent(post.id)}`);
</script>

<!-- clicking the card is a mouse shortcut; keyboards and screen readers open it by its time link -->
<!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_noninteractive_element_interactions -->
<article class="post" data-post-id={post.id} style="--avatar-bg: {color.ring}; --avatar-ink: {color.avatar_ink}"
  onclick={(event) => { if (opensPost(event)) router.go(`/journal/post/${encodeURIComponent(post.id)}`); }}>
  <div class="frame">
    {#if profileHref}<a class="avatar" href={profileHref} aria-label="Open {names}’s profile"><AvatarImage hash={lead?.avatar_blob} glyph={initial} /></a>
    {:else}<span class="avatar" aria-hidden="true"><AvatarImage hash={lead?.avatar_blob} glyph={initial} /></span>{/if}
    <div class="main">
      <div class="identity">
        <span class="authors">{names}</span>
        <span class="when">· <a href={threadHref} title={fullTimestamp(post.occurred_at)}><time datetime={new Date(post.occurred_at).toISOString()}>{shortAgo(post.occurred_at)}</time></a></span>
      </div>
      {#if names !== account}<p class="from">{account}</p>{/if}
      {#if post.reply_to}<a class="reply-ref" href="#/journal/post/{encodeURIComponent(post.reply_to)}">Replying to a post</a>{/if}
      {#if post.cw}<button class="cw" aria-expanded={expanded} onclick={() => (expanded = !expanded)}><span>Content warning: {post.cw}</span><span class="cw-toggle">{expanded ? 'Hide' : 'Show'}</span></button>{/if}
      {#if !post.cw || expanded}
        {#if post.title}<h3 class="display">{post.title}</h3>{/if}
        {#if post.text}<div class="body clamped"><RichText text={post.text} entities={post.entities ?? []} /></div>{/if}
        {#if post.kind === 'entry' || post.text.length > 280}<a class="expand" href={threadHref}>Show more</a>{/if}
        {#if post.kind === 'entry'}<p class="meta-line">{entryMeta(post.text, post.tags ?? [])}</p>{/if}
        {#if post.attachments?.length}<PostMedia attachments={post.attachments} who={names} occurredAt={post.occurred_at} />{/if}
        <RemotePostPoll {projection} {post} member={speaker} />
        {#if tags}<p class="tags">{tags}</p>{/if}
      {/if}
      <RemotePostActions {post} {speaker} {speakerName} />
    </div>
  </div>
</article>

<style>
  .post { padding: 10px 16px; background: var(--bg); border-bottom: 1px solid var(--line); min-width: 0; cursor: pointer; }
  .post:hover { background: color-mix(in srgb, var(--surface) 60%, var(--bg)); }
  .frame { display: grid; grid-template-columns: 40px minmax(0, 1fr); gap: 12px; }
  .avatar { display: inline-grid; width: 40px; height: 40px; border-radius: 50%; overflow: hidden; background: var(--avatar-bg); color: var(--avatar-ink); place-items: center; font-weight: 700; text-decoration: none; }
  .tags { color: var(--accent); font-size: var(--fs-sm); overflow-wrap: anywhere; }
  .main { display: grid; gap: 4px; min-width: 0; align-content: start; }
  .identity { display: flex; align-items: center; gap: 4px; min-width: 0; line-height: 20px; }
  .authors { font-weight: 600; font-size: 15px; color: var(--ink); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; min-width: 0; }
  .when { color: var(--ink-3); font-size: 14px; white-space: nowrap; }
  .when a { color: inherit; text-decoration: none; }
  .when a:hover, .reply-ref:hover { text-decoration: underline; }
  .from, .reply-ref, .meta-line { color: var(--ink-3); font-size: var(--fs-xs); text-decoration: none; justify-self: start; }
  h3, p { margin: 0; }
  h3 { font-size: 16px; line-height: 22px; }
  .body { white-space: pre-wrap; overflow-wrap: anywhere; font-size: 15px; line-height: 21px; }
  .clamped { display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 3; line-clamp: 3; overflow: hidden; }
  .expand { color: var(--accent); justify-self: start; padding: 4px 0; font-size: var(--fs-sm); font-weight: 600; text-decoration: none; }
  .cw { display: flex; align-items: center; gap: 8px; min-height: 48px; border: 0; border-radius: 10px; background: var(--surface-2); color: var(--ink-2); padding: 0 10px; text-align: left; font: inherit; font-size: var(--fs-sm); cursor: pointer; }
  .cw span:first-child { flex: 1; }
  .cw-toggle { color: var(--accent); font-weight: 600; }
</style>
