<script lang="ts">
  // A labelled row with a control at its end (F8.3 "Crop reply chains  − 2 +").
  import type { Snippet } from 'svelte';
  let { label, children }: { label: string; children: Snippet } = $props();
</script>

<div class="row"><span>{label}</span>{@render children()}</div>

<style>
  .row { display: flex; align-items: center; gap: 12px; min-height: 48px; font-size: 15px; }
  span { flex: 1; min-width: 0; }
</style>
