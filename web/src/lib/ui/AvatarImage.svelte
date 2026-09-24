<script lang="ts">
  import { onMount } from 'svelte';
  import { sync } from '../sync/client';
  import { loadBlob } from '../sync/blobs';

  let { hash, glyph, name }: { hash?: string | null; glyph: string; name?: string } = $props();
  let src = $state('');
  let tick = $state(0);
  onMount(() => {
    let status = sync.status;
    return sync.subscribe(() => {
      if (status !== sync.status) { status = sync.status; tick += 1; }
    });
  });
  $effect(() => {
    void tick;
    const wanted = hash;
    let cancelled = false;
    let objectUrl = '';
    src = '';
    if (wanted) void (async () => {
      const blob = await loadBlob(wanted, sync.device);
      if (blob && !cancelled) {
        objectUrl = URL.createObjectURL(blob);
        src = objectUrl;
      }
    })();
    return () => { cancelled = true; if (objectUrl) URL.revokeObjectURL(objectUrl); };
  });
</script>

{#if src}<img {src} alt={name ?? ''} />{:else}{glyph}{/if}

<style>
  img { width: 100%; height: 100%; object-fit: cover; border-radius: inherit; display: block; }
</style>
