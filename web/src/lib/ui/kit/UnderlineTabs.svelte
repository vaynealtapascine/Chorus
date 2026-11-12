<script lang="ts" generics="T extends string">
  // Tabs with an accent underline (Android UnderlineTabs; F4.1, F7.1), optionally counted.
  let { tabs, value = $bindable(), label, onchange }: {
    tabs: { id: T; label: string; count?: number | null }[]; value: T; label: string; onchange?: (v: T) => void;
  } = $props();
</script>

<div class="tabs" role="tablist" aria-label={label}>
  {#each tabs as t (t.id)}
    <button role="tab" aria-selected={value === t.id} onclick={() => { value = t.id; onchange?.(t.id); }}>
      {t.label}{#if t.count != null}<span class="count">{t.count}</span>{/if}
    </button>
  {/each}
</div>

<style>
  .tabs { display: flex; gap: var(--s-1); padding: 0 var(--s-1); border-bottom: 1px solid var(--line); overflow-x: auto; scrollbar-width: none; }
  button { position: relative; display: inline-flex; align-items: center; gap: 6px; flex: none; border: 0; background: none; padding: var(--s-3);
    color: var(--ink-3); font: inherit; font-size: var(--fs-md); font-weight: 500; cursor: pointer; }
  button[aria-selected='true'] { color: var(--ink); font-weight: 600; }
  button[aria-selected='true']::after { content: ''; position: absolute; left: 12px; right: 12px; bottom: 0; height: 3px; border-radius: 2px; background: var(--accent); }
  .count { border-radius: var(--r-full); background: var(--surface-3, var(--surface-2)); color: var(--ink); font-size: 11px; font-weight: 600; padding: 1px 6px; }
</style>
