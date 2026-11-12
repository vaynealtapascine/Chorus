<script lang="ts">
  // A quiet chip (Android StageChip); `on` set makes it a toggle, `leading` is a face.
  import type { Snippet } from 'svelte';
  let { label, on, leading, onclick }: { label: string; on?: boolean; leading?: Snippet; onclick: () => void } = $props();
</script>

<button class="chip" class:on role={on === undefined ? undefined : 'checkbox'} aria-checked={on} {onclick}>
  {#if leading}{@render leading()}{/if}{label}
</button>

<style>
  .chip { flex: none; display: inline-flex; align-items: center; gap: 6px; height: 32px; padding: 0 var(--s-3); border: 0; border-radius: var(--r-md); background: var(--surface-2); color: var(--ink-2);
    font: inherit; font-size: var(--fs-sm); font-weight: 500; white-space: nowrap; cursor: pointer; }
  .chip.on { background: var(--accent-soft); color: var(--accent); }
  .chip:has(:global(.face)) { padding-left: var(--s-1); }
</style>
