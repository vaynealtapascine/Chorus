<script lang="ts">
  import PageHeader from '../kit/PageHeader.svelte';
  import SectionLabel from '../kit/SectionLabel.svelte';
  import Segmented from '../kit/Segmented.svelte';
  import { appearance, setTheme, type ThemeMode } from '../../appearance.svelte';

  const themes: { id: ThemeMode; label: string }[] = [{ id: 'light', label: 'Light' }, { id: 'dark', label: 'Dark' }, { id: 'system', label: 'Follow device' }];
  let mode = $state<ThemeMode>(appearance.mode);
</script>

<section class="page">
  <PageHeader title="Appearance" sub="On this device" />
  <SectionLabel label="Theme" />
  <Segmented label="Theme" options={themes} bind:value={mode} onchange={setTheme} />
</section>

<style>
  .page { display: grid; gap: 12px; max-width: 720px; align-content: start; }
</style>
