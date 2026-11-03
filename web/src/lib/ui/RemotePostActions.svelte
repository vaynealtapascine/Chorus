<script lang="ts">
  // The action row on another account's post (a friend's card, a thread): reply, heart and share.
  // The post isn't in this replica, so (as on Android) it can't be reposted or saved.
  import { postReaction } from '../posts';
  import { sync } from '../sync/client';
  import { router } from '../router.svelte';
  import { shareText } from '../journal-social';
  import { withHeart } from '../friends-feed';
  import type { ThreadPost } from '../post-threads';
  import Icon from './Icon.svelte';

  let { post, speaker, speakerName, onreply }: {
    post: ThreadPost; speaker: string | null; speakerName: string;
    /** What Reply does; it opens the post by default. */
    onreply?: () => void;
  } = $props();
  const NAME_NOTICE = 'chorus.journal.name-notice';
  let notice = $state('');
  let disclose = $state<HTMLDialogElement | undefined>();
  // our heart answers at once, before the next read
  let answered = $state<ThreadPost | null>(null);
  $effect(() => { void post; answered = null; });
  const shown = $derived(answered ?? post);
  const hearts = $derived((shown.reactions ?? []).filter((r) => r.emoji === '💜'));
  const hearted = $derived(!!speaker && hearts.some((r) => r.member_id === speaker));
  const share = $derived(shareText(post.title ?? undefined, post.cw ?? undefined, post.text));

  function heart() {
    if (!speaker) return;
    // another account's readers see who reacted: say so once per device, as for shared own posts
    if (!hearted && !localStorage.getItem(NAME_NOTICE)) { disclose?.showModal(); return; }
    react(!hearted);
  }
  function react(on: boolean) {
    if (!speaker) return;
    sync.create(on ? 'post.react' : 'post.unreact', sync.accountScope, post.id, postReaction(post.id, '💜', speaker));
    answered = withHeart(shown, speaker, speakerName, on);
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

<div class="actions">
  <button class="act" onclick={() => (onreply ? onreply() : router.go(`/journal/post/${encodeURIComponent(post.id)}`))} aria-label={post.reply_count ? `Reply (${post.reply_count})` : 'Reply'}><Icon name="reply" size={18} />{#if post.reply_count}<span>{post.reply_count}</span>{/if}</button>
  <button class="act heart" class:on={hearted} disabled={!speaker} title={hearts.map((r) => r.member_name).join(' · ') || undefined}
    aria-label={speaker ? `${hearted ? 'Remove heart' : 'Heart'} as ${speakerName}${hearts.length ? ` (${hearts.length})` : ''}` : `${hearts.length} hearts`}
    onclick={heart}><Icon name={hearted ? 'heart-filled' : 'heart'} size={18} />{#if hearts.length}<span>{hearts.length}</span>{/if}</button>
  {#if share}<button class="act last" aria-label="Share" onclick={shareOut}><Icon name="share" size={18} /></button>{/if}
  {#if notice}<span class="notice" role="status">{notice}</span>{/if}
</div>

<dialog bind:this={disclose} class="disclose" aria-labelledby="heart-disclose-{post.id}">
  <h2 id="heart-disclose-{post.id}">React as {speakerName}?</h2>
  <p>Post readers can see this member’s name with the reaction. You’ll only be asked once on this device.</p>
  <div class="dialog-actions"><button onclick={() => disclose?.close()}>Cancel</button><button class="primary" onclick={confirmHeart}>Heart</button></div>
</dialog>

<style>
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
  .disclose p { margin: 0; color: var(--ink-2); font-size: var(--fs-sm); line-height: 1.5; }
  .dialog-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 16px; }
  .dialog-actions button { border: 0; border-radius: 999px; padding: 8px 16px; background: none; color: var(--accent); font: inherit; font-weight: 600; cursor: pointer; }
  .dialog-actions .primary { background: var(--accent); color: var(--on-accent, #fff); }
</style>
