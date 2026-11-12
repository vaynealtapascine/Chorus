<script lang="ts">
  // One action (Android MenuRow): an icon, a label and maybe a line under it. `selected` set makes it a
  // choice, with a check on the chosen one; `danger` colours a destructive action.
  import type { Snippet } from 'svelte';
  import Icon from '../Icon.svelte';

  let { label, icon, sub, danger = false, disabled = false, selected, leading, href, onclick }: {
    label: string; icon?: string; sub?: string | null; danger?: boolean; disabled?: boolean; selected?: boolean;
    leading?: Snippet; href?: string; onclick?: () => void;
  } = $props();
  const role = $derived(selected === undefined ? 'menuitem' : 'menuitemradio');
</script>

{#snippet inner()}
  {#if leading}{@render leading()}{:else if icon}<Icon name={icon} size={22} />{/if}
  <span class="text"><span class="label">{label}</span>{#if sub}<span class="sub">{sub}</span>{/if}</span>
  {#if selected}<span class="check"><Icon name="check" size={20} /></span>{/if}
{/snippet}
{#if href && !disabled}
  <a class="menu-row" class:danger class:selected {href} {role} aria-checked={selected} onclick={onclick}>{@render inner()}</a>
{:else}
  <button class="menu-row" class:danger class:selected {role} aria-checked={selected} {disabled} {onclick}>{@render inner()}</button>
{/if}

<style>
  .menu-row { display: flex; align-items: center; gap: 16px; width: 100%; min-height: 52px; padding: 8px 12px; border: 0; border-radius: 12px;
    background: none; color: var(--ink); font: inherit; text-align: left; cursor: pointer; text-decoration: none; }
  .menu-row:hover:not(:disabled) { background: var(--surface-2); }
  .menu-row > :global(.icon) { color: var(--ink-2); }
  .text { flex: 1; min-width: 0; display: grid; }
  .label { font-size: 16px; line-height: 21px; }
  .sub { color: var(--ink-3); font-size: 13px; line-height: 17px; }
  .selected { background: var(--accent-soft); }
  .selected .label { color: var(--accent); font-weight: 600; }
  .check { display: inline-flex; color: var(--accent); }
  .danger .label, .danger > :global(.icon) { color: var(--danger); }
  .menu-row:disabled { cursor: default; }
  .menu-row:disabled .label, .menu-row:disabled > :global(.icon) { color: var(--ink-3); }
</style>
