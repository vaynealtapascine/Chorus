<script lang="ts">
  // A row that opens a choice (F3.5; Android FormValueRow): icon, label, the current value in the accent
  // and a chevron. A link when `href` is given.
  import Icon from '../Icon.svelte';

  let { label, value = '', icon, href, onclick, disabled = false }: {
    label: string; value?: string | null; icon?: string; href?: string; onclick?: () => void; disabled?: boolean;
  } = $props();
</script>

{#snippet inner()}
  {#if icon}<Icon name={icon} size={20} />{/if}
  <span class="label">{label}</span>
  {#if value}<span class="value">{value}</span>{/if}
  <span class="chev"><Icon name="chevron-right" size={18} /></span>
{/snippet}
{#if href && !disabled}<a class="value-row" {href}>{@render inner()}</a>
{:else}<button class="value-row" {disabled} {onclick}>{@render inner()}</button>{/if}

<style>
  .value-row { display: flex; align-items: center; gap: 12px; width: 100%; min-height: 46px; padding: 10px 14px; border: 0; background: none;
    color: var(--ink); font: inherit; font-size: 15px; text-align: left; cursor: pointer; text-decoration: none; }
  .value-row:hover:not(:disabled) { background: color-mix(in srgb, var(--ink) 4%, transparent); }
  .value-row > :global(.icon) { color: var(--ink-2); }
  .label { flex: 1; min-width: 0; }
  .value { max-width: 200px; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; color: var(--accent); font-weight: 500; }
  .chev { display: inline-flex; color: var(--ink-3); }
  .value-row:disabled { cursor: default; }
  .value-row:disabled .label, .value-row:disabled .value { color: var(--ink-3); }
</style>
