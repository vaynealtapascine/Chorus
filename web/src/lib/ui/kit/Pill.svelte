<script lang="ts">
  // A small read-only label (F7 "System", a bucket on a friend, an audience): quiet, or `accent` for the teal one.
  import type { Snippet } from 'svelte';

  let { tone = 'quiet', class: cls = '', children, ...rest }: { tone?: 'quiet' | 'accent'; class?: string; children?: Snippet; [key: string]: unknown } = $props();
</script>

<span class="pill {tone} {cls}" {...rest}>{@render children?.()}</span>

<style>
  .pill { flex: none; display: inline-flex; align-items: center; gap: var(--s-1); padding: 2px 10px; border-radius: var(--r-full); font-size: var(--fs-xs); font-weight: 500; line-height: 16px; white-space: nowrap; }
  .quiet { background: var(--surface-3); color: var(--ink-2); }
  .accent { background: var(--accent-soft); color: var(--accent); }
</style>
