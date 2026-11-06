<script lang="ts">
  // A front's orbit (Figma F3.4; Android Orbit): the innermost layer is a solid ring, the next a
  // dashed accent ring, outer ones faint dashed rings; each face sits on its ring.
  import type { Who } from '../fronting-subjects';
  import Face from './Face.svelte';

  let { rings, size = 76, dark }: { rings: Who[][]; size?: number; dark: boolean } = $props();
  const count = $derived(Math.min(Math.max(rings.length, 1), 3));
  const radius = (i: number) => (count === 1 ? 0.5 : count === 2 ? [0.45, 0.92][i] : [0.32, 0.62, 0.95][i]) * (size / 2);
  const face = $derived(Math.round(size * 0.22));
  function spot(i: number, n: number, of: number): [number, number] {
    const angle = i === 0 ? Math.PI + (n * 2 * Math.PI) / Math.max(of, 1) : ((-60 + i * 90 + n * 50) * Math.PI) / 180;
    const d = i === 0 ? (of === 1 ? 0 : face * 0.45) : radius(i);
    return [size / 2 + d * Math.cos(angle) - face / 2, size / 2 + d * Math.sin(angle) - face / 2];
  }
</script>

<span class="orbit" style="width: {size}px; height: {size}px" aria-hidden="true">
  <svg width={size} height={size} viewBox="0 0 {size} {size}">
    {#each Array(count) as _, i (i)}
      <circle cx={size / 2} cy={size / 2} r={radius(i) - 1} fill="none" stroke-width="1.2"
        stroke={i === 0 ? 'var(--accent)' : i === 1 ? 'color-mix(in oklab, var(--accent) 55%, transparent)' : 'color-mix(in oklab, var(--ink-3) 45%, transparent)'}
        stroke-dasharray={i === 0 ? undefined : '3 3'} />
    {/each}
  </svg>
  {#each rings.slice(0, 3) as faces, i (i)}
    {#each faces.slice(0, 6) as w, n (w.type + w.id)}
      {@const [x, y] = spot(i, n, faces.length)}
      <span class="spot" style="left: {x}px; top: {y}px"><Face who={w} size={face} {dark} /></span>
    {/each}
  {/each}
</span>

<style>
  .orbit { position: relative; display: inline-block; flex: none; }
  svg { position: absolute; inset: 0; }
  .spot { position: absolute; display: grid; }
</style>
