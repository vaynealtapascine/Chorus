<script lang="ts">
  // A bucket's icon (D-115): a built-in icon or its own glyph (an emoji), in its colour when it
  // has one. The colour is mixed with the theme's ink so it stays readable in light and dark.
  import { bucketColor, bucketIcon, isBuiltInIcon } from '../bucket-controls';
  import Icon from './Icon.svelte';
  let { icon, color = null, size = 16, disc = false }: { icon?: string; color?: string | null; size?: number; disc?: boolean } = $props();
  const shown = $derived(bucketIcon(icon));
  const tint = $derived(bucketColor(color));
</script>

<span class="glyph" class:disc class:tinted={!!tint} style:--size="{size}px" style:--bucket={tint}>
  {#if isBuiltInIcon(shown)}<Icon name={shown} size={disc ? Math.round(size * 0.55) : size} />{:else}<span class="emoji" aria-hidden="true">{shown}</span>{/if}
</span>

<style>
  .glyph { display: inline-grid; place-items: center; flex: none; width: var(--size); height: var(--size); line-height: 1; }
  .tinted { color: color-mix(in srgb, var(--bucket) 75%, var(--ink)); }
  .disc { border-radius: 50%; background: var(--accent-soft); color: var(--accent); }
  .disc.tinted { background: color-mix(in srgb, var(--bucket) 22%, transparent); color: color-mix(in srgb, var(--bucket) 75%, var(--ink)); }
  .emoji { font-size: calc(var(--size) * 0.85); }
  .disc .emoji { font-size: calc(var(--size) * 0.55); }
</style>
