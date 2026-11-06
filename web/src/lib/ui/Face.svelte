<script lang="ts">
  // A round face: a member's picture or glyph on their theme-adapted colour (Figma Avatar v2).
  import { core } from '../core';
  import type { Who } from '../fronting-subjects';
  import AvatarImage from './AvatarImage.svelte';

  let { who, size = 40, dark, faint = false }: { who: Who | undefined; size?: number; dark: boolean; faint?: boolean } = $props();
  const c = $derived(core.adaptColor(who?.color ?? '#A09184', dark));
</script>

<span class="face" class:state={who?.type === 'state'} class:faint style="--size: {size}px; --fill: {c.ring}; --ink: {c.avatar_ink}; font-size: {Math.round(size * 0.42)}px" aria-hidden="true">
  {#if who?.type === 'state'}{who.glyph}{:else}<AvatarImage hash={who?.blob} glyph={who?.glyph ?? '·'} name={who?.name} />{/if}
</span>

<style>
  .face { width: var(--size); height: var(--size); flex: none; display: inline-grid; place-items: center; border-radius: 50%; overflow: hidden;
    background: var(--fill); color: var(--ink); font-weight: 700; line-height: 1; }
  .face.state { background: var(--surface-2); color: var(--ink-2); border: 1.5px dashed color-mix(in oklab, var(--ink-3) 60%, transparent); }
  .face.faint { opacity: 0.7; }
  .face :global(img) { width: 100%; height: 100%; object-fit: cover; }
</style>
