<script lang="ts">
  // A row of colour circles (Android Swatches; F4.2 member colour, groups, custom fronts). With
  // `custom`, the last circle opens the browser's own colour picker for any other colour.
  import { BUCKET_SWATCHES } from '../../bucket-controls';

  let { value, onpick, colors = BUCKET_SWATCHES.slice(0, 8), custom = false, size = 32 }: {
    value?: string | null; onpick: (c: string) => void; colors?: readonly string[]; custom?: boolean; size?: number;
  } = $props();
  const same = (a?: string | null, b?: string | null) => !!a && !!b && a.toLowerCase() === b.toLowerCase();
  const own = $derived(!!value && !colors.some((c) => same(c, value)));
</script>

<div class="swatches" role="radiogroup" aria-label="Colour" style="--size: {size}px">
  {#each colors as c (c)}
    <button type="button" role="radio" aria-checked={same(c, value)} aria-label="Colour {c}" class="swatch" class:on={same(c, value)} style="background: {c}" onclick={() => onpick(c)}></button>
  {/each}
  {#if custom}
    <label class="swatch own" class:on={own} style={own ? `background: ${value}` : undefined} aria-label="Another colour">
      <input type="color" value={own ? value : '#888888'} onchange={(e) => onpick(e.currentTarget.value)} />
    </label>
  {/if}
</div>

<style>
  .swatches { display: flex; flex-wrap: wrap; gap: var(--s-2); }
  .swatch { position: relative; width: var(--size); height: var(--size); padding: 0; border-radius: 50%; border: 2px solid var(--surface); cursor: pointer; flex: none; box-sizing: border-box; }
  .swatch.on { border-color: var(--ink); }
  .own { background: conic-gradient(#f08c7f, #eeaa62, #86bfa5, #8fb8e8, #b99ce8, #f08c7f); overflow: hidden; }
  .own input { position: absolute; inset: 0; opacity: 0; width: 100%; height: 100%; cursor: pointer; }
</style>
