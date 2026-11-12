<script lang="ts">
  // The default ceiling for new followers (D-084); the finer controls sit behind Advanced (DESIGN §6).
  import CeilingControls from '../CeilingControls.svelte';
  import FormCard from '../kit/FormCard.svelte';
  import PageHeader from '../kit/PageHeader.svelte';
  import Segmented from '../kit/Segmented.svelte';
  import SectionLabel from '../kit/SectionLabel.svelte';
  import Switch from '../kit/Switch.svelte';
  import { withPreset } from '../../bucket-controls';
  import { notifyPreset } from '../../core/pkg/chorus_wasm.js';
  import { prefOf, savePref } from '../../settings-api';
  import { sync, type Projection } from '../../sync/client';

  let { projection }: { projection: Projection } = $props();
  const legacy = $derived((projection.rows.account?.[sync.accountId]?.fields.settings ?? {}) as Record<string, unknown>);
  const ceiling = $derived((prefOf(projection.rows.pref, 'follow_ceiling') ?? legacy.follow_ceiling ?? {}) as Record<string, unknown>);
  const presets = [
    { id: 'close', label: 'Close' }, { id: 'gentle', label: 'Gentle' }, { id: 'private', label: 'Private' },
    { id: 'digest', label: 'Digest' }, { id: 'off', label: 'Off' },
  ];
  const presetValues = Object.fromEntries(presets.map((p) => [p.id, JSON.parse(notifyPreset(p.id))]));
  // sharing toggles and front/layer grants (D-084) are independent of the preset
  const KEPT = ['share_history', 'share_stats', 'fronts', 'layers'];
  const withoutSharing = (c: Record<string, unknown>) => Object.fromEntries(Object.entries(c).filter(([k]) => !KEPT.includes(k)));
  const preset = $derived(presets.find((p) => JSON.stringify(presetValues[p.id]) === JSON.stringify(withoutSharing(ceiling)))?.id ?? (Object.keys(ceiling).length ? 'custom' : 'gentle'));
  let chosen = $state('gentle');
  $effect(() => { chosen = preset; });
  const detail = (key: 'share_history' | 'share_stats', on: boolean) => savePref('follow_ceiling', { ...ceiling, [key]: on });
</script>

<section class="page">
  <PageHeader title="Sharing" sub={preset === 'custom' ? 'Custom' : null} />
  <SectionLabel label="Default for new followers" />
  <Segmented label="Default for new followers" options={presets} bind:value={chosen} onchange={(id) => savePref('follow_ceiling', withPreset(ceiling, presetValues[id]))} />
  <FormCard>
    <Switch label="Followers can look back at who fronted" checked={ceiling.share_history === true} onchange={(on) => detail('share_history', on)} />
    <Switch label="Followers can see fronting stats" checked={ceiling.share_stats === true} onchange={(on) => detail('share_stats', on)} />
  </FormCard>
  <details class="more">
    <summary>Advanced</summary>
    <CeilingControls {ceiling} inherited="Built-in default" omit={['share_history', 'share_stats']} onchange={(c) => savePref('follow_ceiling', c)} />
  </details>
</section>

<style>
  .page { display: grid; gap: 12px; max-width: 720px; align-content: start; }
  .more { padding: 4px; }
  summary { cursor: pointer; color: var(--accent); font-weight: 600; padding: 8px 0; }
</style>
