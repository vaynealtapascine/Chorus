<script lang="ts">
  import { onMount } from 'svelte';
  import { holdObjectUrl, onSyncStatusChange, sharedObjectUrl } from '../blob-url';

  let { hash, glyph, name }: { hash?: string | null; glyph: string; name?: string } = $props();
  let tick = $state(0);
  onMount(() => onSyncStatusChange(() => (tick += 1)));
  // one URL per picture for the page: a re-created avatar shows its picture in its first frame
  let loaded = $state<{ hash: string; url: string } | null>(null);
  const src = $derived(!hash ? '' : loaded?.hash === hash ? loaded.url : sharedObjectUrl(hash));
  $effect(() => {
    void tick;
    const wanted = hash;
    if (!wanted) return;
    return holdObjectUrl(wanted, (url) => (loaded = { hash: wanted, url }));
  });
</script>

{#if src}<img {src} alt={name ?? ''} />{:else}{glyph}{/if}

<style>
  img { width: 100%; height: 100%; object-fit: cover; border-radius: inherit; display: block; }
</style>
