<script lang="ts">
  // The way into the Review page (F3.6) from Fronting and History: one line while two devices' switches wait to be checked.
  import Icon from './Icon.svelte';

  let { count, front }: { count: number; front: string } = $props();
</script>

{#if count > 0}
  <a class="review-row" href="#/review/{encodeURIComponent(front)}"><span class="disc" aria-hidden="true">!</span>
    <span class="text">{count === 1 ? 'Two devices switched together' : `${count} switches to review`}</span><span class="review">Review</span><Icon name="chevron-right" size={18} /></a>
{/if}

<style>
  .review-row { display: flex; align-items: center; gap: 10px; padding: 10px var(--s-3); border-radius: var(--r-row); background: var(--surface); color: var(--ink); font-size: 14px; text-decoration: none; }
  .text { flex: 1; min-width: 0; }
  .disc { width: 28px; height: 28px; display: grid; place-items: center; border-radius: 9px; background: color-mix(in srgb, var(--warn) 16%, transparent); color: var(--warn); font-weight: 700; }
  .review { color: var(--accent); font-weight: 600; }
  .review-row :global(.icon) { color: var(--ink-3); }
</style>
