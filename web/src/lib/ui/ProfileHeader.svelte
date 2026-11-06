<script lang="ts">
  // One profile header for every member, ours or a friend's (owner, 2026-11-06), the Journal's
  // Twitter-like one (Figma F6.1, Android's MemberProfile): the banner, the avatar overlapping its
  // lower edge with the profile's action beside it, then the name, a subtitle and whatever the
  // page adds (bio, fields).
  import type { Snippet } from 'svelte';
  import { core } from '../core';
  import AvatarImage from './AvatarImage.svelte';

  let { banner, avatar, glyph, name, color, subtitle = '', dark, actions, children }: {
    banner?: string | null; avatar?: string | null; glyph: string; name: string; color?: string | null;
    subtitle?: string; dark: boolean; actions?: Snippet; children?: Snippet;
  } = $props();
  const tone = $derived(color ? core.adaptColor(color, dark) : null);
</script>

<header class="profile-head" style="--ring: {tone?.ring ?? 'var(--surface-2)'}; --avatar-ink: {tone?.avatar_ink ?? 'var(--ink)'}">
  <div class="banner" class:empty={!banner} aria-label={banner ? 'Profile banner' : undefined} role={banner ? 'img' : undefined}>
    {#if banner}<AvatarImage hash={banner} glyph="" name="Profile banner" />{/if}
  </div>
  <div class="bar">
    <span class="avatar"><AvatarImage hash={avatar ?? undefined} {glyph} {name} /></span>
    {#if actions}<div class="actions">{@render actions()}</div>{/if}
  </div>
  <h1 class="display" style="color: {tone?.name ?? 'inherit'}">{name}</h1>
  {#if subtitle}<p class="subtitle">{subtitle}</p>{/if}
  {@render children?.()}
</header>

<style>
  .profile-head { display: grid; gap: var(--s-2); min-width: 0; }
  .banner { height: clamp(120px, 24vw, 220px); overflow: hidden; border-radius: var(--r-lg); background: var(--surface-2); }
  .banner.empty { height: clamp(72px, 12vw, 110px); background: color-mix(in srgb, var(--ring) 35%, var(--surface)); }
  .banner :global(img) { width: 100%; height: 100%; object-fit: cover; }
  .bar { display: flex; align-items: flex-end; justify-content: space-between; gap: var(--s-3); margin: -48px 0 0 var(--s-4); min-height: 48px; }
  .avatar { display: grid; place-items: center; width: 96px; height: 96px; flex: none; border-radius: 50%; overflow: hidden;
    background: var(--ring); color: var(--avatar-ink); font-size: 36px; font-weight: 700; box-shadow: 0 0 0 4px var(--bg); }
  .avatar :global(img) { width: 100%; height: 100%; object-fit: cover; }
  .actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: var(--s-2); padding-bottom: 4px; }
  h1 { margin: var(--s-1) 0 0; overflow-wrap: anywhere; }
  .subtitle { margin: 0; color: var(--ink-3); }
</style>
