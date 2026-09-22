<script lang="ts">
  import { core } from '../core';
  import type { MemberRow, MessageRow } from '../data';
  import RichText from './RichText.svelte';

  export interface Quote {
    message_id: string;
    offset: number;
    length: number;
    text: string;
  }
  export interface Forwarded {
    message_id: string;
    channel_name?: string;
    authors: string[];
    text: string;
    entities: MessageRow['entities'];
    occurred_at: number;
  }

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
    reacts,
    speaker,
    onreact,
  }: {
    m: MessageRow & { reply_to?: string; quote?: Quote; forward_snapshot?: Forwarded[] };
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
    onforward: () => void;
    reacts: Map<string, string[]> | undefined;
    speaker: string | null;
    onreact: (emoji: string, on: boolean) => void;
  } = $props();

  const PALETTE = ['💜', '👍', '😂', '🥹', '🎉', '😢', '👀', '🔥'];
  let palette = $state(false);

  const color = (id: string) => core.adaptColor(people.get(id)?.color ?? '#A09184', dark);
  const nameOf = (id: string) => people.get(id)?.display_name ?? people.get(id)?.name ?? 'Someone';
  const time = (t: number) => new Date(t).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  const replied = $derived(m.reply_to ? lookup(m.reply_to) : undefined);
  let body: HTMLElement | undefined = $state();

  function segText(s: MessageRow['segments'][number]) {
    const ents = m.entities
      .filter((e) => e.offset >= s.offset && e.offset + e.length <= s.offset + s.length)
      .map((e) => ({ ...e, offset: e.offset - s.offset }));
    return { text: m.text.slice(s.offset, s.offset + s.length), entities: ents };
  }

  /** Quote the selected part of this message, or all of it (SPEC §5.3). */
  function quote() {
    const sel = getSelection();
    const picked = sel && body && sel.rangeCount && body.contains(sel.anchorNode) ? sel.toString() : '';
    const at = picked ? m.text.indexOf(picked) : -1;
    if (at >= 0 && picked) onquote({ message_id: m.id, offset: at, length: picked.length, text: picked });
    else onquote({ message_id: m.id, offset: 0, length: m.text.length, text: m.text });
  }
</script>

{#if m.deleted}
  <div class="msg deleted">
    Message deleted{#if mine}<button class="link" onclick={onrestore}>Restore</button>{/if}
  </div>
{:else}
  <div class="msg" class:cont class:pinned={m.pinned}>
    {#if replied}
      <div class="replybar">
        ↪ <span style="color: {color(replied.authors[0] ?? '').name}">{replied.authors.map(nameOf).join(' & ')}</span>
        {#if replied.channel_id !== m.channel_id && replied.channel_name}<span class="elsewhere">in #{replied.channel_name}</span>{/if}
        <span class="snip">{replied.deleted ? 'deleted message' : replied.text.slice(0, 80)}</span>
      </div>
    {/if}
    {#if !cont || replied}
      <div class="head">
        <span class="avatars">
          {#each m.authors.slice(0, 3) as a (a)}
            <span class="avatar" style="--ring: {color(a).ring}">{people.get(a)?.sigils[0] ?? nameOf(a)[0]}</span>
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
    <div class="body" bind:this={body}>
      {#if m.quote}
        <blockquote>{m.quote.text}</blockquote>
      {/if}
      {#each m.forward_snapshot ?? [] as f (f.message_id)}
        <div class="forward">
          <span class="fwd">Forwarded from {f.authors.map(nameOf).join(' & ')}{f.channel_name ? ` · #${f.channel_name}` : ''}</span>
          <RichText text={f.text} entities={f.entities} />
        </div>
      {/each}
      {#if m.segments.length > 1}
        {#each m.segments as s, i (i)}
          {@const seg = segText(s)}
          <div class="segment">
            <span class="who" style="color: {color(s.authors[0] ?? '').name}">{s.authors.map(nameOf).join(' & ')}</span>
            <RichText text={seg.text} entities={seg.entities} />
          </div>
        {/each}
      {:else if m.text}
        <RichText text={m.text} entities={m.entities} />
      {/if}
      {#if m.edited}<span class="edited"> (edited)</span>{/if}
      {#if reacts?.size}
        <div class="reacts">
          {#each [...reacts] as [emoji, who] (emoji)}
            {@const mine = !!speaker && who.includes(speaker)}
            <button class="react" class:mine onclick={() => onreact(emoji, !mine)} title={who.map(nameOf).join(', ')}>
              {emoji} <span>{who.length}</span>
            </button>
          {/each}
        </div>
      {/if}
    </div>
    {#if palette}
      <div class="palette" role="listbox" aria-label="React">
        {#each PALETTE as e (e)}
          <button onclick={() => { onreact(e, true); palette = false; }}>{e}</button>
        {/each}
      </div>
    {/if}
    <div class="actions" role="toolbar" aria-label="Message actions">
      <button onclick={() => (palette = !palette)} title="React" disabled={!speaker}>☺</button>
      <button onclick={onreply} title="Reply">↩</button>
      <button onclick={quote} title="Quote (select text first to quote part)">❝</button>
      <button onclick={onforward} title="Forward">↗</button>
      <button onclick={onpin} title={m.pinned ? 'Unpin' : 'Pin'}>{m.pinned ? '⊘' : '📌'}</button>
      {#if mine}
        <button onclick={onedit} title="Edit">✎</button>
        <button onclick={ondelete} title="Delete">🗑</button>
      {/if}
    </div>
  </div>
{/if}

<style>
  .msg {
    position: relative;
    padding: var(--s-3) var(--s-2) 2px;
    border-radius: var(--r-sm);
  }
  .msg.cont {
    padding-top: 2px;
  }
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
