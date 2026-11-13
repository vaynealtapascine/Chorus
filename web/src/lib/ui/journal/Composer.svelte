<script lang="ts">
  // The Journal composer (UI24), one model drawn three ways: `quick` (a cell at the top of the
  // timeline and a profile, F6.13), `reply` (a cell under a post on its thread) and `full` (the
  // dialog's page, F6.2). The inline cells keep everything but the text out of sight until focused.
  import { untrack } from 'svelte';
  import Icon from '../Icon.svelte';
  import Face from '../Face.svelte';
  import Sheet from '../Sheet.svelte';
  import Button from '../kit/Button.svelte';
  import Segmented from '../kit/Segmented.svelte';
  import QuoteEmbed from '../QuoteEmbed.svelte';
  import ComposerEditor from './ComposerEditor.svelte';
  import ComposerBars from './ComposerBars.svelte';
  import AuthorChip from './AuthorChip.svelte';
  import AttachmentTray from './AttachmentTray.svelte';
  import PollFields from './PollFields.svelte';
  import { whoOf } from './who';
  import { members } from '../../data';
  import { posts } from '../../posts';
  import { quoteView } from '../../post-threads';
  import { remotePost } from '../../remote-posts.svelte';
  import { unicodeEmoji } from '../../chat-composer';
  import { chord, insert } from '../../format-text';
  import { TextEditing, type Composer } from '../../journal-composer.svelte';
  import type { Projection } from '../../sync/client';

  let { c, projection, surface, dark, authors, replyVisibility, replyNames, autofocus = false, onsent, onexpand, oncancel, ondrafts }: {
    c: Composer; projection: Projection; surface: 'quick' | 'reply' | 'full'; dark: boolean;
    /** who the surface suggests writing as */
    authors?: string[];
    replyVisibility?: 'private' | 'followers' | 'server';
    /** the full composer's "Replying to …" */
    replyNames?: string;
    autofocus?: boolean;
    onsent?: () => void; onexpand?: () => void; oncancel?: () => void; ondrafts?: () => void;
  } = $props();

  $effect.pre(() => {
    const p = projection, a = authors, v = replyVisibility;
    untrack(() => c.attach(p, { authors: a, replyVisibility: v }));
  });
  $effect(() => c.persist());

  const ed = $derived(new TextEditing(c));
  const full = $derived(surface === 'full');
  const reply = $derived(!!c.replyTo);
  // a quote, files or a poll below the words leave the full field two lines, not a hole
  const following = $derived(!!c.draft.quote || c.pending.length > 0 || c.draft.pollOn);

  // the inline cells open on focus and stay open while they hold something
  let root = $state<HTMLFormElement | undefined>();
  let opened = $state(false);
  const revealed = $derived(full || opened || c.hasContent || !!ed.sheet);
  function outside(e: Event) {
    if (full || !opened || !root || root.contains(e.target as Node)) return;
    // a sheet or menu of this cell lies outside it in the page's order of things, but not in the DOM
    if ((e.target as Element | null)?.closest?.('.sheet, .scrim')) return;
    if (!c.hasContent) { opened = false; ed.formatRow = false; }
  }
  function focusOut(e: FocusEvent) {
    const next = e.relatedTarget as Node | null;
    if (next && root?.contains(next)) return;
    if (next && !c.hasContent && !ed.sheet) { opened = false; ed.formatRow = false; }
  }

  const name = $derived(c.firstName);
  const placeholder = $derived(surface === 'reply' ? 'Post your reply' : reply ? 'Reply…' : c.draft.quote ? 'Add a comment…'
    : c.draft.kind === 'entry' ? 'Write your entry…' : surface === 'quick' && name ? `What’s on your mind, ${name}?` : 'What’s on your mind?');
  const postLabel = $derived(c.busy ? 'Posting…' : reply ? 'Reply' : 'Post');

  const postById = $derived(new Map(posts(projection).map((p) => [p.id, p])));
  const memberName = (id: string) => members(projection).find((m) => m.id === id)?.display_name ?? members(projection).find((m) => m.id === id)?.name ?? 'Someone';
  // another account's post is read from the server; quoting it borrows its audience (D-127)
  const quote = $derived(c.draft.quote ? quoteView(c.draft.quote, postById, memberName, postById.has(c.draft.quote) ? null : remotePost(c.draft.quote)) : null);
  const quotingOther = $derived(!!c.draft.quote && !postById.has(c.draft.quote));

  async function post(e?: Event) {
    e?.preventDefault();
    if (!c.canPost) return;
    if (await c.send()) {
      ed.cwOpen = ed.tagsOpen = false;
      if (!full) { opened = false; ed.formatRow = false; (document.activeElement as HTMLElement | null)?.blur?.(); }
      onsent?.();
    }
  }

  // a file dragged over the cell lights it; dropped, it attaches
  let dragDepth = $state(0);
  const hasFiles = (e: DragEvent) => !!e.dataTransfer?.types.includes('Files');
  // a press on a tool opens the box only once its click has landed: opening moves the tools, and a
  // button that moves between press and release never gets its click
  function openOnFocus(e: FocusEvent) { if (!(e.target instanceof HTMLButtonElement)) opened = true; }
  function dragEnter(e: DragEvent) { if (hasFiles(e)) { dragDepth++; opened = true; } }
  function dragLeave(e: DragEvent) { if (hasFiles(e)) dragDepth = Math.max(0, dragDepth - 1); }
  function drop(e: DragEvent) {
    dragDepth = 0;
    if (!e.dataTransfer?.files.length) return;
    e.preventDefault();
    c.addFiles(e.dataTransfer.files);
  }
  function titleKey(e: KeyboardEvent) {
    if (chord(e) === 'post') { e.preventDefault(); void post(); }
  }
  let FormattingSheet = $state<typeof import('../FormattingSheet.svelte').default | null>(null);
  $effect(() => { if (ed.sheet === 'format' && !FormattingSheet) void import('../FormattingSheet.svelte').then((m) => (FormattingSheet = m.default)); });
