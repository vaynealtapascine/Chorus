<script lang="ts">
  // "Only these members" (F8.3, F8.5): a scrolling row of faces that narrow what shows.
  import AvatarImage from '../AvatarImage.svelte';
  import Chip from '../kit/Chip.svelte';
  import type { StageAuthor } from './authors';

  let { authors, only, onchange }: { authors: StageAuthor[]; only: string[] | null; onchange: (next: string[] | null) => void } = $props();
  function toggle(id: string) {
    const cur = only ?? [];
    const next = cur.includes(id) ? cur.filter((x) => x !== id) : [...cur, id];
    onchange(next.length ? next : null);
  }
</script>

<div class="chips">
  {#each authors as a (a.id)}
    <Chip label={a.name} on={!!only?.includes(a.id)} onclick={() => toggle(a.id)}>
      {#snippet leading()}<span class="face" style="--c: {a.color}"><AvatarImage hash={a.blob} glyph={a.glyph} name={a.name} /></span>{/snippet}
    </Chip>
  {/each}
</div>

<style>
  .chips { display: flex; gap: var(--s-2); overflow-x: auto; scrollbar-width: none; padding-bottom: 2px; }
  .face { display: inline-grid; place-items: center; width: 24px; height: 24px; border-radius: 50%; overflow: hidden; background: var(--c); font-size: var(--fs-xs); }
</style>
