<script lang="ts">
  // The app's button (Android ChorusButton). `primary` is the one action of a sheet or form, `neutral` the
  // alternative beside it, `soft` the teal-washed one, `quiet` the plainest, `danger` a filled confirm that removes something and `danger-soft` a red-worded one. `md` and `lg` are the full-width-ish
  // buttons of a sheet footer, `sm` the small pill beside a row. `grow` shares a footer's width, `block` fills the row.
  import type { Snippet } from 'svelte';
  import type { HTMLButtonAttributes } from 'svelte/elements';

  let { variant = 'neutral', size = 'md', round = false, grow = false, block = false, href, class: cls = '', children, ...rest }: Omit<HTMLButtonAttributes, 'class' | 'children'> & {
    variant?: 'primary' | 'neutral' | 'quiet' | 'soft' | 'danger' | 'danger-soft'; size?: 'sm' | 'md' | 'lg'; round?: boolean; grow?: boolean; block?: boolean; href?: string; download?: string; class?: string; children?: Snippet;
  } = $props();
</script>

{#if href && !rest.disabled}
  <a class="btn {variant} {size} {cls}" class:round class:grow class:block {href} {...(rest as Record<string, unknown>)}>{@render children?.()}</a>
{:else}
  <button class="btn {variant} {size} {cls}" class:round class:grow class:block {...rest}>{@render children?.()}</button>
{/if}

<style>
  .btn { display: inline-flex; align-items: center; justify-content: center; gap: var(--s-2); min-height: 44px; padding: 10px var(--s-5); border: 0; border-radius: var(--r-control);
    font: inherit; font-size: var(--fs-md); font-weight: 600; text-decoration: none; cursor: pointer; }
  .lg { min-height: 48px; }
  .sm { min-height: 36px; padding: 0 var(--s-4); border-radius: var(--r-full); }
  .round { border-radius: var(--r-full); }
  .grow { flex: 1; }
  .block { width: 100%; }
  .primary { background: var(--accent); color: var(--on-accent); }
  .soft { background: var(--accent-soft); color: var(--accent); }
  .neutral { background: var(--surface-2); color: var(--ink); }
  .quiet { background: none; color: var(--ink-2); }
  .danger { background: var(--danger); color: var(--on-accent); }
  .danger-soft { background: var(--surface-2); color: var(--danger); }
  .btn:disabled { background: var(--surface-3); color: var(--ink-3); cursor: default; }
</style>
