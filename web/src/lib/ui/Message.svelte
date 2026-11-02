<script lang="ts" module>
  import type { MemberColors } from '../core';
  // one formatter and one adapted colour per value for every message on the page (R24)
  const clock = new Intl.DateTimeFormat([], { hour: '2-digit', minute: '2-digit' });
  const adapted = new Map<string, MemberColors>();
</script>

<script lang="ts">
  import { core } from '../core';
  import Icon from './Icon.svelte';
  import type { EmojiRow, MemberRow, MessageRow, SnapshotItem, TextRange, ThreadSummary } from '../data';
  import type { PostRow } from '../posts';
  import { segmentRich } from '../segments';
  import RichText from './RichText.svelte';
  import AttachmentView from './AttachmentView.svelte';
  import AvatarImage from './AvatarImage.svelte';
  import EmojiImage from './EmojiImage.svelte';
  import { sync, type Revision } from '../sync/client';
  import { swipeReply } from '../swipe-reply';
  import { linkTo } from '../share-links';
  import LinkEmbeds from './LinkEmbeds.svelte';

  export type Quote = TextRange;
  export type Forwarded = SnapshotItem;

  let {
    m,
    cont,
    people,
    dark,
    lookup,
    mine,
    onreply,
    onreplyin,
    onreplyprivately,
    unseen = [],
    onquote,
    onedit,
    ondelete,
    onrestore,
    onpin,
    onforward,
    onselect,
    selecting,
    selected,
    thread,
    onthread,
    reacts,
    speaker,
    onreact,
    emojiById,
    cwAutoExpand,
    heldUntil,
    oncancelheld,
    extra,
    last = true,
    tag,
    right,
    onjump,
    postOf,
    embedLookup,
  }: {
    m: MessageRow;
    cont: boolean;
    /** The last of a run from the same authors: it carries their face (Figma F5.21). */
    last?: boolean;
    /** Beside the name: the author's layer when not in the innermost one, or another system's name. */
    tag?: string;
    /** Sits on our side: by one of the room's speakers (D-103); else by whoever speaks now. */
    right?: boolean;
    /** Show a replied-to, quoted or forwarded original: scroll to it, or open its chat at it. */
    onjump?: (target: MessageRow) => void;
    /** What its links point at (D-109): the replica's posts, and messages this reader may see here. */
    postOf?: (id: string) => PostRow | undefined;
    embedLookup?: (id: string) => (MessageRow & { channel_name?: string }) | undefined;
    people: Map<string, MemberRow>;
    dark: boolean;
    lookup: (id: string) => (MessageRow & { channel_name?: string }) | undefined;
    mine: boolean;
    onreply: () => void;
    /** "Reply in…": pick another channel, thread or DM (SPEC §5.3). */
    onreplyin: () => void;
    /** "Reply privately", where there's someone else to reply to. */
    onreplyprivately?: () => void;
    /** Slow mode (D-076): this message waits to be sent until then (ms). */
    heldUntil?: number;
    oncancelheld?: () => void;
    /** Rendered under the body with the message ID (a poll card, D-081). */
    extra?: import('svelte').Snippet<[string]>;
    /** Members who haven't read this yet ("track reading per member"). */
    unseen?: string[];
    onquote: (q: Quote) => void;
    onedit: () => void;
    ondelete: () => void;
    onrestore: () => void;
    onpin: () => void;
    onforward: (range?: TextRange) => void;
    onselect: () => void;
    selecting: boolean;
    selected: boolean;
    thread?: ThreadSummary;
    onthread: () => void;
    reacts: Map<string, string[]> | undefined;
    speaker: string | null;
    onreact: (emoji: string, on: boolean) => void;
    emojiById: Map<string, EmojiRow>;
    cwAutoExpand: boolean;
  } = $props();
  // a held message counts down each second
  let nowMs = $state(Date.now());
  $effect(() => {
    if (!heldUntil) return;
    const t = setInterval(() => (nowMs = Date.now()), 1000);
    return () => clearInterval(t);
  });

  let actionMenu = $state<HTMLDivElement>();
  let actionTrigger = $state<HTMLButtonElement>();
  const menuId = $derived(`message-actions-${m.id}`);
  function runAction(action: () => unknown) { actionMenu?.hidePopover(); action(); }
  /** Where a tap opened the menu; otherwise it hangs from the ⋯ button. */
  let anchor: { left: number; bottom: number } | null = null;
  function placeActions() {
    const r = anchor ?? actionTrigger?.getBoundingClientRect();
    anchor = null;
    if (!actionMenu || !r) return;
    const box = actionMenu.getBoundingClientRect();
    actionMenu.style.left = `${Math.max(12, Math.min(r.left, innerWidth - box.width - 12))}px`;
    actionMenu.style.top = `${Math.max(12, Math.min(r.bottom + 4, innerHeight - box.height - 12))}px`;
  }
  const PALETTE = ['💜', '👍', '😂', '🥹', '🎉', '😢', '👀', '🔥'];
  let palette = $state(false);
  let cwExpanded = $state<boolean | null>(null);
  const showBody = $derived(!m.cw || (cwExpanded ?? cwAutoExpand));
  // the one speaking here writes on the right; this account's other members on the left, named
  // (a message still waiting to sync has no account yet: it's ours)
  const out = $derived(right ?? ((mine || !m.account_id) && (!speaker || m.authors.includes(speaker))));

  const color = (id: string) => {
    const hex = people.get(id)?.color ?? '#A09184';
    const key = `${hex}|${dark}`;
    let c = adapted.get(key);
    if (c === undefined) adapted.set(key, (c = core.adaptColor(hex, dark)));
    return c;
  };
  const nameOf = (id: string) => people.get(id)?.display_name ?? people.get(id)?.name ?? 'Someone';
  const time = (t: number) => clock.format(t);
  // "edited" opens what it said before (SPEC §5.3)
  let history = $state<Revision[] | null>(null);
  const toggleHistory = () => (history = history ? null : sync.revisions(m.id));
  const replied = $derived(m.reply_to ? lookup(m.reply_to) : undefined);
  /** The original of a quote or forward, when this reader can see it here. */
  const original = (id: string | undefined) => (id ? lookup(id) : undefined);
  function jump(e: MouseEvent, target: MessageRow | undefined) {
    if (!target || target.deleted || !onjump || (e.target as Element).closest('a')) return;
    e.stopPropagation();
    onjump(target);
  }
  // where there's no hover, a tap on the bubble opens its actions, which carry the time
  // (owner, 2026-11-02); links, buttons and a text selection keep their own clicks
  function tapBubble(e: MouseEvent) {
    if ((e.target as Element).closest('a, button, input, textarea, video, audio, [role="button"]')) return;
    if (getSelection()?.isCollapsed === false) return;
    if (selecting) { onselect(); return; }
    if (!actionMenu || matchMedia('(hover: hover) and (min-width: 641px)').matches) return;
    anchor = { left: e.clientX, bottom: e.clientY };
    actionMenu.showPopover();
  }
  const when = $derived(new Date(m.occurred_at).toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' }));
  let body: HTMLElement | undefined = $state();

  /** DOM text positions and message offsets are both UTF-16. */
  function selectedRange(): TextRange | null {
    const sel = getSelection();
    if (!sel || !body || !sel.rangeCount || sel.isCollapsed) return null;
    const range = sel.getRangeAt(0);
    const wrapper = (node: Node) => (node instanceof Element ? node : node.parentElement)?.closest<HTMLElement>('[data-message-offset]');
    const start = wrapper(range.startContainer);
    const end = wrapper(range.endContainer);
    if (!start || !end || !body.contains(start) || !body.contains(end)) return null;
    const position = (element: HTMLElement, node: Node, offset: number) => {
      const before = document.createRange();
      before.selectNodeContents(element);
      before.setEnd(node, offset);
      return Number(element.dataset.messageOffset) + before.toString().length;
    };
    const from = position(start, range.startContainer, range.startOffset);
    const to = position(end, range.endContainer, range.endOffset);
    if (to <= from) return null;
    return { message_id: m.id, offset: from, length: to - from, text: m.text.slice(from, to) };
  }

  function quote() {
    onquote(selectedRange() ?? { message_id: m.id, offset: 0, length: m.text.length, text: m.text });
  }
</script>

{#if m.deleted}
  <div class="msg-row" class:out><div class="msg deleted" data-message-id={m.id}>
    Message deleted{#if mine}<button class="link" onclick={onrestore}>Restore</button>{/if}
    {#if thread}<button class="link" onclick={onthread}>Open thread · {thread.replyCount} {thread.replyCount === 1 ? 'reply' : 'replies'}</button>{/if}
  </div></div>
{:else}
  <div class="msg-row" class:out class:cont class:last use:swipeReply={{ enabled: !heldUntil && !selecting, onreply }}>
  <span class="swipe-hint" aria-hidden="true"><Icon name="action-reply" size={18} /></span>
  {#if out}{@render aside()}{/if}
  {#if !out}
    <span class="slot">{#if last}{@const a = m.authors[0] ?? ''}<span class="avatar" style="--ring: {color(a).ring}; --avatar-ink: {color(a).avatar_ink}" title={nameOf(a)}><AvatarImage hash={people.get(a)?.avatar_blob} glyph={people.get(a)?.sigils[0] ?? nameOf(a)[0]} name={nameOf(a)} /></span>{/if}</span>
  {/if}
  <!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
  <div class="msg" class:cont class:self={out} class:pinned={m.pinned} class:selected data-message-id={m.id} onclick={tapBubble}>
    {#if !cont || selecting}
      <div class="head">
        {#if selecting}<button class="select-toggle" aria-label={selected ? 'Deselect message' : 'Select message'} aria-pressed={selected} onclick={onselect}>{selected ? '☑' : '□'}</button>{/if}
        {#if m.authors.length > 1}
          <span class="avatars">
            {#each m.authors.slice(0, 3) as a (a)}
              <span class="avatar small" style="--ring: {color(a).ring}; --avatar-ink: {color(a).avatar_ink}"><AvatarImage hash={people.get(a)?.avatar_blob} glyph={people.get(a)?.sigils[0] ?? nameOf(a)[0]} name={nameOf(a)} /></span>
            {/each}
          </span>
        {/if}
        <span class="who">
          {#each m.authors as a, i (a)}{#if i}<span class="amp">{' & '}</span>{/if}<span style="color: {color(a).name}">{nameOf(a)}</span>{/each}
        </span>
        {#if tag}<span class="layer">{tag}</span>{/if}
        {#if m.sent_offline}<span class="tag" title="Composed offline, synced later">sent offline</span>{/if}
        {#if m.pinned}<span class="tag pin-tag" title="Pinned"><Icon name="pin" size={12} /></span>{/if}
      </div>
    {/if}
    {#if replied}
      {@const qc = color(replied.authors[0] ?? '')}
      <button class="reply-quote" style="--q: {qc.name}" disabled={!onjump || replied.deleted} onclick={(e) => jump(e, replied)}
        title={onjump && !replied.deleted ? 'Show the message this replies to' : undefined}>
        <span class="q-name">{replied.authors.map(nameOf).join(' & ')}{#if replied.channel_id !== m.channel_id && replied.channel_name}<span class="elsewhere"> · #{replied.channel_name}</span>{/if}</span>
        <span class="snip">{replied.deleted ? 'Deleted message' : replied.cw ? `Content warning: ${replied.cw}` : replied.text.slice(0, 120)}</span>
      </button>
    {/if}
    {#if m.cw}
      <button class="cw-toggle" aria-expanded={showBody} onclick={() => (cwExpanded = !showBody)}>
        Content warning: {m.cw} · {showBody ? 'Hide content' : 'Show content'}
      </button>
    {/if}
    {#if showBody}<div class="body" bind:this={body}>
      {#if m.quote}
        {#if 'items' in m.quote}
          {#each m.quote.items as q, i (`${q.message_id}:${i}`)}
            {@const src = onjump ? original(q.message_id) : undefined}
            {@const from = `${q.authors.map(nameOf).join(' & ')}${q.channel_name ? ` · #${q.channel_name}` : ''}`}
            <!-- the header button is the keyboard path; the whole quote answers a pointer -->
            <!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_noninteractive_element_interactions -->
            <blockquote class:jumps={!!src} onclick={(e) => jump(e, src)}>
              {#if src}<button class="q-src" onclick={(e) => jump(e, src)} title="Show the quoted message">{from}</button>{:else}<span>{from}</span>{/if}
              <RichText text={q.text} entities={q.entities} emoji={emojiById} />
              {#each q.attachments ?? [] as a (a.id)}<AttachmentView attachment={a} />{/each}
            </blockquote>
          {/each}
        {:else}
          {@const src = onjump ? original(m.quote.message_id) : undefined}
          <!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_noninteractive_element_interactions -->
          <blockquote class:jumps={!!src} onclick={(e) => jump(e, src)}>
            {#if src}<button class="q-src" onclick={(e) => jump(e, src)} title="Show the quoted message">{src.authors.map(nameOf).join(' & ')}</button>{/if}{m.quote.text}
          </blockquote>
        {/if}
      {/if}
      {#each m.forward_snapshot ?? [] as f (f.message_id)}
        {@const src = onjump ? original(f.message_id) : undefined}
        {@const from = `Forwarded from ${f.authors.map(nameOf).join(' & ')}${f.channel_name ? ` · #${f.channel_name}` : ''}`}
        <!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
        <div class="forward" class:jumps={!!src} onclick={(e) => jump(e, src)}>
          {#if src}<button class="fwd q-src" onclick={(e) => jump(e, src)} title="Show the original message">{from}</button>{:else}<span class="fwd">{from}</span>{/if}
          <RichText text={f.text} entities={f.entities} emoji={emojiById} />
          {#each f.attachments ?? [] as a (a.id)}<AttachmentView attachment={a} />{/each}
        </div>
      {/each}
      {#if m.segments.length > 1}
        {#each m.segments as s, i (i)}
          {@const seg = segmentRich(m, s)}
          <div class="segment" style="--q: {color(s.authors[0] ?? '').name}">
            <span class="segment-head">
              <span class="segment-avatars">
                {#each s.authors as author (author)}
                  <span class="segment-avatar" style="--ring: {color(author).ring}; --avatar-ink: {color(author).avatar_ink}" title={nameOf(author)}><AvatarImage hash={people.get(author)?.avatar_blob} glyph={people.get(author)?.sigils[0] ?? nameOf(author)[0]} name={nameOf(author)} /></span>
                {/each}
              </span>
              <span class="who" style="color: {color(s.authors[0] ?? '').name}">{s.authors.map(nameOf).join(' & ')}</span>
            </span>
            <span class="selectable" data-message-offset={s.offset}><RichText text={seg.text} entities={seg.entities} emoji={emojiById} /></span>
          </div>
        {/each}
      {:else if m.text}
        <span class="selectable text" data-message-offset="0"><RichText text={m.text} entities={m.entities} emoji={emojiById} /></span>
      {/if}
      {#if postOf && embedLookup && m.entities.length}<LinkEmbeds text={m.text} entities={m.entities} lookup={embedLookup} {postOf} {nameOf} {onjump} />{/if}
      {#if m.attachments.length && !m.forward_snapshot?.some((f) => f.attachments?.length)}
        <div class="attachments">{#each m.attachments as a (a.id)}<AttachmentView attachment={a} />{/each}</div>
      {/if}
      {@render extra?.(m.id)}
      {#if history}
        <ol class="history" aria-label="Edit history">
          {#each history as r (r.rev)}
            <li>
              <time>{new Date(r.at).toLocaleString([], { dateStyle: 'short', timeStyle: 'short' })}</time>
              {#if r.original}<span class="tag">original</span>{/if}
              <RichText text={r.fields.text ?? ''} entities={r.fields.entities ?? []} emoji={emojiById} />
            </li>
          {/each}
        </ol>
      {/if}
      {#if reacts?.size}
        <div class="reacts">
          {#each [...reacts] as [emoji, who] (emoji)}
            {@const mine = !!speaker && who.includes(speaker)}
            <button class="react" class:mine onclick={() => onreact(emoji, !mine)} title={who.map(nameOf).join(', ')}>
              {#if emoji.startsWith('custom:') && emojiById.has(emoji.slice(7))}
                {@const custom = emojiById.get(emoji.slice(7))!}
                <EmojiImage hash={custom.blob_hash} name={custom.name} />
              {:else}{emoji}{/if} <span>{who.length}</span>
            </button>
          {/each}
        </div>
      {/if}
      {#if thread}
        <button class="thread-preview" onclick={onthread}>
          <span class="thread-avatars">
            {#each thread.lastRepliers as id (id)}
              <span class="thread-avatar" style="--ring: {color(id).ring}" title={nameOf(id)}><AvatarImage hash={people.get(id)?.avatar_blob} glyph={people.get(id)?.sigils[0] ?? nameOf(id)[0]} name={nameOf(id)} /></span>
            {/each}
          </span>
          <span>{thread.replyCount ? `${thread.replyCount} ${thread.replyCount === 1 ? 'reply' : 'replies'}` : 'Thread started'} · Open thread</span>
        </button>
      {/if}
    </div>{/if}
    {#if unseen.length || out}
      <div class="meta">
        {#if unseen.length}<span class="unseen" title="Not seen by {unseen.join(', ')}" aria-label="Not seen by {unseen.join(', ')}">●</span>{/if}
        {#if out}<span class="sent" class:pending={!m.account_id} title={m.account_id ? 'Sent' : 'Waiting to sync'}><Icon name={m.account_id ? 'read' : 'history'} size={14} /></span>{/if}
      </div>
    {/if}
    {#if palette}
      <div class="palette" role="listbox" aria-label="React">
        {#each PALETTE as e (e)}
          <button onclick={() => { onreact(e, true); palette = false; }}>{e}</button>
        {/each}
        {#each [...emojiById.values()].filter((e) => !e.deleted) as e (e.id)}
          <button onclick={() => { onreact(`custom:${e.id}`, true); palette = false; }} title={`:${e.name}:`}><EmojiImage hash={e.blob_hash} name={e.name} /></button>
        {/each}
      </div>
    {/if}
    {#if heldUntil}
      <p class="held" role="status">
        Slow mode: sending in {Math.max(0, Math.ceil((heldUntil - nowMs) / 1000))} s
        {#if oncancelheld}<button class="link" onclick={oncancelheld}>Cancel</button>{/if}
      </p>
    {/if}
    <!-- a held message isn't sent yet: only Cancel, nothing covering it (narrow screens) -->
    {#if !heldUntil}
    <button class="action-trigger" bind:this={actionTrigger} popovertarget={menuId} aria-label="Message actions" title="Message actions"><Icon name="more" size={18} /></button>
    <div class="actions" bind:this={actionMenu} id={menuId} popover="auto" ontoggle={(e) => { if (e.newState === 'open') placeActions(); }} role="group" aria-label="Message actions">
      <p class="when"><time datetime={new Date(m.occurred_at).toISOString()}>{when}</time>{m.edited ? ' · edited' : ''}</p>
      <button onclick={() => runAction(() => (palette = !palette))} title="React" disabled={!speaker}><Icon name="smile" size={20} />React</button>
      <button onclick={() => runAction(onreply)} title="Reply"><Icon name="action-reply" size={20} />Reply</button>
      <button onclick={() => runAction(onreplyin)} title="Reply in another channel"><Icon name="action-reply" size={20} />Reply in another channel</button>
      {#if onreplyprivately}<button onclick={() => runAction(onreplyprivately)} title="Reply privately"><Icon name="chat" size={20} />Reply privately</button>{/if}
      <button onclick={() => runAction(quote)} title="Quote (select text first to quote part)"><Icon name="copy" size={20} />Quote selection or message</button>
      <button onclick={() => runAction(() => onforward(selectedRange() ?? undefined))} title="Forward selection or message"><Icon name="forward" size={20} />Forward</button>
      <button onclick={() => runAction(() => navigator.clipboard?.writeText(linkTo(location.origin, { kind: 'message', id: m.id })))} title="Copy a link to this message: it shows as a preview in a chat"><Icon name="copy" size={20} />Copy link</button>
      <button onclick={() => runAction(onselect)} title={selected ? 'Deselect message' : 'Select for bundle'} aria-label={selected ? 'Deselect message' : 'Select for bundle'}><Icon name="select" size={20} />{selected ? 'Deselect' : 'Select for bundle'}</button>
      <button onclick={() => runAction(onthread)} title={thread ? 'Open thread' : 'Start thread'} aria-label={thread ? 'Open thread' : 'Start thread'}><Icon name="action-thread" size={20} />{thread ? 'Open thread' : 'Start thread'}</button>
      <button onclick={() => runAction(onpin)} title={m.pinned ? 'Unpin' : 'Pin'}><Icon name="action-pin" size={20} />{m.pinned ? 'Unpin' : 'Pin'}</button>
      {#if m.edited}<button onclick={() => runAction(toggleHistory)} title="What it said before"><Icon name="history" size={20} />{history ? 'Hide edit history' : 'Edit history'}</button>{/if}
      {#if mine}
        <button onclick={() => runAction(onedit)} title="Edit"><Icon name="action-edit" size={20} />Edit</button>
        <button class="delete-action" onclick={() => runAction(ondelete)} title="Delete"><Icon name="delete" size={20} />Delete</button>
      {/if}
    </div>
    {/if}
  </div>
  {#if !out}{@render aside()}{/if}
  </div>
{/if}

<!-- beside the bubble: "edited" always, the time on hover where there is one (owner, 2026-11-02) -->
{#snippet aside()}
  <span class="aside" class:bare={!m.edited}>
    {#if m.edited}<button class="edited" onclick={toggleHistory} aria-expanded={!!history} title="Edit history">edited</button>{/if}
    <time class="stamp" datetime={new Date(m.occurred_at).toISOString()}>{time(m.occurred_at)}</time>
  </span>
{/snippet}

<style>
  /* Bubbles (Figma F5.21): others on the left with their face beside the last of a run, the one
     speaking here on the right; names open a run, time and a sent tick close each bubble. */
  .msg-row { position: relative; display: flex; align-items: flex-end; gap: 8px; padding: 2px 64px 2px 0; min-width: 0; touch-action: pan-y pinch-zoom; }
  .msg-row:not(.cont) { margin-top: 6px; }
  .msg-row.out { justify-content: flex-end; padding: 2px 0 2px 72px; }
  .slot { flex: none; width: 32px; height: 32px; }
  .slot .avatar { width: 32px; height: 32px; font-size: 13px; font-weight: 700; }
  .msg {
    position: relative;
    display: grid;
    gap: 3px;
    padding: 7px 11px 6px;
    border-radius: 16px 16px 16px 5px;
    background: var(--surface-2);
    min-width: 0;
    max-width: min(100%, 560px);
    font-size: 15px;
    line-height: 20px;
    transition: background var(--t-fast) var(--ease);
  }
  .msg-row:not(.last) .msg { border-bottom-left-radius: 8px; }
  .msg.self { background: var(--bubble-out); border-radius: 16px 16px 5px 16px; }
  .msg-row.out:not(.last) .msg { border-bottom-right-radius: 8px; }
  .msg.selected { box-shadow: 0 0 0 2px var(--accent); }
  .msg.deleted { color: var(--ink-3); font-style: italic; font-size: var(--fs-sm); background: none; border: 1px dashed var(--line); }
  .cw-toggle { display: block; margin: 2px 0; padding: 6px 10px; border: 0; border-radius: 8px; background: color-mix(in srgb, var(--ink) 7%, transparent); color: var(--ink); cursor: pointer; text-align: left; font: inherit; font-size: 14px; }
  .select-toggle { background: none; border: 0; color: var(--accent); cursor: pointer; padding: 0; }
  .link { background: none; border: 0; color: var(--accent); cursor: pointer; font-style: normal; margin-left: var(--s-2); }
  .head { display: flex; align-items: center; gap: 6px; min-width: 0; padding-right: 22px; }
  .who { font-weight: 600; font-size: 13px; line-height: 17px; min-width: 0; overflow-wrap: anywhere; }
  .amp { color: var(--ink-3); font-weight: 400; }
  .layer { font-size: 12px; color: var(--ink-3); white-space: nowrap; }
  .tag { display: inline-flex; align-items: center; font-size: 11px; color: var(--ink-3); border: 1px solid var(--line); border-radius: var(--r-full); padding: 0 6px; }
  .pin-tag { border: 0; padding: 0; color: var(--warn); }
  .reply-quote { display: grid; padding: 4px 10px 4px 9px; border: 0; border-left: 3px solid var(--q); border-radius: 6px; background: color-mix(in srgb, var(--q) 12%, transparent);
    font: inherit; font-size: 13px; line-height: 17px; color: inherit; text-align: left; min-width: 0; width: 100%; transition: background var(--t-fast) var(--ease); }
  .reply-quote:not(:disabled) { cursor: pointer; }
  .reply-quote:not(:disabled):hover { background: color-mix(in srgb, var(--q) 22%, transparent); }
  .q-name { color: var(--q); font-weight: 600; }
  .snip { color: var(--ink-3); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .elsewhere { font-weight: 400; color: var(--ink-3); }
  .body { display: grid; gap: 4px; min-width: 0; overflow-wrap: anywhere; }
  .text { white-space: pre-wrap; }
  blockquote { margin: 2px 0; border-left: 3px solid var(--line); padding-left: var(--s-3); color: var(--ink-2); white-space: pre-wrap; }
  blockquote span { display: block; font-size: var(--fs-xs); color: var(--ink-3); }
  .forward { border-left: 3px solid var(--accent); border-radius: 6px; padding: 4px 10px; margin: 2px 0; display: grid; gap: 2px; background: color-mix(in srgb, var(--accent) 10%, transparent); }
  .fwd { font-size: 12px; color: var(--accent); font-weight: 600; }
  /* a quote or forward whose original is here: tap it to go there */
  .jumps { cursor: pointer; }
  .q-src { display: block; background: none; border: 0; padding: 0; font: inherit; font-size: var(--fs-xs); color: var(--ink-3); text-align: left; cursor: pointer; }
  .q-src.fwd { font-size: 12px; color: var(--accent); font-weight: 600; }
  .q-src:hover { text-decoration: underline; }
  .segment { display: grid; gap: 2px; padding: 2px 0 2px 10px; border-left: 2px solid var(--q, var(--line)); margin-top: 2px; }
  .segment-head { display: flex; align-items: center; gap: var(--s-2); font-size: var(--fs-xs); }
  .segment-avatars, .avatars { display: flex; }
  .segment-avatar, .thread-avatar { width: 20px; height: 20px; display: grid; place-items: center; border-radius: 50%; overflow: hidden; font-size: 11px;
    background: var(--ring, var(--surface)); color: var(--avatar-ink, var(--ink)); box-shadow: 0 0 0 2px var(--surface-2); }
  .segment-avatar + .segment-avatar { margin-left: -4px; }
  .avatar { width: 28px; height: 28px; flex: none; display: grid; place-items: center; overflow: hidden; font-size: 14px; border-radius: 50%;
    background: var(--ring, var(--surface-2)); color: var(--avatar-ink, var(--ink)); }
  .avatar.small { width: 20px; height: 20px; font-size: 11px; box-shadow: 0 0 0 2px var(--surface-2); }
  .avatars .avatar + .avatar { margin-left: -6px; }
  .attachments { display: grid; gap: 4px; margin-top: 2px; }
  .reacts { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 2px; }
  .react { display: inline-flex; align-items: center; gap: 4px; font: inherit; font-size: 13px; line-height: 17px; font-weight: 600; padding: 3px 8px; border: 0; border-radius: 12px; cursor: pointer;
    background: color-mix(in srgb, var(--ink) 8%, transparent); color: var(--ink-2); transition: background var(--t-fast) var(--ease); }
  .react.mine { background: color-mix(in srgb, var(--accent) 18%, transparent); color: var(--accent); }
  .react span { font-size: 13px; }
  .thread-preview { display: flex; align-items: center; gap: var(--s-2); width: fit-content; margin-top: 2px; padding: 4px 10px 4px 6px; font: inherit; font-size: 12px; font-weight: 600;
    color: var(--accent); background: color-mix(in srgb, var(--accent) 14%, transparent); border: 0; border-radius: var(--r-full); cursor: pointer; }
  .thread-avatars { display: flex; }
  .thread-avatar + .thread-avatar { margin-left: -5px; }
  .meta { display: flex; align-items: center; justify-content: flex-end; gap: 4px; margin-top: -2px; font-size: 11px; line-height: 14px; color: var(--ink-3); }
  /* beside the bubble (owner, 2026-11-02): "edited" stays in sight; the time waits for a hover or
     focus with a mouse, so nothing moves, and lives in the actions a tap opens elsewhere */
  .aside { position: relative; flex: none; display: inline-flex; padding-bottom: 5px; font-size: 11px; line-height: 14px; color: var(--ink-3); }
  .aside.bare { margin-left: -8px; }
  .msg-row.out .aside.bare { margin-left: 0; margin-right: -8px; }
  .stamp { display: none; white-space: nowrap; }
  @media (hover: hover) and (min-width: 641px) {
    .stamp { display: block; position: absolute; bottom: 5px; left: calc(100% + 8px); opacity: 0; pointer-events: none; transition: opacity var(--t-fast) var(--ease); }
    .msg-row.out .stamp { left: auto; right: calc(100% + 8px); }
    .msg-row:hover .stamp, .msg-row:focus-within .stamp { opacity: 1; }
  }
  /* swipe either way to reply (touch): the row follows the finger, the reply mark waits behind */
  .msg-row:global(.swiping) > :not(.swipe-hint), .msg-row:global(.returning) > :not(.swipe-hint) { transform: translateX(var(--swipe, 0px)); }
  .msg-row:global(.returning) > :not(.swipe-hint) { transition: transform var(--t-base) var(--ease); }
  .swipe-hint { position: absolute; top: 50%; right: 8px; width: 32px; height: 32px; margin-top: -16px; display: none; place-items: center; border-radius: 50%;
    background: var(--surface-3); color: var(--accent); opacity: var(--swipe-p, 0); transform: scale(calc(0.5 + 0.5 * var(--swipe-p, 0))); transition: transform var(--t-fast) var(--ease); }
  .msg-row:global(.toward-end) .swipe-hint { right: auto; left: 8px; }
  .msg-row:global(.swiping) .swipe-hint, .msg-row:global(.returning) .swipe-hint { display: grid; }
  .msg-row:global(.armed) .swipe-hint { transform: scale(1.2); }
  .sent { display: inline-grid; color: var(--accent); }
  .sent.pending { color: var(--ink-3); }
  .unseen { color: var(--accent); font-size: 9px; }
  button.edited { background: none; border: 0; padding: 0; font: inherit; font-size: 11px; color: var(--ink-3); cursor: pointer; text-decoration: underline dotted; }
  .history { margin: var(--s-1) 0; padding: var(--s-2) var(--s-3); list-style: none; border-left: 2px solid var(--line); display: grid; gap: var(--s-1); color: var(--ink-2); font-size: var(--fs-sm); }
  .history time { font-size: 11px; color: var(--ink-3); }
  .palette { display: flex; flex-wrap: wrap; gap: 2px; margin-top: 4px; }
  .palette button { background: var(--surface); border: 1px solid var(--line); border-radius: var(--r-sm); cursor: pointer; font-size: 18px; width: 34px; height: 34px; }
  .action-trigger { position: absolute; right: 2px; top: 2px; width: 28px; height: 28px; border: 0; border-radius: 50%; background: transparent; color: var(--ink-3); display: grid; place-items: center; cursor: pointer; }
  .action-trigger:hover { background: color-mix(in srgb, var(--ink) 10%, transparent); color: var(--ink); }
  /* with a mouse, the ⋯ waits for the pointer or keyboard focus, like Telegram's hover menu */
  @media (hover: hover) {
    .action-trigger { opacity: 0; transition: opacity var(--t-fast) var(--ease); }
    .msg:hover .action-trigger, .msg:focus-within .action-trigger, .action-trigger:focus-visible { opacity: 1; }
  }
  .actions { position: fixed; inset: auto; margin: 0; width: 280px; max-width: calc(100vw - 24px); max-height: min(70dvh, 560px); overflow-y: auto; color: var(--ink); background: var(--surface); border: 1px solid var(--line); border-radius: 16px; box-shadow: var(--shadow-pop); padding: 6px; }
  .actions:popover-open { display: grid; }
  .when { margin: 0 0 4px; padding: 6px 12px 8px; border-bottom: 1px solid var(--line); font-size: var(--fs-sm); color: var(--ink-3); }
  .actions button { display: flex; gap: 14px; align-items: center; background: none; border: 0; cursor: pointer; min-height: 44px; padding: 10px 12px; border-radius: 10px; color: var(--ink); text-align: left; font-size: var(--fs-md); }
  .actions button :global(.icon) { color: var(--ink-3); }
  .actions button:hover { background: var(--surface-2); }
  .actions button.delete-action, .actions button.delete-action :global(.icon) { color: var(--danger); }
  .held { margin: 0; font-size: var(--fs-sm); color: var(--ink-3); }
  @media (max-width: 640px) {
    .msg-row { padding-right: 24px; }
    .msg-row.out { padding-left: 40px; }
  }
</style>
