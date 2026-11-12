<script lang="ts">
  // "By hand" (F8.7): the time one message shows in this stage.
  import { untrack } from 'svelte';
  import Sheet from '../Sheet.svelte';

  let { at, onpick, onclose }: { at: number; onpick: (at: number) => void; onclose: () => void } = $props();
  const local = (t: number) => new Date(t - new Date(t).getTimezoneOffset() * 60_000).toISOString().slice(0, 16);
  let value = $state(untrack(() => local(at)));
  const time = $derived(new Date(value).getTime());
</script>

<Sheet title="Shown time" {onclose} layer={3}>
  <input type="datetime-local" bind:value aria-label="Shown time" />
  {#snippet footer()}
    <button class="neutral" onclick={onclose}>Cancel</button>
    <button class="primary" disabled={Number.isNaN(time)} onclick={() => onpick(time)}>Done</button>
  {/snippet}
</Sheet>

<style>
  input { height: 48px; padding: 0 14px; border: 1px solid var(--line); border-radius: 12px; background: var(--surface-2); color: var(--ink); font: inherit; font-size: 16px; }
  .neutral, .primary { min-height: 48px; border: 0; border-radius: 24px; font: inherit; font-size: 15px; font-weight: 600; cursor: pointer; }
  .neutral { flex: 1; background: var(--surface-2); color: var(--ink); }
  .primary { flex: 2; background: var(--accent); color: var(--on-accent); }
  .primary:disabled { background: var(--surface-3); color: var(--ink-3); cursor: default; }
</style>
