<script lang="ts">
  // F8.4: preset, theme and width.
  import { DEFAULT_PALETTE, STYLES, type Def, type Style, type Theme, type Width } from '../../stage-def';
  import Segmented from '../kit/Segmented.svelte';
  import Label from './Label.svelte';

  let { def = $bindable(), dark }: { def: Def; dark: boolean } = $props();
  const themeNow = $derived<'dark' | 'light'>(def.render.theme === 'dark' || (def.render.theme === 'auto' && dark) ? 'dark' : 'light');
  const bars: Record<Style, [number, string, boolean][]> = {
    chorus: [[55, 'a', false], [45, 'b', true]], discord: [[80, 'f', false], [60, 'f', false], [70, 'f', false]], bubbles: [[50, 'a', false], [50, 'b', true]],
    card: [[100, 'c', false]], transcript: [[80, 'f', false], [60, 'f', false], [70, 'f', false]], minimal: [[80, 'f', false], [80, 'f', false]],
  };
</script>

<Label>Preset</Label>
<div class="presets" role="radiogroup" aria-label="Preset">
  {#each STYLES as [id, label] (id)}
    <button class="tile" class:on={def.render.style === id} role="radio" aria-checked={def.render.style === id} onclick={() => (def.render.style = id)}>
      <span class="sketch">{#each bars[id] as [w, kind, end], i (i)}<i class={kind} class:end style="width: {w}%"></i>{/each}</span>
      <span class="name">{label}</span>
    </button>
  {/each}
</div>
<Label>Theme</Label>
<Segmented label="Theme" value={def.render.theme === 'custom' ? 'custom' : themeNow}
  options={[{ id: 'dark', label: 'Dark' }, { id: 'light', label: 'Light' }, { id: 'custom', label: 'Custom' }]}
  onchange={(v) => { def.render.theme = v as Theme; if (v === 'custom' && !def.render.palette) def.render.palette = { ...DEFAULT_PALETTE }; }} />
{#if def.render.theme === 'custom' && def.render.palette}
  <div class="colours">
    {#each [['bg', 'Background'], ['ink', 'Text'], ['accent', 'Accent']] as const as [key, label] (key)}
      <label class="colour"><input type="color" value={def.render.palette[key]} aria-label={label} oninput={(e) => (def.render.palette = { ...def.render.palette!, [key]: e.currentTarget.value.toUpperCase() })} /><span>{label}</span></label>
    {/each}
  </div>
{/if}
<Label>Width</Label>
<Segmented label="Width" value={def.render.width} options={[{ id: 'phone', label: 'Phone' }, { id: 'square', label: 'Square' }, { id: 'wide', label: 'Wide' }]}
  onchange={(v) => (def.render.width = v as Width)} />

<style>
  .presets { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; }
  .tile { display: grid; gap: 6px; padding: 8px; border: 1.5px solid transparent; border-radius: 14px; background: var(--surface-2); color: var(--ink); font: inherit; cursor: pointer; }
  .tile.on { border-color: var(--accent); }
  .tile.on .name { color: var(--accent); }
  .name { font-size: 12px; font-weight: 600; text-align: center; }
  .sketch { display: flex; flex-direction: column; justify-content: center; gap: 3px; height: 36px; padding: 6px; border-radius: 8px; background: var(--bg); }
  .sketch i { display: block; height: 3px; border-radius: 3px; background: color-mix(in srgb, var(--ink-3) 45%, transparent); }
  .sketch i.a, .sketch i.b, .sketch i.c { height: 7px; background: var(--surface-3); }
  .sketch i.b { background: color-mix(in srgb, var(--accent) 60%, transparent); }
  .sketch i.c { height: 20px; }
  .sketch i.end { align-self: flex-end; }
  .colours { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; }
  .colour { display: flex; align-items: center; gap: 8px; min-height: 44px; padding: 0 10px; border-radius: 12px; background: var(--surface-2); font-size: 13px; color: var(--ink-2); cursor: pointer; }
  .colour input { width: 28px; height: 28px; padding: 0; border: 0; border-radius: 50%; background: none; cursor: pointer; flex: none; }
  .colour input::-webkit-color-swatch-wrapper { padding: 0; } .colour input::-webkit-color-swatch { border: 2px solid var(--surface); border-radius: 50%; }
</style>