</script>

<svelte:document onpointerdown={outside} />

{#snippet face()}
  {@const [first, second] = c.authorMembers}
  <span class="face" aria-hidden="true">
    <Face who={whoOf(first)} size={40} {dark} />
    {#if second}<span class="second"><Face who={whoOf(second)} size={18} {dark} /></span>{/if}
  </span>
{/snippet}

{#snippet postButton()}
  <Button variant="primary" size="sm" type="submit" class="post-pill" disabled={!c.canPost}>{postLabel}</Button>
{/snippet}

{#snippet extras()}
  {#if c.restored}<p class="restored">{c.draft.lostFiles ? `${c.draft.lostFiles} ${c.draft.lostFiles === 1 ? 'file needs' : 'files need'} attaching again` : 'Draft restored'} · <button type="button" onclick={() => c.discard()}>Discard</button></p>{/if}
  {#if quote}
    <div class="quoting"><QuoteEmbed {quote} gone={quote.post ? undefined : 'Loading the quoted post…'} />
      <button type="button" class="unquote" aria-label="Remove quote" title="Remove quote" onclick={() => (c.draft.quote = null)}><Icon name="close" size={14} /></button></div>
    {#if quotingOther}<p class="note">Visible to readers of the quoted post.</p>{/if}
  {/if}
  {#if c.pending.length}<AttachmentTray {c} />{/if}
  {#if c.draft.pollOn}<PollFields {c} />{/if}
  {#if ed.showCw}<input class="field" bind:value={c.draft.cw} placeholder="Content warning" aria-label="Content warning" onkeydown={titleKey} />{/if}
  {#if ed.showTags}<div class="pair"><input class="field" bind:value={c.draft.tags} placeholder="Tags, separated by commas" aria-label="Tags" onkeydown={titleKey} /><input class="field" bind:value={c.draft.mood} placeholder="Mood" aria-label="Mood" onkeydown={titleKey} /></div>{/if}
  {#if c.error}<p class="error" role="alert">{c.error}</p>{/if}
{/snippet}

<!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
<form bind:this={root} class="composer {surface}" class:revealed class:drag={dragDepth > 0} onsubmit={post} onfocusin={openOnFocus} onclick={() => (opened = true)} onfocusout={focusOut}
  ondragenter={dragEnter} ondragleave={dragLeave} ondragover={(e) => e.preventDefault()} ondrop={drop} aria-label={reply ? 'Reply' : 'New post'}>
  {#if full}
    <header class="top">
      <button type="button" class="text-btn cancel" onclick={oncancel}>Cancel</button>
      <span class="title">{reply ? 'Reply' : ''}</span>
      {#if !reply}<button type="button" class="text-btn drafts" onclick={ondrafts}>Drafts</button>{/if}
      {@render postButton()}
    </header>
    {#if !reply}<div class="kind"><Segmented quiet label="Kind" value={c.draft.kind} options={[{ id: 'note', label: 'Post' }, { id: 'entry', label: 'Journal entry' }]} onchange={(v) => (c.draft.kind = v)} /></div>{/if}
    <!-- the empty middle focuses the text; the keys already reach it -->
    <!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
    <div class="scroll" onclick={(e) => { if ((e.target as HTMLElement).matches('.scroll, .cell, .main, .replying')) { ed.el?.focus(); ed.el?.setSelectionRange(c.draft.body.length, c.draft.body.length); } }}>
      {#if replyNames}<p class="replying">Replying to <strong>{replyNames}</strong></p>{/if}
      <div class="cell">
        {@render face()}
        <div class="main">
          <div class="author-row"><AuthorChip {c} {dark} /></div>
          {#if c.draft.kind === 'entry' && !reply}<input class="entry-title" bind:value={c.draft.title} placeholder="Title (optional)" aria-label="Entry title" onkeydown={titleKey} />{/if}
          <ComposerEditor {ed} {dark} {placeholder} onpost={post} big roomy={!following} {autofocus} />
          {@render extras()}
        </div>
      </div>
    </div>
    <div class="dock">
      <div class="band"><ComposerBars {ed} part="audience" up saved /></div>
      <ComposerBars {ed} part="format" docked />
      <ComposerBars {ed} part="attach" docked />
    </div>
  {:else}
    <div class="cell">
      {@render face()}
      <div class="main">
        {#if revealed}
          <div class="author-row">
            <AuthorChip {c} {dark} />
            {#if onexpand}<button type="button" class="expand" aria-label="Open the full composer" title="Expand" onclick={onexpand}><Icon name="expand" size={18} /></button>{/if}
          </div>
        {/if}
        {#if revealed && c.draft.kind === 'entry' && !reply}<input class="entry-title" bind:value={c.draft.title} placeholder="Title (optional)" aria-label="Entry title" onkeydown={titleKey} />{/if}
        <div class="line" class:collapsed={surface === 'reply' && !revealed}>
          <ComposerEditor {ed} {dark} {placeholder} onpost={post} />
          {#if surface === 'reply' && !revealed}{@render postButton()}{/if}
        </div>
        {#if revealed || surface === 'quick'}
          {@render extras()}
          {#if ed.formatRow}<div class="format"><ComposerBars {ed} part="format" /></div>{/if}
          <div class="actions">
            <ComposerBars {ed} part="attach" few={!revealed} toggle={revealed}>
              {#snippet trailing()}
                <span class="brk"></span>
                <span class="grow"></span>
                <div class="right">
                  {#if revealed}<button type="button" class="bell" class:on={!!c.draft.alerts} aria-label="Notifications" title="Notifications" onclick={() => (ed.sheet = 'alerts')}>
                    <Icon name={c.draft.alerts === 'none' ? 'bell-off' : 'bell'} size={16} /></button>{/if}
                  <span class="audience"><ComposerBars {ed} part="audience" bell={false} /></span>
                  {@render postButton()}
                </div>
              {/snippet}
            </ComposerBars>
          </div>
        {/if}
      </div>
    </div>
  {/if}
</form>

{#if ed.sheet === 'format' && FormattingSheet}<FormattingSheet value={ed.current()} onchange={ed.apply} onlink={() => ed.openLink()} onclose={() => (ed.sheet = '')} />{/if}
{#if ed.sheet === 'link'}
  <Sheet title="Link" onclose={() => (ed.sheet = '')}>
    <!-- svelte-ignore a11y_autofocus -->
    <input class="field" bind:value={ed.linkUrl} placeholder="https://" aria-label="Link address" inputmode="url" autofocus onkeydown={(e) => e.key === 'Enter' && (e.preventDefault(), ed.addLink())} />
    {#if ed.linkError}<p class="error" role="alert">{ed.linkError}</p>{/if}
    {#snippet footer()}<Button variant="primary" grow onclick={ed.addLink}>Add link</Button>{/snippet}
  </Sheet>
{/if}
{#if ed.sheet === 'emoji'}
  <Sheet title="Emoji" onclose={() => (ed.sheet = '')}>
    <div class="emoji-grid">{#each unicodeEmoji as emoji (emoji)}<button type="button" title={emoji} aria-label={`Insert ${emoji}`} onclick={() => { ed.sheet = ''; ed.act((v) => insert(v, emoji)); }}>{emoji}</button>{/each}</div>
  </Sheet>
{/if}
{#if ed.sheet === 'alerts'}
  <Sheet title="Notify" onclose={() => (ed.sheet = '')}>
    <Segmented label="Notifications" value={c.draft.alerts} options={[{ id: '', label: 'As usual' }, { id: 'delayed', label: 'Random delay' }, { id: 'none', label: 'Don’t notify' }]} onchange={(v) => { c.draft.alerts = v as typeof c.draft.alerts; ed.sheet = ''; }} />
  </Sheet>
{/if}

<style>
  .composer { position: relative; min-width: 0; transition: box-shadow var(--t-fast) var(--ease); }
  /* a dragged file lights the surface it would land in */
  .composer.drag { box-shadow: inset 0 0 0 2px color-mix(in srgb, var(--accent) 55%, transparent); background: color-mix(in srgb, var(--accent-soft) 40%, transparent); }

  /* the inline cells sit in the feed column as a post does (PostCard: 40 px face, 12 px gap) */
  .quick, .reply { background: var(--bg); border-bottom: 1px solid var(--line); }
  .cell { display: grid; grid-template-columns: 40px minmax(0, 1fr); column-gap: var(--s-3); padding: var(--s-3) var(--s-4); }
  .face { position: relative; width: 40px; height: 40px; }
  .second { position: absolute; right: -6px; bottom: -4px; display: grid; padding: 2px; border-radius: 50%; background: var(--bg); }
  .main { display: grid; gap: var(--s-2); min-width: 0; align-content: start; }
  .author-row { display: flex; align-items: center; justify-content: space-between; gap: var(--s-2); min-height: 32px; animation: chorus-fade-in var(--t-fast) var(--ease); }
  .line { display: flex; align-items: flex-start; gap: var(--s-2); min-width: 0; }
  .line > :global(.body-wrap) { flex: 1; }
  .line.collapsed { align-items: center; }
  .expand:focus-visible, .bell:focus-visible, .unquote:focus-visible, .restored button:focus-visible { outline-offset: 0; }
  .expand { display: grid; place-items: center; width: 40px; height: 40px; margin: -4px -8px -4px 0; border: 0; border-radius: 50%; background: none; color: var(--ink-2); cursor: pointer; }
  @media (hover: hover) { .expand:hover { background: var(--surface-2); filter: none; } }
  .format { margin: 0 -8px; }
  .actions > :global(.attach) { margin-left: -10px; }
  /* once open, a hairline sets the tools apart from the words (Twitter's) */
  .revealed .actions { border-top: 1px solid var(--line); padding-top: var(--s-1); }
  .actions :global(.attach) { flex-wrap: wrap; }
  .grow { flex: 1; }
  .right { display: flex; align-items: center; gap: var(--s-2); margin-left: auto; }
  .brk { display: none; }
  /* too narrow for tools, audience and Post on one line: audience, bell and Post take the line above the tools (Android's two bars) */
  .actions { container-type: inline-size; }
  @container (max-width: 490px) {
    .revealed .right { display: contents; }
    .revealed .audience { order: -4; }
    .revealed .bell { order: -3; }
    .revealed .brk { display: block; order: -1; flex: 0 0 100%; height: 0; }
    .revealed .actions :global(.post-pill) { order: -2; margin-left: auto; }
  }
  .audience :global(.chips) { min-height: 0; flex-wrap: nowrap; }
  .bell { display: grid; place-items: center; width: 32px; height: 32px; border: 0; border-radius: 50%; background: var(--surface-2); color: var(--ink-2); cursor: pointer; animation: chorus-fade-in var(--t-fast) var(--ease); }
  .bell.on { background: var(--accent-soft); color: var(--accent); }
  :global(.post-pill) { flex: none; }
  /* a disabled Post still reads as the button: the accent, faded */
  :global(.post-pill:disabled) { background: color-mix(in srgb, var(--accent) 24%, var(--surface-2)); color: color-mix(in srgb, var(--accent) 70%, var(--ink-3)); }

  .field { width: 100%; box-sizing: border-box; min-height: 44px; border: 1px solid transparent; border-radius: var(--r-row); background: var(--surface-2); color: var(--ink);
    padding: 0 var(--s-3); font: inherit; font-size: var(--fs-md); }
  .field:focus { outline: none; border-color: var(--accent); }
  .pair { display: grid; gap: var(--s-2); grid-template-columns: minmax(0, 2fr) minmax(0, 1fr); }
  .quoting { position: relative; }
  .unquote { position: absolute; top: 8px; right: 8px; display: grid; place-items: center; width: 28px; height: 28px; border: 0; border-radius: 50%; background: var(--surface-2); color: var(--ink-2); cursor: pointer; }
  .note, .restored { margin: 0; color: var(--ink-3); font-size: var(--fs-xs); }
  .restored button { border: 0; background: none; padding: 0; color: var(--accent); font: inherit; cursor: pointer; }
  .error { margin: 0; color: var(--danger); font-size: var(--fs-sm); }
  .emoji-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(44px, 1fr)); gap: var(--s-1); }
  .emoji-grid button { height: 44px; border: 0; border-radius: var(--r-sm); background: none; font-size: 24px; cursor: pointer; }
  @media (hover: hover) { .emoji-grid button:hover { background: var(--surface-2); filter: none; } }

  /* the full composer: a page with the top bar above and the bars docked below */
  .full { display: flex; flex-direction: column; height: 100%; min-height: 0; background: var(--bg); }
  .top { display: flex; align-items: center; gap: var(--s-1); min-height: 56px; padding: 0 var(--s-3) 0 var(--s-1); flex: none; }
  .top .title { flex: 1; text-align: center; color: var(--ink-3); font-size: var(--fs-sm); font-weight: 500; }
  .text-btn { min-height: 44px; padding: 0 var(--s-3); border: 0; border-radius: var(--r-row); background: none; font: inherit; font-size: var(--fs-base); font-weight: 500; cursor: pointer; }
  .text-btn:focus-visible { outline-offset: -2px; }
  .cancel { color: var(--accent); }
  .drafts { color: var(--ink-2); font-size: var(--fs-md); }
  .kind { padding: 0 var(--s-4) var(--s-2); flex: none; }
  .kind :global(.segmented) { display: inline-flex; width: 240px; border-radius: var(--r-full); }
  .kind :global(.segmented button) { border-radius: var(--r-full); }
  .scroll { flex: 1; min-height: 0; overflow-y: auto; overscroll-behavior: contain; }
  .full .cell { min-height: 100%; box-sizing: border-box; padding-top: var(--s-2); padding-bottom: var(--s-6); }
  .replying { margin: 0; padding: var(--s-2) var(--s-4) 0 calc(var(--s-4) + 52px); color: var(--ink-3); font-size: var(--fs-sm); }
  .replying strong { color: var(--accent); font-weight: 600; }
  .entry-title { width: 100%; box-sizing: border-box; border: 0; outline: none; background: none; color: var(--ink); padding: var(--s-1) 0 0; font: inherit; font-size: var(--fs-xl); line-height: 28px; font-weight: 700; }
  .entry-title::placeholder { color: var(--ink-3); font-weight: 700; }
  .dock { flex: none; border-top: 1px solid var(--line); background: var(--surface); padding: 0 var(--s-1) env(safe-area-inset-bottom); }
  .band { padding: 0 var(--s-3); }
</style>
