<script lang="ts">
  // A journal post the way Android's JournalPostFrame reads it: avatar column, names · compact
  // time, the post, then one quiet icon row (reply, repost, heart, save, share) and a ⋯ menu.
  import PollCard from './PollCard.svelte';
  import { pollOf } from '../polls';
  import { sync, type Projection } from '../sync/client';
  import { core } from '../core';
  import { buckets, type MemberRow } from '../data';
  import { bucketAudience } from '../bucket-controls';
  import BucketGlyph from './BucketGlyph.svelte';
  import { canWriteAs, type PostRow } from '../posts';
  import { audienceOf } from '../profile-audience';
  import PostAudienceDialog from './PostAudienceDialog.svelte';
  import { postPreview } from '../post-preview';
  import PostEdit from './PostEdit.svelte';
  import RichText from './RichText.svelte';
  import AvatarImage from './AvatarImage.svelte';
  import QuoteEmbed from './QuoteEmbed.svelte';
  import PostMedia from './PostMedia.svelte';
  import Icon from './Icon.svelte';
  import { opensPost, type QuoteView } from '../post-threads';
  import { bookmarkPayload, repostBy, repostPayload, shareText, type SocialIndex } from '../journal-social';
  import { linkTo } from '../share-links';
  import { entryMeta, fullTimestamp, shortAgo } from '../journal-presentation';
  import { journalCompose } from '../journal-compose.svelte';
  import { router } from '../router.svelte';
  import { memberHandles, shownHandle } from '../handles';

  let { post, people, dark, reactions, speaker, compactEntry = false, onreply, onreact, projection, replyCount = 0, replyToNames, inThread = false, quote = null, social, saved, repostedBy, full = false, quoteCount = 0, onquotes, reactorNames }: {
    post: PostRow; people: Map<string, MemberRow>; dark: boolean;
    reactions: Map<string, string[]>; speaker: string | null;
    /** Names for reactors from other accounts, from the server's read of the post. */
    reactorNames?: Map<string, string>;
    /** In a feed: stop at 300 characters with Show more (an entry opens its thread). */
    compactEntry?: boolean;
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
    /** The list's reposts; without it the card offers no repost (e.g. another account's post). */
    social?: SocialIndex;
    /** This account's saved posts; without it the card offers no save. */
    saved?: Set<string>;
    /** Shown above a reposted original: who reposted it. */
    repostedBy?: string;
    /** A thread's focused post (Android's `full`): larger, whole, with its time, counts and a large action row. */
    full?: boolean;
    /** Posts quoting this one, for the focused post's counts; [onquotes] lists them. */
    quoteCount?: number;
    onquotes?: () => void;
  } = $props();
  const HEART = '💜';
  const NAME_NOTICE = 'chorus.journal.name-notice';
  const poll = $derived(projection ? pollOf(projection, post.id) : null);
  let expanded = $state(false);
  let bodyExpanded = $state(false);
  let menu = $state(false);
  let changingAudience = $state(false);
  // only a post written by one of this account's members can have its audience changed here
  const own = $derived(!!projection && post.authors.some((id) => canWriteAs(projection, id, sync.accountId)));
  let repostMenu = $state(false);
  let notice = $state('');
  let disclose = $state<HTMLDialogElement | undefined>();
  let editing = $state(false);
  // every card stops at 300 characters (owner, D-107); a thread shows its posts whole
  const preview = $derived(postPreview(post.text, post.entities));
  const whole = $derived(full || inThread);
  const previewing = $derived(!whole && !bodyExpanded && preview.truncated);
  const name = (id: string) => people.get(id)?.display_name ?? people.get(id)?.name ?? reactorNames?.get(id) ?? 'Someone';
  const names = $derived(post.authors.map(name).join(' & ') || 'Someone');
  const lead = $derived(people.get(post.authors[0] ?? ''));
  // one author's handle beside their name, as Figma F6.3 (D-126); joint posts show only names
  const handle = $derived(post.authors.length === 1 ? shownHandle(lead, projection ? memberHandles(projection, sync.accountId) : new Map()) : undefined);
  const color = $derived(core.adaptColor(lead?.color ?? '#A09184', dark));
  const hearts = $derived(reactions.get(HEART) ?? []);
  const hearted = $derived(!!speaker && hearts.includes(speaker));
  const reactedBy = $derived([...reactions].flatMap(([emoji, ids]) => ids.map((id) => `${emoji} ${name(id)}`)).join(' · '));
  const reposts = $derived(social?.reposts.get(post.id)?.length ?? 0);
  const ownRepost = $derived(social && speaker ? repostBy(social, post.id, speaker) : undefined);
  const isSaved = $derived(saved?.has(post.id) ?? false);
  const share = $derived(shareText(post.title, post.cw, post.text));
  const detailTime = $derived(new Date(post.occurred_at).toLocaleTimeString(undefined, { timeStyle: 'short' }) + ' · ' +
    new Date(post.occurred_at).toLocaleDateString(undefined, { dateStyle: 'medium' }));
  const iconSize = $derived(full ? 22 : 18);
  const threadHref = $derived(`#/journal/post/${encodeURIComponent(post.id)}`);
  const audienceLabel = $derived(post.visibility.mode === 'private' ? 'Only your system' : post.visibility.mode === 'followers' ? 'Followers'
    : post.visibility.mode === 'buckets' ? projection ? bucketAudience(audienceOf(post.visibility), buckets(projection)) : 'Selected buckets' : 'Everyone on this server');

  function heart() {
    if (!speaker) return;
    // readers of a shared post see the member's name with the reaction: say so once per device
    if (!hearted && post.visibility.mode !== 'private' && !localStorage.getItem(NAME_NOTICE)) { disclose?.showModal(); return; }
    onreact(HEART, !hearted);
  }
  function confirmHeart() {
    localStorage.setItem(NAME_NOTICE, '1');
    disclose?.close();
    onreact(HEART, true);
  }
  function repost() {
    repostMenu = false;
    if (!speaker) return;
    // undoing deletes (tombstones) the repost, never the original
    if (ownRepost) sync.create('post.delete', sync.accountScope, ownRepost.id, {});
    else sync.create('post.create', sync.accountScope, sync.newId(), repostPayload(speaker, post.id));
  }
  function quotePost() {
    repostMenu = false;
    // the Journal's composer takes the post and focuses; quoting is its own post with its own audience
    journalCompose.quote = post.id;
    router.go('/journal');
  }
  function toggleSave() {
    // saving is a private account pref: it syncs to this account's devices and never reaches the author
    sync.create('pref.set', sync.accountScope, null, bookmarkPayload(post.id, !isSaved));
  }
  async function shareOut() {
    menu = false;
    if (!share) return;
    try {
      if (navigator.share) { await navigator.share({ title: post.title, text: share }); return; }
    } catch (e) { if ((e as Error).name === 'AbortError') return; }
    copy();
  }
  /** A link that opens this post and, pasted into a chat, shows it there (D-109). */
  async function copyLink() {
    menu = false;
    try { await navigator.clipboard.writeText(linkTo(location.origin, { kind: 'post', id: post.id })); notice = 'Link copied'; } catch { notice = 'Could not copy'; }
    setTimeout(() => (notice = ''), 2000);
  }
  async function copy() {
    menu = false;
    try { await navigator.clipboard.writeText(share); notice = 'Copied'; } catch { notice = 'Could not copy'; }
    setTimeout(() => (notice = ''), 2000);
  }
  function outside(node: HTMLElement, close: () => void) {
    const onDown = (e: PointerEvent) => { if (!node.contains(e.target as Node)) close(); };
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') close(); };
    document.addEventListener('pointerdown', onDown);
    document.addEventListener('keydown', onKey);
    return { destroy() { document.removeEventListener('pointerdown', onDown); document.removeEventListener('keydown', onKey); } };
  }
