<script lang="ts">
  // "Chorus is ready" (Figma F10.5): what is new, then Later or Install (a reload; drafts are kept).
  import Sheet from './Sheet.svelte';
  import Icon from './Icon.svelte';
  import { installUpdate, updates } from '../updates.svelte';
</script>

{#if updates.ready && !updates.later}
  <Sheet title="Chorus is ready" onclose={() => (updates.later = true)}>
    {#if updates.notes.length}
      <ul>{#each updates.notes as line (line)}<li><Icon name="check" size={18} />{line}</li>{/each}</ul>
    {/if}
    {#snippet footer()}
      <button class="later" onclick={() => (updates.later = true)}>Later</button>
      <button class="go" onclick={installUpdate}>Install</button>
    {/snippet}
  </Sheet>
{/if}

<style>
  ul { list-style: none; margin: 0; padding: var(--s-3) 14px; display: grid; gap: 10px; border-radius: var(--r-control); background: var(--surface-2); }
  li { display: flex; gap: 10px; align-items: flex-start; }
  li :global(.icon) { color: var(--accent); margin-top: 2px; }
  .later, .go { flex: 1; min-height: 48px; border: 0; border-radius: var(--r-control); font: inherit; font-weight: 600; cursor: pointer; }
  .later { background: var(--surface-2); color: var(--ink); }
  .go { background: var(--accent); color: var(--on-accent); }
</style>
