<script lang="ts">
  // Another account's post in Journal › Friends, framed like PostCard. It isn't in this replica, so
  // (as on Android) it can be replied to, hearted and shared, not reposted or saved.
  import { core } from '../core';
  import { postReaction } from '../posts';
  import { sync, type Projection } from '../sync/client';
  import { router } from '../router.svelte';
  import { entryMeta, fullTimestamp, shortAgo } from '../journal-presentation';
  import { shareText } from '../journal-social';
  import { withHeart, type FriendPost } from '../friends-feed';
  import { authorProfileHref, tagLine } from '../post-threads';
  import AvatarImage from './AvatarImage.svelte';
  import Icon from './Icon.svelte';
  import PostMedia from './PostMedia.svelte';
  import RemotePostPoll from './RemotePostPoll.svelte';
  import RichText from './RichText.svelte';

  let { post = $bindable(), account, projection, speaker, speakerName, dark }: {
    post: FriendPost; account: string; projection: Projection;
    speaker: string | null; speakerName: string; dark: boolean;
  } = $props();
  const NAME_NOTICE = 'chorus.journal.name-notice';
  let expanded = $state(false);
  let notice = $state('');
  let disclose = $state<HTMLDialogElement | undefined>();
  const names = $derived((post.author_cards ?? []).map((a) => a.display_name || a.name || '').filter(Boolean).join(' & ') || account);
  const lead = $derived(post.author_cards?.[0]);
  const color = $derived(core.adaptColor(lead?.color ?? '#A09184', dark));
  // the author's picture and profile, where their system shares that profile with us (D-101)
  const profileHref = $derived(authorProfileHref(post));
  const initial = $derived((names.trim()[0] ?? '?').toUpperCase());
  const tags = $derived(tagLine(post.tags));
  const hearts = $derived((post.reactions ?? []).filter((r) => r.emoji === '💜'));
  const hearted = $derived(!!speaker && hearts.some((r) => r.member_id === speaker));
  const share = $derived(shareText(post.title ?? undefined, post.cw ?? undefined, post.text));
  const threadHref = $derived(`#/journal/post/${encodeURIComponent(post.id)}`);
  const open = () => router.go(`/journal/post/${encodeURIComponent(post.id)}`);

  function heart() {
    if (!speaker) return;
    // another account's readers see who reacted: say so once per device, as for shared own posts
    if (!hearted && !localStorage.getItem(NAME_NOTICE)) { disclose?.showModal(); return; }
    react(!hearted);
  }
  function react(on: boolean) {
    if (!speaker) return;
    sync.create(on ? 'post.react' : 'post.unreact', sync.accountScope, post.id, postReaction(post.id, '💜', speaker));
    post = withHeart(post, speaker, speakerName, on);
  }
  function confirmHeart() {
    localStorage.setItem(NAME_NOTICE, '1');
    disclose?.close();
    react(true);
  }
  async function shareOut() {
    if (!share) return;
    try {
      if (navigator.share) { await navigator.share({ title: post.title ?? undefined, text: share }); return; }
    } catch (e) { if ((e as Error).name === 'AbortError') return; }
    try { await navigator.clipboard.writeText(share); notice = 'Copied'; } catch { notice = 'Could not copy'; }
    setTimeout(() => (notice = ''), 2000);
  }
</script>

<article class="post" data-post-id={post.id} style="--avatar-bg: {color.ring}; --avatar-ink: {color.avatar_ink}">
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
        <div class="actions">
          <button class="act" onclick={open} aria-label={post.reply_count ? `Reply (${post.reply_count})` : 'Reply'}><Icon name="reply" size={18} />{#if post.reply_count}<span>{post.reply_count}</span>{/if}</button>
          <button class="act heart" class:on={hearted} disabled={!speaker} title={hearts.map((r) => r.member_name).join(' · ') || undefined}
            aria-label={speaker ? `${hearted ? 'Remove heart' : 'Heart'} as ${speakerName}${hearts.length ? ` (${hearts.length})` : ''}` : `${hearts.length} hearts`}
            onclick={heart}><Icon name={hearted ? 'heart-filled' : 'heart'} size={18} />{#if hearts.length}<span>{hearts.length}</span>{/if}</button>
          {#if share}<button class="act last" aria-label="Share" onclick={shareOut}><Icon name="share" size={18} /></button>{/if}
          {#if notice}<span class="notice" role="status">{notice}</span>{/if}
        </div>
      {/if}
    </div>
  </div>
</article>

<dialog bind:this={disclose} class="disclose" aria-labelledby="friend-disclose-{post.id}">
  <h2 id="friend-disclose-{post.id}">React as {speakerName}?</h2>
  <p>Post readers can see this member’s name with the reaction. You’ll only be asked once on this device.</p>
  <div class="dialog-actions"><button onclick={() => disclose?.close()}>Cancel</button><button class="primary" onclick={confirmHeart}>Heart</button></div>
</dialog>

<style>
  .post { padding: 10px 16px; background: var(--bg); border-bottom: 1px solid var(--line); min-width: 0; }
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
  .actions { display: flex; align-items: center; margin: 0 -8px; }
  .actions > .act { flex: 1; }
  .actions > .act.last { flex: none; }
  .act { display: inline-flex; align-items: center; gap: 4px; min-height: 36px; padding: 0 8px; border: 0; background: none; color: var(--ink-3); font: inherit; font-size: 13px; cursor: pointer; }
  .act:hover:not(:disabled) { color: var(--accent); }
  .act:disabled { cursor: default; opacity: .6; }
  .act.heart.on, .act.heart:hover:not(:disabled) { color: var(--danger); }
  .notice { color: var(--ink-3); font-size: var(--fs-xs); margin-left: 8px; }
  .disclose { max-width: 360px; border: 1px solid var(--line); border-radius: 16px; background: var(--surface); color: var(--ink); padding: 20px; }
  .disclose::backdrop { background: rgb(0 0 0 / .4); }
  .disclose h2 { margin: 0 0 8px; font-size: 18px; }
  .disclose p { color: var(--ink-2); font-size: var(--fs-sm); line-height: 1.5; }
  .dialog-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 16px; }
  .dialog-actions button { border: 0; border-radius: 999px; padding: 8px 16px; background: none; color: var(--accent); font: inherit; font-weight: 600; cursor: pointer; }
  .dialog-actions .primary { background: var(--accent); color: var(--on-accent, #fff); }
</style>
