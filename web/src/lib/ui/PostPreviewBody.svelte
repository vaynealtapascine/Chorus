<script lang="ts">
  import { postPreview } from '../post-preview';
  import type { Entity } from '../core';
  import RichText from './RichText.svelte';
  let { text, entities = [] }: { text: string; entities?: Entity[] } = $props();
  let expanded = $state(false);
  const preview = $derived(postPreview(text, entities));
</script>
<span class="body"><RichText text={expanded ? text : preview.text} entities={expanded ? entities : preview.entities} />{#if !expanded && preview.truncated}…{/if}</span>
{#if preview.truncated}<button aria-expanded={expanded} onclick={() => (expanded = !expanded)}>{expanded ? 'Show less' : 'Show more'}</button>{/if}
<style>
  .body { white-space: pre-wrap; overflow-wrap: anywhere; } button { display: block; min-height: 44px; padding: 8px 0; color: var(--accent); background: none; border: 0; cursor: pointer; }
</style>
