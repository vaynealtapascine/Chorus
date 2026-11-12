<script lang="ts">
  // A quoted post inside another: a bordered mini card (name, time, three lines) that opens it.
  import type { QuoteView } from '../post-threads';
  import RichText from './RichText.svelte';

  let { quote, gone = 'Quoted post unavailable' }: { quote: QuoteView; gone?: string } = $props();
</script>

{#if quote.post}
  <a class="quote" href="#/journal/post/{encodeURIComponent(quote.id)}" aria-label="Quoted post by {quote.names}">
    <span class="by"><strong>{quote.names}</strong> · {new Date(quote.post.occurred_at).toLocaleDateString()}</span>
    {#if quote.post.cw}<span class="text">Content warning: {quote.post.cw}</span>
    {:else}{#if quote.post.title}<strong class="title">{quote.post.title}</strong>{/if}<span class="text"><RichText text={quote.post.text} entities={quote.post.entities ?? []} /></span>{/if}
  </a>
{:else}
  <p class="quote gone">{gone}</p>
{/if}

<style>
  .quote { display: grid; gap: var(--s-1); padding: var(--s-3); border: 1px solid var(--line); border-radius: var(--r-row); color: var(--ink); text-decoration: none; min-width: 0; }
  a.quote:hover { background: var(--surface); }
  .by { color: var(--ink-3); font-size: var(--fs-xs); }
  .by strong { color: var(--ink); }
  .text { display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 3; line-clamp: 3; overflow: hidden; white-space: pre-wrap; overflow-wrap: anywhere; font-size: var(--fs-sm); }
  .gone { margin: 0; color: var(--ink-3); font-size: var(--fs-sm); }
</style>
