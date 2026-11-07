<script lang="ts">
  // A face on the Friends screens: an account (its picture, else its initial on a colour picked by
  // id) or a member someone shared (their colour and sigil), optionally ringed by layer (F7.2).
  import { core } from '../core';
  import AvatarImage from './AvatarImage.svelte';

  let { name, color, glyph, blob, size = 40, dark, ring = null }: {
    name: string; color: string; glyph?: string | null; blob?: string | null; size?: number; dark: boolean;
    /** a layer ring: solid for the front, dashed for co-conscious and beyond */
    ring?: 'solid' | 'dashed' | null;
  } = $props();
  const c = $derived(core.adaptColor(color, dark));
  const initial = $derived((glyph ?? name.replace(/^@/, '')[0] ?? '?').toUpperCase());
</script>

<span class="wrap" class:ringed={!!ring} class:dashed={ring === 'dashed'} style:--size="{size}px">
  <span class="fr-face" style:width="{ring ? size - 10 : size}px" style:height="{ring ? size - 10 : size}px"
    style:font-size="{Math.round((ring ? size - 10 : size) * 0.4)}px" style:--fr-bg={c.ring} style:--fr-ink={c.avatar_ink}>
    <AvatarImage hash={blob} glyph={initial} {name} />
  </span>
</span>

<style>
  .wrap { display: inline-grid; place-items: center; flex: none; width: var(--size); height: var(--size); border-radius: 50%; }
  .wrap:not(.ringed) { width: auto; height: auto; }
  .ringed { border: 2px solid var(--accent); }
  .ringed.dashed { border: 2px dashed color-mix(in srgb, var(--accent) 70%, transparent); }
</style>
