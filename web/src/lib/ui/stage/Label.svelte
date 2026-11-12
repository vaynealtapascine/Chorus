<script lang="ts">
  import type { Snippet } from 'svelte';
  let { children }: { children: Snippet } = $props();
</script>

<h3>{@render children()}</h3>

<style>
  h3 { margin: 0; color: var(--accent); font-size: var(--fs-sm); font-weight: 600; }
</style>
