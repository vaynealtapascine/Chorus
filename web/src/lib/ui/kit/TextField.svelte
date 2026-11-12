<script lang="ts">
  // A text field (Android ChorusTextField): filled, or `outlined` where a field sits beside others on a plain
  // page; `multiline` makes it a textarea; `tall` is the 48 dp one of a sheet's single question.
  import type { HTMLInputAttributes } from 'svelte/elements';

  let { value = $bindable(''), multiline = false, outlined = false, tall = false, class: cls = '', ...rest }: Omit<HTMLInputAttributes, 'class' | 'value'> & {
    value?: string | number | null; multiline?: boolean; outlined?: boolean; tall?: boolean; class?: string; rows?: number;
  } = $props();
</script>

{#if multiline}
  <textarea class="field {cls}" class:outlined bind:value {...(rest as Record<string, unknown>)}></textarea>
{:else}
  <input class="field {cls}" class:outlined class:tall bind:value {...rest} />
{/if}

<style>
  .field { box-sizing: border-box; min-width: 0; padding: var(--s-3) 14px; border: 0; border-radius: var(--r-row); background: var(--surface-2); color: var(--ink); font: inherit; }
  .outlined { border: 1px solid var(--line); }
  .tall { height: 48px; padding-block: 0; font-size: var(--fs-base); }
  textarea.field { resize: vertical; }
</style>
