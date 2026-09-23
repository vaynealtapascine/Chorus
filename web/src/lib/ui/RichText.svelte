<script lang="ts" module>
  import type { Entity } from '../core';

  export interface Piece {
    text: string;
    marks: Entity[];
  }

  /** Split text at entity boundaries (UTF-16 offsets, which JS strings use natively). */
  export function pieces(text: string, entities: Entity[]): Piece[] {
    const cuts = new Set<number>([0, text.length]);
    for (const e of entities) {
      cuts.add(Math.min(e.offset, text.length));
      cuts.add(Math.min(e.offset + e.length, text.length));
    }
    const sorted = [...cuts].sort((a, b) => a - b);
    const out: Piece[] = [];
    for (let i = 0; i + 1 < sorted.length; i++) {
      const [a, b] = [sorted[i], sorted[i + 1]];
      if (a === b) continue;
      out.push({ text: text.slice(a, b), marks: entities.filter((e) => e.offset <= a && e.offset + e.length >= b) });
    }
    return out;
  }

  const CLASS: Record<string, string> = {
    bold: 'b',
    italic: 'i',
    underline: 'u',
    strikethrough: 's',
    spoiler: 'spoiler',
    code: 'code',
    pre: 'pre',
    blockquote: 'quote',
    expandable_blockquote: 'quote',
    mention: 'mention',
    custom_emoji: 'emoji',
  };

  export function classes(marks: Entity[]): string {
    return marks.map((m) => CLASS[m.type] ?? '').filter(Boolean).join(' ');
  }

  export function href(marks: Entity[], text: string): string | null {
    const link = marks.find((m) => m.type === 'text_link' || m.type === 'url');
    if (!link) return null;
    const url = link.type === 'url' ? text : String(link.url ?? '');
    return /^(https?:|mailto:|chorus:)/.test(url) ? url : null;
  }
</script>

<script lang="ts">
  import type { EmojiRow } from '../data';
  import EmojiImage from './EmojiImage.svelte';
  let { text, entities = [], emoji = new Map() }: { text: string; entities?: Entity[]; emoji?: Map<string, EmojiRow> } = $props();
  const parts = $derived(pieces(text, entities));
  let revealed = $state(new Set<number>());
</script>

<span class="rich">
  {#each parts as p, i (i)}
    {@const url = href(p.marks, p.text)}
    {@const cls = classes(p.marks)}
    {@const customId = p.marks.find((mark) => mark.type === 'custom_emoji')?.emoji_id}
    {@const custom = typeof customId === 'string' ? emoji.get(customId) : undefined}
    {#if cls.includes('spoiler') && !revealed.has(i)}
      <button class="{cls} hidden" onclick={() => (revealed = new Set(revealed).add(i))} aria-label="Reveal spoiler">{p.text}</button>
    {:else if url}
      <a class={cls} href={url} target="_blank" rel="noopener noreferrer">{p.text}</a>
    {:else if custom}
      <span class="emoji-token">{p.text}</span>
      <EmojiImage hash={custom.blob_hash} name={custom.name} />
    {:else if cls}
      <span class={cls}>{p.text}</span>
    {:else}
      {p.text}
    {/if}
  {/each}
</span>

<style>
  .rich {
    white-space: pre-wrap;
    overflow-wrap: anywhere;
  }
  /* Keep the original token in DOM text so quote-selection offsets still match the stored text. */
  .emoji-token { position: absolute; width: 1px; height: 1px; overflow: hidden; clip-path: inset(50%); white-space: nowrap; }
  .b {
    font-weight: 650;
  }
  .i {
    font-style: italic;
  }
  .u {
    text-decoration: underline;
  }
  .s {
    text-decoration: line-through;
  }
  .u.s {
    text-decoration: underline line-through;
  }
  .code,
  .pre {
    font-family: var(--font-mono);
    font-size: 0.92em;
    background: var(--surface-2);
    border-radius: 4px;
    padding: 0 0.25em;
  }
  .pre {
    display: block;
    padding: var(--s-2) var(--s-3);
    margin: var(--s-1) 0;
  }
  .quote {
    display: block;
    border-left: 3px solid var(--line);
    padding-left: var(--s-3);
    color: var(--ink-2);
  }
  .mention {
    color: var(--accent);
    background: var(--accent-soft);
    border-radius: 4px;
    padding: 0 0.2em;
  }
  a {
    color: var(--accent);
  }
  .spoiler.hidden {
    all: unset;
    cursor: pointer;
    background: var(--ink-3);
    color: transparent;
    border-radius: 4px;
  }
  .spoiler {
    background: var(--surface-2);
    border-radius: 4px;
  }
</style>
