<script lang="ts">
  // "By hand" (F8.7): the time one message shows in this stage.
  import Button from '../kit/Button.svelte';
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
    <Button size="lg" round grow class="neutral" onclick={onclose}>Cancel</Button>
    <Button variant="primary" size="lg" round grow class="primary" disabled={Number.isNaN(time)} onclick={() => onpick(time)}>Done</Button>
  {/snippet}
</Sheet>

<style>
  input { height: 48px; padding: 0 14px; border: 1px solid var(--line); border-radius: var(--r-row); background: var(--surface-2); color: var(--ink); font: inherit; font-size: var(--fs-base); }
</style>
