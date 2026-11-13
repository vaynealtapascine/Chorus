<script lang="ts">
  // The phone's pencil (Android Journal.kt): starts a post from the full composer, above the tab bar.
  import Icon from '../Icon.svelte';
  import { openComposer } from '../../journal-compose.svelte';
  let { kind }: { kind: 'note' | 'entry' } = $props();
</script>

<button type="button" class="fab" aria-label="Write a post" onclick={() => openComposer({ kind })}><Icon name="edit" size={24} /></button>

<style>
  .fab { position: fixed; right: var(--s-4); bottom: calc(69px + env(safe-area-inset-bottom) + var(--s-4)); z-index: 9; display: grid; place-items: center; width: 56px; height: 56px;
    border: 0; border-radius: 50%; background: var(--accent); color: var(--on-accent); box-shadow: var(--shadow-pop); cursor: pointer; }
  @media (min-width: 641px) { .fab { display: none; } }
</style>
