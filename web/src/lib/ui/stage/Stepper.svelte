<script lang="ts">
  // − value + (F8.3 Crop reply chains, F8.5 Reply levels); a missing handler greys that side.
  let { value, label, minus, plus }: { value: string; label: string; minus?: (() => void) | null; plus?: (() => void) | null } = $props();
</script>

<div class="stepper" role="group" aria-label="{label}, {value}">
  <button aria-label="Fewer" disabled={!minus} onclick={() => minus?.()}>−</button>
  <span>{value}</span>
  <button aria-label="More" disabled={!plus} onclick={() => plus?.()}>+</button>
</div>

<style>
  .stepper { display: inline-flex; align-items: center; border-radius: 20px; background: var(--surface-2); }
  button { width: 40px; height: 40px; border: 0; border-radius: 50%; background: none; color: var(--ink); font: inherit; font-size: var(--fs-lg); font-weight: 600; cursor: pointer; }
  button:disabled { color: var(--ink-3); cursor: default; }
  span { min-width: 28px; text-align: center; font-weight: 600; font-size: var(--fs-md); }
</style>
