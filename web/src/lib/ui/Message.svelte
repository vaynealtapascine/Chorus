<script lang="ts">
  import { core } from '../core';
  import type { EmojiRow, MemberRow, MessageRow, SnapshotItem, TextRange, ThreadSummary } from '../data';
  import { segmentRich } from '../segments';
  import RichText from './RichText.svelte';
  import AttachmentView from './AttachmentView.svelte';
  import AvatarImage from './AvatarImage.svelte';
  import EmojiImage from './EmojiImage.svelte';

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
  }: {
    m: MessageRow;
    cont: boolean;
    people: Map<string, MemberRow>;
    dark: boolean;
    lookup: (id: string) => (MessageRow & { channel_name?: string }) | undefined;
    mine: boolean;
    onreply: () => void;
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

  const PALETTE = ['💜', '👍', '😂', '🥹', '🎉', '😢', '👀', '🔥'];
  let palette = $state(false);
  let cwExpanded = $state<boolean | null>(null);
  const showBody = $derived(!m.cw || (cwExpanded ?? cwAutoExpand));

  const color = (id: string) => core.adaptColor(people.get(id)?.color ?? '#A09184', dark);
  const nameOf = (id: string) => people.get(id)?.display_name ?? people.get(id)?.name ?? 'Someone';
  const time = (t: number) => new Date(t).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
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
  <div class="msg deleted">
    Message deleted{#if mine}<button class="link" onclick={onrestore}>Restore</button>{/if}
    {#if thread}<button class="link" onclick={onthread}>Open thread · {thread.replyCount} {thread.replyCount === 1 ? 'reply' : 'replies'}</button>{/if}
  </div>
{:else}
  <div class="msg" class:cont class:pinned={m.pinned} class:selected>
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
            <span class="avatar" style="--ring: {color(a).ring}"><AvatarImage hash={people.get(a)?.avatar_blob} glyph={people.get(a)?.sigils[0] ?? nameOf(a)[0]} name={nameOf(a)} /></span>
          {/each}
        </span>
        <span class="who">
          {#each m.authors as a, i (a)}{#if i}<span class="amp">{' & '}</span>{/if}<span style="color: {color(a).name}">{nameOf(a)}</span>{/each}
        </span>
        <time>{time(m.occurred_at)}</time>
        {#if m.sent_offline}<span class="tag" title="Composed offline, synced later">sent offline</span>{/if}
        {#if m.pinned}<span class="tag">pinned</span>{/if}
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
      {#if m.edited}<span class="edited"> (edited)</span>{/if}
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
    <div class="actions" role="toolbar" aria-label="Message actions">
      <button onclick={() => (palette = !palette)} title="React" disabled={!speaker}>☺</button>
      <button onclick={onreply} title="Reply">↩</button>
      <button onclick={quote} title="Quote (select text first to quote part)">❝</button>
      <button onclick={() => onforward(selectedRange() ?? undefined)} title="Forward selection or message">↗</button>
      <button onclick={onselect} title={selected ? 'Deselect message' : 'Select for bundle'} aria-label={selected ? 'Deselect message' : 'Select for bundle'}>{selected ? '☑' : '□'}</button>
      <button onclick={onthread} title={thread ? 'Open thread' : 'Start thread'} aria-label={thread ? 'Open thread' : 'Start thread'}>☷</button>
      <button onclick={onpin} title={m.pinned ? 'Unpin' : 'Pin'}>{m.pinned ? '⊘' : '📌'}</button>
      {#if mine}
        <button onclick={onedit} title="Edit">✎</button>
        <button onclick={ondelete} title="Delete">🗑</button>
      {/if}
    </div>
  </div>
{/if}

<style>
  .cw-toggle { display: block; margin: var(--s-2) 0; padding: var(--s-2) var(--s-3); border: 1px solid var(--line); border-radius: var(--r-sm); background: var(--surface-2); color: var(--ink); cursor: pointer; text-align: left; font: inherit; }
  .msg {
    position: relative;
    padding: var(--s-3) var(--s-2) 2px;
    border-radius: var(--r-sm);
  }
  .msg.cont {
    padding-top: 2px;
  }
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
    background: var(--surface-2);
    box-shadow: 0 0 0 2px var(--ring, var(--line)), 0 0 0 3px var(--surface);
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
  .actions {
    position: absolute;
    top: 2px;
    right: var(--s-2);
    display: none;
    gap: 2px;
    background: var(--surface);
    border: 1px solid var(--line);
    border-radius: var(--r-sm);
    padding: 2px;
    box-shadow: var(--shadow-pop);
  }
  .msg:hover .actions,
  .msg:focus-within .actions {
    display: flex;
  }
  .actions button {
    background: none;
    border: 0;
    cursor: pointer;
    width: 28px;
    height: 28px;
    border-radius: 4px;
    color: var(--ink-2);
  }
  .actions button:hover {
    background: var(--surface-2);
  }
</style>
