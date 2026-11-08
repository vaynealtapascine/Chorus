<script lang="ts">
  // A member's banner behind their Members row (D-140): two-thirds of the panel's width,
  // anchored bottom-right, fading towards the name. Loaded once the row is near view.
  import { sync } from '../sync/client';
  import { loadBlob } from '../sync/blobs';

  let { hash }: { hash: string } = $props();
  let el = $state<HTMLImageElement>();
  let near = $state(false);
  let src = $state('');

  $effect(() => {
    if (!el || near) return;
    const io = new IntersectionObserver((es) => { if (es.some((e) => e.isIntersecting)) near = true; }, { rootMargin: '400px' });
    io.observe(el);
    return () => io.disconnect();
  });
  $effect(() => {
    if (!near) return;
    const wanted = hash;
    let cancelled = false;
    let url = '';
    src = '';
    void loadBlob(wanted, sync.device).then((blob) => {
      if (blob && !cancelled) src = url = URL.createObjectURL(blob);
    });
    return () => { cancelled = true; if (url) URL.revokeObjectURL(url); };
  });
</script>

<img bind:this={el} class="banner" class:shown={!!src} src={src || undefined} alt="" aria-hidden="true" draggable="false" />

<style>
  .banner {
    position: absolute;
    right: 0;
    bottom: 0;
    height: 100%;
    width: calc(100% * 2 / 3);
    object-fit: cover;
    object-position: right bottom;
    pointer-events: none;
    opacity: 0;
    transition: opacity var(--t-base) var(--ease);
    mask-image: linear-gradient(to right, transparent 0, rgb(0 0 0 / .45) 40%, #000 85%);
  }
  .banner.shown { opacity: 1; }
</style>
