<script lang="ts">
  // The round check beside a message while picking (F8.2): filled when picked.
  import Icon from '../Icon.svelte';
  let { on }: { on: boolean } = $props();
</script>

<span class="check" class:on role="checkbox" aria-checked={on} aria-label={on ? 'Picked' : 'Not picked'}>{#if on}<Icon name="check" size={14} />{/if}</span>

<style>
  .check { flex: none; width: 22px; height: 22px; box-sizing: border-box; display: inline-grid; place-items: center; border-radius: 50%; border: 1.5px solid var(--ink-3); color: var(--on-accent); }
  .check.on { background: var(--accent); border-color: var(--accent); }
</style>
