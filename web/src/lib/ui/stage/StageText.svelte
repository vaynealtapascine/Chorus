<script lang="ts">
  // A stage's text with its blacked-out ranges (core's, merged) drawn as solid bars. While `editing`
  // (the Redact tool), a click blacks out a word or gives one back, and a press and drag blacks out
  // what it passes over (F8.6). Outside editing the bars hold blocks, never the words, so what is
  // hidden is not in the captured page at all.
  import type { Entity } from '../../core';
  import { blackParts, dragBlackout, tapBlackout, type Range } from '../../stage-def';
  import { pick } from '../../stage-pick';
  import RichText from '../RichText.svelte';

  let { text, entities = [], shown = [], own = [], editing = false, onchange }: {
    text: string; entities?: Entity[]; shown?: readonly Range[]; own?: readonly Range[]; editing?: boolean; onchange?: (ranges: Range[]) => void;
  } = $props();
  let preview = $state<Range | null>(null);
  const parts = $derived(blackParts(text, [...shown, ...(preview ? [preview] : [])], editing));
</script>

{#if !editing && !shown.length}
  <div class="text"><RichText {text} {entities} /></div>
{:else}
  <div class="text plain" class:editing use:pick={{
    enabled: editing,
    ontap: (i) => onchange?.(tapBlackout(text, own, i)),
    ondragging: (ends) => (preview = ends ? (dragBlackout(text, [], ends[0], ends[1])[0] ?? null) : null),
    ondrag: (from, to) => onchange?.(dragBlackout(text, own, from, to)),
  }}>{#each parts as part, j (j)}{#if part.black}<span class="blackout">{part.text}</span>{:else}{part.text}{/if}{/each}</div>
{/if}

<style>
  .text { color: inherit; white-space: pre-wrap; overflow-wrap: anywhere; }
  .editing { cursor: text; user-select: none; -webkit-user-select: none; -webkit-touch-callout: none; }
  .blackout { background: #000; color: #000; border-radius: 2px; }
</style>
