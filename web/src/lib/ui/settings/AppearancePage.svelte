<script lang="ts">
  // Appearance (Figma F10.2), on this device: a live preview, theme, accent and the reading rows.
  // Every choice applies at once. The rest of the settings sit behind Advanced (DESIGN §6).
  import FormCard from '../kit/FormCard.svelte';
  import PageHeader from '../kit/PageHeader.svelte';
  import SectionLabel from '../kit/SectionLabel.svelte';
  import Segmented from '../kit/Segmented.svelte';
  import Switch from '../kit/Switch.svelte';
  import ValueRow from '../kit/ValueRow.svelte';
  import { appearance, setAppearance, setTheme, type ThemeMode } from '../../appearance.svelte';
  import { ACCENTS, DENSITIES, TEXT_SIZES, type Accent, type Density, type TextSize } from '../../appearance-prefs';
  import { core } from '../../core';
  import { members } from '../../data';
  import type { Projection } from '../../sync/client';

  let { projection }: { projection: Projection } = $props();
  const themes: { id: ThemeMode; label: string }[] = [{ id: 'light', label: 'Light' }, { id: 'dark', label: 'Dark' }, { id: 'system', label: 'Follow device' }];
  let mode = $state<ThemeMode>(appearance.mode);
  let textSize = $state<TextSize>(appearance.textSize);
  let density = $state<Density>(appearance.density);
  // theirs is one member, ours another; a swatch's colour is what that accent is in this mode
  const people = $derived(members(projection).filter((m) => !m.archived));
  const them = $derived(people[1] ?? people[0]);
  const us = $derived(people[0]);
  const tone = (hex: string | undefined) => (appearance.memberColours && hex ? core.adaptColor(hex, appearance.dark).name : 'var(--ink)');
  const swatch: Record<Accent, [string, string]> = {
    teal: ['#247D73', '#42A397'], sunflower: ['#8A6A00', '#F0BD1B'], lime: ['#5A7A1E', '#A8CF6A'],
    lavender: ['#6E4FB8', '#B79CEB'], sky: ['#3A64B5', '#9DB8F0'],
  };
</script>

<section class="page">
  <PageHeader title="Appearance" sub="On this device" />
  <div class="preview" aria-hidden="true">
    <div class="bubble"><strong style="color: {tone(them?.color)}">{them?.display_name ?? them?.name ?? 'Amarynthe'}</strong><span>Keys are by the door.</span></div>
    <div class="bubble own"><strong style="color: {tone(us?.color)}">{us?.display_name ?? us?.name ?? 'Foxglove'}</strong><span>Got them, thank you!</span></div>
  </div>
  <SectionLabel label="Theme" />
  <Segmented label="Theme" options={themes} bind:value={mode} onchange={setTheme} />
  <SectionLabel label="Accent" />
  <div class="swatches" role="radiogroup" aria-label="Accent">
    {#each ACCENTS as a (a.id)}
      <button role="radio" aria-checked={appearance.accent === a.id} class:on={appearance.accent === a.id} onclick={() => setAppearance({ accent: a.id })}>
        <span class="dot" style="background: {swatch[a.id][appearance.dark ? 1 : 0]}"></span>{a.label}
      </button>
    {/each}
  </div>
  <SectionLabel label="Reading" />
  <FormCard>
    <Switch label="Member colours in chat" checked={appearance.memberColours} onchange={(on) => setAppearance({ memberColours: on })} />
  </FormCard>
  <SectionLabel label="Text size" />
  <Segmented label="Text size" options={[...TEXT_SIZES]} bind:value={textSize} onchange={(textSize) => setAppearance({ textSize })} />
  <SectionLabel label="Density" />
  <Segmented label="Density" options={[...DENSITIES]} bind:value={density} onchange={(density) => setAppearance({ density })} />
  <FormCard><ValueRow icon="sliders" label="Advanced" href="#/settings/advanced" /></FormCard>
</section>

<style>
  .page { display: grid; gap: var(--s-3); max-width: 720px; align-content: start; }
  .preview { display: grid; gap: 6px; padding: var(--s-3); border-radius: var(--r-md); background: var(--surface); }
  .bubble { display: grid; justify-self: start; padding: var(--s-2) var(--s-3); border-radius: var(--r-md); background: var(--surface-2); }
  .bubble.own { justify-self: end; background: var(--bubble-out); }
  .bubble strong { font-size: var(--fs-sm); }
  .bubble span { font-size: var(--fs-base); }
  .swatches { display: flex; flex-wrap: wrap; gap: 6px; }
  .swatches button { display: grid; justify-items: center; gap: var(--s-1); min-width: 64px; padding: var(--s-1) 2px; border: 0; border-radius: var(--r-sm); background: none; color: var(--ink-3); font: inherit; font-size: 11px; cursor: pointer; }
  .swatches button.on { color: var(--ink); font-weight: 700; }
  .dot { width: 40px; height: 40px; border-radius: 50%; border: 3px solid transparent; background-clip: content-box; padding: 0; box-shadow: 0 0 0 0 var(--ink); }
  .on .dot { box-shadow: 0 0 0 2px var(--ink); }
</style>
