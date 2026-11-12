<script lang="ts">
  // A chip (Android StageChip, F7 chips): a small choice or an add-one, quiet until `on`. `on` set makes it a
  // toggle (a checkbox unless the caller says radio / aria-pressed), `leading` is a face or glyph, `href` makes it a link.
  import type { Snippet } from 'svelte';
  import type { HTMLButtonAttributes } from 'svelte/elements';

  let { label, on, leading, href, class: cls = '', children, ...rest }: Omit<HTMLButtonAttributes, 'class' | 'children'> & {
    label?: string; on?: boolean; leading?: Snippet; href?: string; class?: string; children?: Snippet;
  } = $props();
</script>

{#if href}
  <a class="chip {cls}" class:on {href} {...(rest as Record<string, unknown>)}>
    {#if leading}{@render leading()}{/if}{label ?? ''}{@render children?.()}
  </a>
{:else}
  <button class="chip {cls}" class:on role={on === undefined ? undefined : 'checkbox'} aria-checked={on} {...rest}>
    {#if leading}{@render leading()}{/if}{label ?? ''}{@render children?.()}
  </button>
{/if}

<style>
  .chip { flex: none; display: inline-flex; align-items: center; gap: 6px; min-height: 32px; padding: 0 var(--s-3); border: 0; border-radius: var(--r-md); background: var(--surface-2); color: var(--ink-2);
    font: inherit; font-size: var(--fs-sm); font-weight: 500; white-space: nowrap; text-decoration: none; cursor: pointer; }
  .chip.on, .chip[aria-pressed='true'] { background: var(--accent-soft); color: var(--accent); }
  .chip:disabled { color: var(--ink-3); cursor: default; }
  .chip:has(:global(.face)) { padding-left: var(--s-1); }
</style>
