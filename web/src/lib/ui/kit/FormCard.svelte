<script lang="ts">
  // A rounded card holding rows of a form (F3.5; Android FormCard): rows inside get a line between them.
  import type { Snippet } from 'svelte';

  let { children, label }: { children: Snippet; label?: string } = $props();
</script>

<div class="form-card" role={label ? 'group' : undefined} aria-label={label}>{@render children()}</div>

<style>
  .form-card { display: grid; border-radius: 16px; background: var(--surface-2); overflow: hidden; }
  .form-card > :global(* + *) { border-top: 1px solid var(--line); }
</style>
