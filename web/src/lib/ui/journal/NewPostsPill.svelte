<script lang="ts">
  // "New posts ↑" (Android Journal.kt): a post arrived while the reader is scrolled down. Sticky at the
  // column's top with no height, so it never moves the feed.
  import Icon from '../Icon.svelte';
  let { onclick }: { onclick: () => void } = $props();
</script>

<div class="slot"><button type="button" class="pill" {onclick}><span class="up"><Icon name="back" size={16} /></span>New posts</button></div>

<style>
  .slot { position: sticky; top: var(--s-3); z-index: 8; height: 0; display: flex; justify-content: center; pointer-events: none; }
  .pill { pointer-events: auto; display: inline-flex; align-items: center; gap: var(--s-1); min-height: 40px; padding: 0 var(--s-4); border: 0; border-radius: var(--r-full);
    background: var(--accent); color: var(--on-accent); font: inherit; font-size: var(--fs-sm); font-weight: 600; box-shadow: var(--shadow-menu); cursor: pointer;
    animation: chorus-fade-in var(--t-fast) var(--ease); }
  .up { display: inline-grid; transform: rotate(90deg); }
</style>
