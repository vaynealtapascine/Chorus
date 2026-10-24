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
  import { segmentRich } from '../segments';
  import RichText from './RichText.svelte';
  import AttachmentView from './AttachmentView.svelte';
  import AvatarImage from './AvatarImage.svelte';
  import EmojiImage from './EmojiImage.svelte';
  import { sync, type Revision } from '../sync/client';

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
  }: {
    m: MessageRow;
    cont: boolean;
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
  function placeActions() {
    if (!actionMenu || !actionTrigger) return;
    const r = actionTrigger.getBoundingClientRect();
    const box = actionMenu.getBoundingClientRect();
    actionMenu.style.left = `${Math.max(12, Math.min(r.left, innerWidth - box.width - 12))}px`;
    actionMenu.style.top = `${Math.max(12, Math.min(r.bottom + 4, innerHeight - box.height - 12))}px`;
  }
  const PALETTE = ['💜', '👍', '😂', '🥹', '🎉', '😢', '👀', '🔥'];
  let palette = $state(false);
  let cwExpanded = $state<boolean | null>(null);
  const showBody = $derived(!m.cw || (cwExpanded ?? cwAutoExpand));

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
  <div class="msg deleted" data-message-id={m.id}>
    Message deleted{#if mine}<button class="link" onclick={onrestore}>Restore</button>{/if}
    {#if thread}<button class="link" onclick={onthread}>Open thread · {thread.replyCount} {thread.replyCount === 1 ? 'reply' : 'replies'}</button>{/if}
  </div>
{:else}
  <div class="msg" class:cont class:self={!!speaker && m.authors.includes(speaker)} class:pinned={m.pinned} class:selected data-message-id={m.id}>
    {#if replied}
      <div class="replybar">
        ↪ <span style="color: {color(replied.authors[0] ?? '').name}">{replied.authors.map(nameOf).join(' & ')}</span>
        {#if replied.channel_id !== m.channel_id && replied.channel_name}<span class="elsewhere">in #{replied.channel_name}</span>{/if}
        <span class="snip">{replied.deleted ? 'deleted message' : replied.cw ? `Content warning: ${replied.cw}` : replied.text.slice(0, 80)}</span>
      </div>
    {/if}
    {#if !cont || replied || selecting || m.cw}
      <div class="head">
        {#if selecting}<button class="select-toggle" aria-label={selected ? 'Deselect message' : 'Select message'} aria-pressed={selected} onclick={onselect}>{selected ? '☑' : '□'}</button>{/if}
        <span class="avatars">
          {#each m.authors.slice(0, 3) as a (a)}
            <span class="avatar" style="--ring: {color(a).ring}; --avatar-ink: {color(a).avatar_ink}"><AvatarImage hash={people.get(a)?.avatar_blob} glyph={people.get(a)?.sigils[0] ?? nameOf(a)[0]} name={nameOf(a)} /></span>
          {/each}
        </span>
        <span class="who">
          {#each m.authors as a, i (a)}{#if i}<span class="amp">{' & '}</span>{/if}<span style="color: {color(a).name}">{nameOf(a)}</span>{/each}
        </span>
        <time>{time(m.occurred_at)}</time>
        {#if m.sent_offline}<span class="tag" title="Composed offline, synced later">sent offline</span>{/if}
        {#if m.pinned}<span class="tag">pinned</span>{/if}
        {#if unseen.length}<span class="unseen" title="Not seen by {unseen.join(', ')}" aria-label="Not seen by {unseen.join(', ')}">●</span>{/if}
      </div>
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
            <blockquote><span>{q.authors.map(nameOf).join(' & ')}{q.channel_name ? ` · #${q.channel_name}` : ''}</span><RichText text={q.text} entities={q.entities} emoji={emojiById} />
              {#each q.attachments ?? [] as a (a.id)}<AttachmentView attachment={a} />{/each}
            </blockquote>
          {/each}
        {:else}
          <blockquote>{m.quote.text}</blockquote>
        {/if}
      {/if}
      {#each m.forward_snapshot ?? [] as f (f.message_id)}
        <div class="forward">
          <span class="fwd">Forwarded from {f.authors.map(nameOf).join(' & ')}{f.channel_name ? ` · #${f.channel_name}` : ''}</span>
          <RichText text={f.text} entities={f.entities} emoji={emojiById} />
          {#each f.attachments ?? [] as a (a.id)}<AttachmentView attachment={a} />{/each}
        </div>
      {/each}
      {#if m.segments.length > 1}
        {#each m.segments as s, i (i)}
          {@const seg = segmentRich(m, s)}
          <div class="segment">
            <span class="segment-head">
              <span class="segment-avatars">
                {#each s.authors as author (author)}
                  <span class="segment-avatar" style="--ring: {color(author).ring}" title={nameOf(author)}><AvatarImage hash={people.get(author)?.avatar_blob} glyph={people.get(author)?.sigils[0] ?? nameOf(author)[0]} name={nameOf(author)} /></span>
                {/each}
              </span>
              <span class="who" style="color: {color(s.authors[0] ?? '').name}">{s.authors.map(nameOf).join(' & ')}</span>
            </span>
            <span class="selectable" data-message-offset={s.offset}><RichText text={seg.text} entities={seg.entities} emoji={emojiById} /></span>
          </div>
        {/each}
      {:else if m.text}
        <span class="selectable" data-message-offset="0"><RichText text={m.text} entities={m.entities} emoji={emojiById} /></span>
      {/if}
      {#if m.attachments.length && !m.forward_snapshot?.some((f) => f.attachments?.length)}
        <div class="attachments">{#each m.attachments as a (a.id)}<AttachmentView attachment={a} />{/each}</div>
      {/if}
      {#if m.edited}<button class="edited" onclick={toggleHistory} aria-expanded={!!history}>(edited)</button>{/if}
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
    <button class="action-trigger" bind:this={actionTrigger} popovertarget={menuId} aria-label="Message actions" title="Message actions"><Icon name="more" size={20} /></button>
    <div class="actions" bind:this={actionMenu} id={menuId} popover="auto" ontoggle={(e) => { if (e.newState === 'open') placeActions(); }} role="group" aria-label="Message actions">
      <button onclick={() => runAction(() => (palette = !palette))} title="React" disabled={!speaker}><Icon name="smile" size={20} />React</button>
      <button onclick={() => runAction(onreply)} title="Reply"><Icon name="action-reply" size={20} />Reply</button>
      <button onclick={() => runAction(onreplyin)} title="Reply in another channel"><Icon name="action-reply" size={20} />Reply in another channel</button>
      {#if onreplyprivately}<button onclick={() => runAction(onreplyprivately)} title="Reply privately"><Icon name="chat" size={20} />Reply privately</button>{/if}
      <button onclick={() => runAction(quote)} title="Quote (select text first to quote part)"><Icon name="copy" size={20} />Quote selection or message</button>
      <button onclick={() => runAction(() => onforward(selectedRange() ?? undefined))} title="Forward selection or message"><Icon name="forward" size={20} />Forward</button>
      <button onclick={() => runAction(onselect)} title={selected ? 'Deselect message' : 'Select for bundle'} aria-label={selected ? 'Deselect message' : 'Select for bundle'}><Icon name="select" size={20} />{selected ? 'Deselect' : 'Select for bundle'}</button>
      <button onclick={() => runAction(onthread)} title={thread ? 'Open thread' : 'Start thread'} aria-label={thread ? 'Open thread' : 'Start thread'}><Icon name="action-thread" size={20} />{thread ? 'Open thread' : 'Start thread'}</button>
      <button onclick={() => runAction(onpin)} title={m.pinned ? 'Unpin' : 'Pin'}><Icon name="action-pin" size={20} />{m.pinned ? 'Unpin' : 'Pin'}</button>
      {#if mine}
        <button onclick={() => runAction(onedit)} title="Edit"><Icon name="action-edit" size={20} />Edit</button>
        <button class="delete-action" onclick={() => runAction(ondelete)} title="Delete"><Icon name="delete" size={20} />Delete</button>
      {/if}
    </div>
    {/if}
  </div>
{/if}

<style>
  .cw-toggle { display: block; margin: var(--s-2) 0; padding: var(--s-2) var(--s-3); border: 1px solid var(--line); border-radius: var(--r-sm); background: var(--surface-2); color: var(--ink); cursor: pointer; text-align: left; font: inherit; }
  .msg {
    position: relative;
    padding: 12px;
    border-radius: 16px;
    background: var(--surface-2);
    max-width: min(90%, 72ch);
    align-self: flex-start;
    min-width: 0;
  }
  .msg.cont { padding-top: 8px; }
  .msg.cont .body { padding-right: 36px; min-height: 36px; }
  .msg.self { align-self: flex-end; background: var(--accent-soft); }
  .head { flex-wrap: wrap; }
  .msg.selected { background: var(--accent-soft); }
  .select-toggle { background: none; border: 0; color: var(--accent); cursor: pointer; }
  blockquote span { display: block; font-size: var(--fs-xs); color: var(--ink-3); }
  .msg:hover {
    background: var(--surface-2);
  }
  .msg.pinned {
    box-shadow: inset 2px 0 0 var(--warn);
  }
  .deleted {
    color: var(--ink-3);
    font-style: italic;
    font-size: var(--fs-sm);
  }
  .link {
    background: none;
    border: 0;
    color: var(--accent);
    cursor: pointer;
    font-style: normal;
    margin-left: var(--s-2);
  }
  .replybar {
    font-size: var(--fs-xs);
    color: var(--ink-3);
    display: flex;
    gap: var(--s-2);
    padding-left: calc(28px + var(--s-2));
    overflow: hidden;
    white-space: nowrap;
  }
  .snip {
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .elsewhere {
    font-style: italic;
  }
  .head {
    display: flex;
    align-items: center;
    gap: var(--s-2);
  }
  .who {
    font-weight: 600;
  }
  .amp {
    color: var(--ink-3);
    font-weight: 400;
  }
  time,
  .tag,
  .edited {
    font-size: var(--fs-xs);
    color: var(--ink-3);
  }
  .unseen {
    color: var(--accent, var(--ink-3));
    font-size: var(--fs-xs);
  }
  button.edited {
    background: none;
    border: 0;
    padding: 0 var(--s-1);
    cursor: pointer;
    text-decoration: underline dotted;
  }
  .history {
    margin: var(--s-1) 0;
    padding: var(--s-2) var(--s-3);
    list-style: none;
    border-left: 2px solid var(--line);
    display: grid;
    gap: var(--s-1);
    color: var(--ink-2);
  }
  .tag {
    border: 1px solid var(--line);
    border-radius: var(--r-full);
    padding: 0 var(--s-2);
  }
  .body {
    padding-left: calc(28px + var(--s-2));
  }
  blockquote {
    margin: 2px 0;
    border-left: 3px solid var(--line);
    padding-left: var(--s-3);
    color: var(--ink-2);
    white-space: pre-wrap;
  }
  .forward {
    border: 1px solid var(--line);
    border-radius: var(--r-sm);
    padding: var(--s-2) var(--s-3);
    margin: 2px 0;
    display: grid;
    gap: 2px;
  }
  .fwd {
    font-size: var(--fs-xs);
    color: var(--ink-3);
  }
  .segment {
    display: grid;
    gap: 2px;
    padding: var(--s-1) 0 var(--s-1) var(--s-3);
    border-left: 2px solid var(--line);
    margin-top: var(--s-1);
  }
  .segment-head { display: flex; align-items: center; gap: var(--s-2); font-size: var(--fs-xs); }
  .segment-avatars { display: flex; }
  .segment-avatar {
    width: 20px;
    height: 20px;
    display: grid;
    place-items: center;
    border-radius: 50%;
    background: var(--surface-2);
    box-shadow: 0 0 0 1px var(--ring, var(--line));
  }
  .segment-avatar + .segment-avatar { margin-left: -4px; }
  .avatars {
    display: flex;
  }
  .avatar {
    width: 28px;
    height: 28px;
    flex: none;
    display: grid;
    place-items: center;
    font-size: 14px;
    border-radius: 50%;
    background: var(--ring, var(--surface-2));
    color: var(--avatar-ink, var(--ink));
  }
  .avatars .avatar + .avatar {
    margin-left: -8px;
  }
  .reacts {
    display: flex;
    flex-wrap: wrap;
    gap: var(--s-1);
    margin-top: var(--s-1);
  }
  .react {
    font: inherit;
    font-size: var(--fs-sm);
    background: var(--surface-2);
    border: 1px solid var(--line);
    border-radius: var(--r-full);
    padding: 0 var(--s-2);
    cursor: pointer;
  }
  .react.mine {
    border-color: var(--accent);
    background: var(--accent-soft);
  }
  .react span {
    color: var(--ink-2);
    font-size: var(--fs-xs);
  }
  .thread-preview {
    display: flex;
    align-items: center;
    gap: var(--s-2);
    margin-top: var(--s-2);
    padding: var(--s-1) var(--s-2);
    font: inherit;
    font-size: var(--fs-xs);
    font-weight: 600;
    color: var(--accent);
    background: var(--accent-soft);
    border: 0;
    border-radius: var(--r-full);
    cursor: pointer;
  }
  .thread-avatars { display: flex; }
  .thread-avatar {
    width: 20px;
    height: 20px;
    display: grid;
    place-items: center;
    border-radius: 50%;
    background: var(--surface);
    box-shadow: 0 0 0 1px var(--ring, var(--line));
  }
  .thread-avatar + .thread-avatar { margin-left: -5px; }
  .palette {
    display: flex;
    gap: 2px;
    margin: var(--s-1) 0 0 calc(28px + var(--s-2));
  }
  .palette button {
    background: var(--surface);
    border: 1px solid var(--line);
    border-radius: var(--r-sm);
    cursor: pointer;
    font-size: 18px;
    width: 34px;
    height: 34px;
  }
  .action-trigger { position: absolute; right: 2px; top: 2px; width: 44px; height: 44px; border: 0; border-radius: 50%; background: transparent; color: var(--ink-3); display: grid; place-items: center; cursor: pointer; }
  .head { padding-right: 36px; }
  .actions { position: fixed; inset: auto; margin: 0; width: 280px; max-width: calc(100vw - 24px); max-height: min(70dvh, 560px); overflow-y: auto; color: var(--ink); background: var(--surface); border: 1px solid var(--line); border-radius: 16px; box-shadow: var(--shadow-pop); padding: 6px; }
  .actions:popover-open { display: grid; }
  .actions button { display: flex; gap: 14px; align-items: center; background: none; border: 0; cursor: pointer; min-height: 48px; padding: 10px 12px; border-radius: 10px; color: var(--ink); text-align: left; font-size: var(--fs-md); }
  .actions button :global(.icon) { color: var(--ink-3); }
  .actions button:hover { background: var(--surface-2); }
  .actions button.delete-action, .actions button.delete-action :global(.icon) { color: var(--danger); }
  .held { margin: 0; font-size: var(--fs-sm); color: var(--ink-3); }
</style>
