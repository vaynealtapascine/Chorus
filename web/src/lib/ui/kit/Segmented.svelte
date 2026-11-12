<script lang="ts" generics="T extends string">
  // A segmented choice (Android Segmented; F10.2 theme, F9.1 range): one of a few, all in view.
  let { options, value = $bindable(), label, onchange }: {
    options: { id: T; label: string }[]; value: T; label: string; onchange?: (v: T) => void;
  } = $props();
</script>

<div class="segmented" role="radiogroup" aria-label={label}>
  {#each options as o (o.id)}
    <button type="button" role="radio" aria-checked={value === o.id} onclick={() => { value = o.id; onchange?.(o.id); }}>{o.label}</button>
  {/each}
</div>

<style>
  .segmented { display: flex; gap: 3px; padding: 3px; border-radius: var(--r-row); background: var(--surface-2); }
  button { flex: 1; min-height: 34px; border: 0; border-radius: 9px; background: none; color: var(--ink-3); font: inherit; font-size: var(--fs-sm); font-weight: 500; cursor: pointer; }
  button[aria-checked='true'] { background: var(--accent); color: var(--on-accent); font-weight: 600; }
</style>
