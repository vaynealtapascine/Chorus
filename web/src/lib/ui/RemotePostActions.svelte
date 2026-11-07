<script lang="ts">
  // The action row on another account's post (a friend's card, a thread), the same five actions as
  // our own (D-127): reply, repost/quote, heart, save, share. The post isn't in this replica, so it
  // can't be reposted or saved (shown disabled); it can be quoted, and the quote borrows its audience.
  import { postReaction } from '../posts';
  import { sync } from '../sync/client';
  import { router } from '../router.svelte';
  import { shareText } from '../journal-social';
  import { withHeart } from '../friends-feed';
  import { journalCompose } from '../journal-compose.svelte';
  import { keepRemote } from '../remote-posts.svelte';
  import type { ThreadPost } from '../post-threads';
  import Icon from './Icon.svelte';

  let { post, speaker, speakerName, onreply }: {
    post: ThreadPost; speaker: string | null; speakerName: string;
    /** What Reply does; it opens the post by default. */
    onreply?: () => void;
  } = $props();
  const NAME_NOTICE = 'chorus.journal.name-notice';
  let notice = $state('');
  let repostMenu = $state(false);
  // a pointer or Escape outside the menu closes it
  function outside(node: HTMLElement) {
    const down = (e: PointerEvent) => { if (!node.contains(e.target as Node)) repostMenu = false; };
    const key = (e: KeyboardEvent) => { if (e.key === 'Escape') repostMenu = false; };
    document.addEventListener('pointerdown', down);
    document.addEventListener('keydown', key);
    return { destroy() { document.removeEventListener('pointerdown', down); document.removeEventListener('keydown', key); } };
  }
  function quote() {
    repostMenu = false;
    keepRemote(post);
    journalCompose.quote = post.id;
    router.go('/journal');
  }
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
  <span class="pop-anchor">
    <button class="act" aria-haspopup="menu" aria-expanded={repostMenu} aria-label="Repost or quote" onclick={() => (repostMenu = !repostMenu)}><Icon name="repost" size={18} /></button>
    {#if repostMenu}<div class="menu" role="menu" use:outside><button role="menuitem" disabled title="Only your own system’s posts can be reposted"><Icon name="repost" size={18} />Repost</button><button role="menuitem" onclick={quote}><Icon name="edit" size={18} />Quote</button></div>{/if}
  </span>
  <button class="act heart" class:on={hearted} disabled={!speaker} title={hearts.map((r) => r.member_name).join(' · ') || undefined}
    aria-label={speaker ? `${hearted ? 'Remove heart' : 'Heart'} as ${speakerName}${hearts.length ? ` (${hearts.length})` : ''}` : `${hearts.length} hearts`}
    onclick={heart}><Icon name={hearted ? 'heart-filled' : 'heart'} size={18} />{#if hearts.length}<span>{hearts.length}</span>{/if}</button>
  <button class="act" aria-label="Save (only your system’s posts)" disabled><Icon name="bookmark" size={18} /></button>
  <button class="act last" aria-label={share ? 'Share' : 'Share (nothing to share)'} disabled={!share} onclick={shareOut}><Icon name="share" size={18} /></button>
  {#if notice}<span class="notice" role="status">{notice}</span>{/if}
</div>

<dialog bind:this={disclose} class="disclose" aria-labelledby="heart-disclose-{post.id}">
  <h2 id="heart-disclose-{post.id}">React as {speakerName}?</h2>
  <p>Post readers can see this member’s name with the reaction. You’ll only be asked once on this device.</p>
  <div class="dialog-actions"><button onclick={() => disclose?.close()}>Cancel</button><button class="primary" onclick={confirmHeart}>Heart</button></div>
</dialog>

<style>
  .actions { display: flex; align-items: center; margin: 0 -8px; }
  .actions > .act, .actions > .pop-anchor { flex: 1; }
  .pop-anchor { position: relative; display: inline-flex; }
  .menu { position: absolute; z-index: 20; top: 100%; left: 0; min-width: 160px; display: grid; padding: 6px; border-radius: 12px; background: var(--surface); box-shadow: var(--shadow-pop); }
  .menu button { display: flex; align-items: center; gap: 10px; border: 0; border-radius: 8px; background: none; color: var(--ink); padding: 8px 10px; font: inherit; text-align: left; cursor: pointer; }
  .menu button:hover:not(:disabled) { background: var(--surface-2); }
  .menu button:disabled { color: var(--ink-3); cursor: default; }
  .actions > .act.last { flex: none; }
  .act { display: inline-flex; align-items: center; gap: 4px; min-height: 36px; padding: 0 8px; border: 0; background: none; color: var(--ink-3); font: inherit; font-size: 13px; cursor: pointer; }
  .act:hover:not(:disabled) { color: var(--accent); }
  .act:disabled { cursor: default; opacity: .4; }
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