</script>

<!-- clicking the card is a mouse shortcut; keyboards and screen readers open it by its time link -->
<!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_noninteractive_element_interactions -->
<article class="post" class:full data-post-id={post.id} style="--author: {color.name}; --avatar-bg: {color.ring}; --avatar-ink: {color.avatar_ink}; --tint: {color.tint}"
  onclick={(event) => { if (!full && !editing && opensPost(event)) router.go(`/journal/post/${encodeURIComponent(post.id)}`); }}>
  {#if repostedBy}<p class="reposted"><Icon name="repost" size={14} />{repostedBy} reposted</p>{/if}
  <div class="frame">
    <a class="avatar" href="#/profile/{post.authors[0] ?? ''}" aria-label="{name(post.authors[0] ?? '')}’s profile"><AvatarImage hash={lead?.avatar_blob} glyph={lead?.sigils[0] ?? name(post.authors[0] ?? '')[0]} name={name(post.authors[0] ?? '')} /></a>
    <div class="identity">
        <span class="authors">{#each post.authors as id, i (id)}{#if i} &amp; {/if}<a href="#/profile/{id}">{name(id)}</a>{/each}</span>
        {#if handle}<span class="handle">@{handle}</span>{/if}
        <span class="when">· <a href={threadHref} title={fullTimestamp(post.occurred_at)}><time datetime={new Date(post.occurred_at).toISOString()}>{shortAgo(post.occurred_at)}</time></a>{#if post.edited} · edited{/if}</span>
        {#if post.visibility.mode === 'private' || post.visibility.mode === 'followers'}<span class="lock" title={audienceLabel} role="img" aria-label={audienceLabel}><Icon name="lock" size={14} /></span>
        {:else if post.visibility.mode === 'buckets'}{@const b = projection ? buckets(projection).find((x) => post.visibility.bucket_ids?.includes(x.id)) : undefined}<span class="audience"><BucketGlyph icon={b?.icon} color={b?.color} size={14} />{audienceLabel}</span>{/if}
    </div>
    <div class="main">
      {#if post.reply_to && !inThread}<a class="reply-ref" href="#/journal/post/{encodeURIComponent(post.reply_to)}">Replying to {replyToNames ?? 'a post'}</a>{/if}
      {#if post.cw}<button class="cw" aria-expanded={expanded} onclick={() => (expanded = !expanded)}><span>Content warning: {post.cw}</span><span class="cw-toggle">{expanded ? 'Hide' : 'Show'}</span></button>{/if}
      {#if !post.cw || expanded}
        {#if post.title}<h3 class="display">{post.title}</h3>{/if}
        {#if post.text}<div class="body">{#if previewing}<RichText text={preview.text} entities={preview.entities} />…{:else}<RichText text={post.text} entities={post.entities} />{/if}</div>{/if}
        {#if !whole && preview.truncated}
          {#if compactEntry && post.kind === 'entry' && !bodyExpanded}<a class="expand" href={threadHref}>Show more</a>
          {:else}<button class="expand" aria-expanded={bodyExpanded} onclick={() => (bodyExpanded = !bodyExpanded)}>{bodyExpanded ? 'Show less' : 'Show more'}</button>{/if}
        {/if}
        {#if post.kind === 'entry'}<p class="meta-line">{entryMeta(post.text, post.tags)}</p>{/if}
        {#if post.mood}<p class="mood">{post.mood}</p>{/if}
        {#if post.kind !== 'entry' && post.tags.length}<p class="tags">{#each post.tags as tag (tag)}<span>#{tag}</span>{/each}</p>{/if}
        {#if post.attachments?.length}<PostMedia attachments={post.attachments} who={names} occurredAt={post.occurred_at} />{/if}
        {#if poll && projection}<PollCard {projection} parentId={post.id} {poll} member={speaker} />{/if}
        {#if quote}<QuoteEmbed {quote} />{/if}
        {#if full}
          <p class="detail-time">{detailTime} · {audienceLabel}</p>
          {#if replyCount || reposts || quoteCount || hearts.length}
            <p class="stats">
              {#if replyCount}<span><strong>{replyCount}</strong> {replyCount === 1 ? 'reply' : 'replies'}</span>{/if}
              {#if reposts}<span><strong>{reposts}</strong> {reposts === 1 ? 'repost' : 'reposts'}</span>{/if}
              {#if quoteCount}<button class="stat" onclick={onquotes}><strong>{quoteCount}</strong> {quoteCount === 1 ? 'quote' : 'quotes'}</button>{/if}
              {#if hearts.length}<span title={reactedBy || undefined}><strong>{hearts.length}</strong> {hearts.length === 1 ? 'like' : 'likes'}</span>{/if}
            </p>
          {/if}
        {/if}
      {/if}
      <!-- a content warning hides what it warns about, not the row -->
      <div class="actions" class:large={full}>
          <button class="act" onclick={onreply} aria-label={replyCount ? `Reply (${replyCount} ${replyCount === 1 ? 'reply' : 'replies'})` : 'Reply'}><Icon name="reply" size={iconSize} />{#if replyCount && !full}<span>{replyCount}</span>{/if}</button>
          {#if social}
            <span class="pop-anchor">
              <button class="act" class:on={!!ownRepost} disabled={!speaker} aria-haspopup="menu" aria-expanded={repostMenu} aria-label={ownRepost ? 'Reposted; open repost options' : 'Repost'} onclick={() => (repostMenu = !repostMenu)}><Icon name="repost" size={iconSize} />{#if reposts && !full}<span>{reposts}</span>{/if}</button>
              {#if repostMenu}<div class="menu" role="menu" use:outside={() => (repostMenu = false)}><button role="menuitem" onclick={repost}><Icon name="repost" size={18} />{ownRepost ? 'Undo repost' : 'Repost'}</button><button role="menuitem" onclick={quotePost}><Icon name="edit" size={18} />Quote</button></div>{/if}
            </span>
          {/if}
          <button class="act heart" class:on={hearted} disabled={!speaker} title={reactedBy || undefined}
            aria-label={speaker ? `${hearted ? 'Remove heart' : 'Heart'} as ${name(speaker)}${hearts.length ? ` (${hearts.length})` : ''}` : `${hearts.length} hearts`}
            onclick={heart}><Icon name={hearted ? 'heart-filled' : 'heart'} size={iconSize} />{#if hearts.length && !full}<span>{hearts.length}</span>{/if}</button>
          {#if saved}<button class="act" class:on={isSaved} aria-pressed={isSaved} aria-label={isSaved ? 'Remove from saved' : 'Save'} onclick={toggleSave}><Icon name={isSaved ? 'bookmark-filled' : 'bookmark'} size={iconSize} /></button>{/if}
          {#if share}<button class="act last" aria-label="Share" onclick={shareOut}><Icon name="share" size={iconSize} /></button>{/if}
          {#if notice}<span class="notice" role="status">{notice}</span>{/if}
      </div>
    </div>
    <span class="pop-anchor overflow">
      <button class="more" aria-haspopup="menu" aria-expanded={menu} aria-label="More options for this post" onclick={() => (menu = !menu)}><Icon name="more" size={18} /></button>
      {#if menu}<div class="menu right" role="menu" use:outside={() => (menu = false)}>
        {#if !inThread}<a role="menuitem" href={threadHref} onclick={() => (menu = false)}>Open post</a>{/if}
        <button role="menuitem" onclick={copyLink}>Copy link</button>
        {#if share}<button role="menuitem" onclick={copy}>Copy text</button>{/if}
        {#if own && !post.repost_of}<button role="menuitem" onclick={() => { menu = false; editing = true; }}>Edit post</button>{/if}
        {#if own}<button role="menuitem" onclick={() => { menu = false; changingAudience = true; }}>Change audience</button>{/if}
        <a role="menuitem" href="#/stage-post/{post.id}" onclick={() => (menu = false)}>Stage</a>
      </div>{/if}
    </span>
  </div>
  {#if editing}<PostEdit {post} onclose={() => (editing = false)} />{/if}
</article>

{#if editing}<PostEdit {post} onclose={() => (editing = false)} />{/if}
{#if changingAudience && projection}<PostAudienceDialog {projection} postId={post.id} current={audienceOf(post.visibility)} onclose={() => (changingAudience = false)} />{/if}

<dialog bind:this={disclose} class="disclose" aria-labelledby="disclose-{post.id}">
  <h2 id="disclose-{post.id}">React as {speaker ? name(speaker) : 'this member'}?</h2>
  <p>Post readers can see this member’s name with the reaction. You’ll only be asked once on this device.</p>
  <div class="dialog-actions"><button onclick={() => disclose?.close()}>Cancel</button><button class="primary" onclick={confirmHeart}>Heart</button></div>
</dialog>

<style>
  .post { padding: 10px 16px; background: var(--bg); border-bottom: 1px solid var(--line); min-width: 0; }
  .post:not(.full) { cursor: pointer; }
  .post:not(.full):hover { background: color-mix(in srgb, var(--surface) 60%, var(--bg)); }
  .reposted { display: flex; align-items: center; gap: 6px; margin: 0 0 6px 28px; color: var(--ink-3); font-size: var(--fs-xs); font-weight: 600; }
  /* compact: the avatar column runs beside the post; full: avatar and names, then the post at full width */
  .frame { position: relative; display: grid; grid-template-columns: 40px minmax(0, 1fr); column-gap: 12px; row-gap: 4px; }
  .frame > .avatar { grid-row: 1 / span 2; }
  .frame > .identity { grid-column: 2; grid-row: 1; }
  .frame > .main { grid-column: 2; grid-row: 2; }
  .full { padding: 16px 20px; }
  .full .frame { grid-template-columns: 48px minmax(0, 1fr); row-gap: 12px; align-items: center; }
  .full .frame > .avatar { grid-row: 1; width: 48px; height: 48px; }
  .full .frame > .main { grid-column: 1 / -1; gap: 8px; }
  .full .body { font-size: 16px; line-height: 24px; }
  .full h3 { font-size: 24px; line-height: 30px; }
  .detail-time { color: var(--ink-3); font-size: var(--fs-sm); margin-top: 4px; }
  .stats { display: flex; flex-wrap: wrap; gap: 16px; padding: 12px 0; border-top: 1px solid var(--line); color: var(--ink-3); font-size: var(--fs-sm); }
  .stats strong { color: var(--ink); font-weight: 700; }
  .stat { border: 0; background: none; padding: 0; color: inherit; font: inherit; cursor: pointer; }
  .stat:hover { text-decoration: underline; }
  .actions.large { margin: 0; padding: 4px 0; border-top: 1px solid var(--line); border-bottom: 1px solid var(--line); }
  .actions.large .act { min-height: 44px; }
  .avatar { display: inline-grid; width: 40px; height: 40px; border-radius: 50%; overflow: hidden; background: var(--avatar-bg); color: var(--avatar-ink); place-items: center; font-weight: 700; text-decoration: none; }
  .main { display: grid; gap: 4px; min-width: 0; align-content: start; }
  .identity { display: flex; align-items: center; gap: 4px; min-width: 0; padding-right: 36px; line-height: 20px; }
  .authors { font-weight: 600; font-size: 15px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; min-width: 0; }
  .authors a { color: var(--ink); text-decoration: none; }
  .handle { color: var(--ink-3); font-size: 14px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; min-width: 0; flex: 0 1 auto; }
  .authors a:hover { text-decoration: underline; }
  .when { color: var(--ink-3); font-size: 14px; white-space: nowrap; }
  .when a { color: inherit; text-decoration: none; }
  .when a:hover, .reply-ref:hover { text-decoration: underline; }
  .lock { display: inline-flex; color: var(--ink-3); margin-left: 2px; }
  .audience { color: var(--ink-3); font-size: var(--fs-xs); margin-left: 4px; white-space: nowrap; }
  .reply-ref { color: var(--ink-3); font-size: var(--fs-xs); text-decoration: none; justify-self: start; }
  h3, p { margin: 0; }
  h3 { font-size: 16px; line-height: 22px; }
  .body { white-space: pre-wrap; overflow-wrap: anywhere; font-size: 15px; line-height: 21px; }
  .expand { border: 0; background: none; color: var(--accent); justify-self: start; padding: 4px 0; font: inherit; font-size: var(--fs-sm); font-weight: 600; cursor: pointer; text-decoration: none; }
  .meta-line, .tags { color: var(--ink-3); font-size: var(--fs-xs); }
  .mood { color: var(--ink-2); font-size: var(--fs-xs); }
  .tags { display: flex; flex-wrap: wrap; gap: 8px; color: var(--accent); }
  .cw { display: flex; align-items: center; gap: 8px; min-height: 48px; border: 0; border-radius: 10px; background: var(--surface-2); color: var(--ink-2); padding: 0 10px; text-align: left; font: inherit; font-size: var(--fs-sm); cursor: pointer; }
  .cw span:first-child { flex: 1; }
  .cw-toggle { color: var(--accent); font-weight: 600; }
  /* every action but the last gets an equal cell, so the icons fall at even steps (Android's row) */
  .actions { display: flex; align-items: center; margin: 0 -8px; }
  .actions > .act, .actions > .pop-anchor { flex: 1; }
  .actions > .act.last { flex: none; }
  .act { display: inline-flex; align-items: center; gap: 4px; min-height: 36px; padding: 0 8px; border: 0; background: none; color: var(--ink-3); font: inherit; font-size: 13px; cursor: pointer; }
  .act:hover:not(:disabled) { color: var(--accent); }
  .act:disabled { cursor: default; opacity: .6; }
  .act.on { color: var(--accent); }
  .act.heart.on, .act.heart:hover:not(:disabled) { color: var(--danger); }
  .notice { color: var(--ink-3); font-size: var(--fs-xs); margin-left: 8px; }
  .pop-anchor { position: relative; display: inline-flex; }
  .overflow { position: absolute; top: -5px; right: -8px; }
  .more { display: grid; place-items: center; width: 36px; height: 36px; border: 0; border-radius: 50%; background: none; color: var(--ink-3); cursor: pointer; }
  .more:hover { background: var(--surface-2); }
  .menu { position: absolute; top: 100%; left: 0; z-index: 5; display: grid; min-width: 160px; padding: 6px; border: 1px solid var(--line); border-radius: 12px; background: var(--surface); box-shadow: 0 8px 24px rgb(0 0 0 / .18); }
  .menu.right { left: auto; right: 0; }
  .menu button, .menu a { display: flex; align-items: center; gap: 10px; padding: 10px 12px; border: 0; border-radius: 8px; background: none; color: var(--ink); font: inherit; font-size: var(--fs-sm); text-align: left; text-decoration: none; cursor: pointer; }
  .menu button:hover, .menu a:hover { background: var(--surface-2); }
  .disclose { max-width: 360px; border: 1px solid var(--line); border-radius: 16px; background: var(--surface); color: var(--ink); padding: 20px; }
  .disclose::backdrop { background: rgb(0 0 0 / .4); }
  .disclose h2 { margin: 0 0 8px; font-size: 18px; }
  .disclose p { color: var(--ink-2); font-size: var(--fs-sm); line-height: 1.5; }
  .dialog-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 16px; }
  .dialog-actions button { border: 0; border-radius: 999px; padding: 8px 16px; background: none; color: var(--accent); font: inherit; font-weight: 600; cursor: pointer; }
  .dialog-actions .primary { background: var(--accent); color: var(--on-accent, #fff); }
</style>
