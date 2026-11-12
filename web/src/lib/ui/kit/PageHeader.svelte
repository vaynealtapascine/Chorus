<script lang="ts">
  // A settings page's title, one line that carries data (or none) and an action on the right.
  import type { Snippet } from 'svelte';
  let { title, sub, action }: { title: string; sub?: string | null; action?: Snippet } = $props();
</script>

<header class="page-header">
  <div><h1>{title}</h1>{#if sub}<p>{sub}</p>{/if}</div>
  {#if action}{@render action()}{/if}
</header>

<style>
  .page-header { display: flex; align-items: center; gap: var(--s-3); }
  div { flex: 1; min-width: 0; }
  h1 { margin: 0; font-size: 24px; line-height: 30px; font-weight: 700; letter-spacing: -.01em; }
  p { margin: 2px 0 0; color: var(--ink-3); font-size: 14px; }
</style>
