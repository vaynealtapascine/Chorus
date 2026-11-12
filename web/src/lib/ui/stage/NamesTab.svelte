<script lang="ts">
  // F8.7: a fake name and colour for each author and the shown times, for this stage only.
  import { shiftLabel, type Def } from '../../stage-def';
  import AvatarImage from '../AvatarImage.svelte';
  import Segmented from '../kit/Segmented.svelte';
  import FormCard from '../kit/FormCard.svelte';
  import Switch from '../kit/Switch.svelte';
  import Chip from '../kit/Chip.svelte';
  import Label from './Label.svelte';
  import Stepper from './Stepper.svelte';
  import type { StageAuthor } from './authors';

  let { def = $bindable(), authors, firstAt }: { def: Def; authors: StageAuthor[]; firstAt: number | null } = $props();

  function setFake(id: string, label: string) {
    const next = { ...def.fake_names };
    if (label.trim()) next[id] = { label: label.trim(), color: next[id]?.color ?? null };
    else delete next[id];
    def.fake_names = next;
  }
  const setColor = (id: string, color: string) => (def.fake_names = { ...def.fake_names, [id]: { ...def.fake_names[id], color } });
  const localInput = (t: number) => new Date(t - new Date(t).getTimezoneOffset() * 60_000).toISOString().slice(0, 16);
  const mode = $derived(def.time.mode === 'shift' || def.time.mode === 'start' || def.time.mode === 'manual' ? def.time.mode : '');
  function pick(next: string) {
    if (next === def.time.mode) def.time = { mode: 'real' };
    else if (next === 'shift') def.time = { mode: 'shift', offset_ms: 0 };
    else if (next === 'start') def.time = { mode: 'start', start: firstAt ?? Date.now() };
    else if (next === 'manual') def.time = { mode: 'manual', at: {} };
  }
  const minutes = $derived(def.time.mode === 'shift' ? def.time.offset_ms / 60_000 : 0);
</script>

{#if authors.length}
  <Label>Names</Label>
  {#each authors as a (a.id)}
    {@const fake = def.fake_names[a.id]}
    <div class="author">
      <span class="face" style="--c: {a.color}"><AvatarImage hash={a.blob} glyph={a.glyph} name={a.name} /></span>
      <span class="real">{a.name}</span>
      <span class="arrow" aria-hidden="true">›</span>
      <input placeholder={a.name} value={fake?.label ?? ''} onchange={(e) => setFake(a.id, e.currentTarget.value)} aria-label="Show {a.name} as" />
      <label class="dot" class:off={!fake} title="Colour for {fake?.label ?? a.name}">
        <input type="color" disabled={!fake} value={fake?.color ?? a.color} aria-label="Colour for {fake?.label ?? a.name}" oninput={(e) => setColor(a.id, e.currentTarget.value)} />
      </label>
    </div>
  {/each}
{/if}
<Label>Times</Label>
<Segmented label="Times" value={mode as 'shift' | 'start' | 'manual'} options={[{ id: 'shift', label: 'Shift all' }, { id: 'start', label: 'Start at' }, { id: 'manual', label: 'By hand' }]} onchange={pick} />
{#if def.time.mode === 'shift'}
  <Stepper value={shiftLabel(minutes)} label="Shift all times"
    minus={() => (def.time = { mode: 'shift', offset_ms: (minutes - 15) * 60_000 })} plus={() => (def.time = { mode: 'shift', offset_ms: (minutes + 15) * 60_000 })} />
{:else if def.time.mode === 'start'}
  <div class="start">
    <input type="datetime-local" class="when" value={localInput(def.time.start)} aria-label="Start time"
      onchange={(e) => { const t = new Date(e.currentTarget.value).getTime(); if (!Number.isNaN(t)) def.time = { mode: 'start', start: t }; }} />
    <span class="note">Gaps between messages are kept.</span>
  </div>
{:else if def.time.mode === 'manual' && Object.keys(def.time.at).length}
  <div class="chips"><Chip label="Reset {Object.keys(def.time.at).length} {Object.keys(def.time.at).length === 1 ? 'time' : 'times'}" onclick={() => (def.time = { mode: 'manual', at: {} })} /></div>
{/if}
<FormCard>
  <Switch label="Hide dates" checked={!!def.render.hide_dates} onchange={(on) => (def.render.hide_dates = on)} />
</FormCard>

<style>
  .author { display: flex; align-items: center; gap: 10px; }
  .face { flex: none; display: inline-grid; place-items: center; width: 32px; height: 32px; border-radius: 50%; overflow: hidden; background: var(--c); }
  .real { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--ink-2); font-size: var(--fs-md); }
  .arrow { color: var(--ink-3); }
  .author > input { flex: 1.2; min-width: 0; height: 40px; padding: 0 var(--s-3); border: 1px solid var(--line); border-radius: var(--r-sm); background: var(--surface-2); color: var(--ink); font: inherit; }
  .dot { flex: none; width: 32px; height: 32px; display: grid; place-items: center; cursor: pointer; }
  .dot.off { opacity: 0.4; cursor: default; }
  .dot input { width: 16px; height: 16px; padding: 0; border: 0; background: none; border-radius: 50%; cursor: inherit; }
  .dot input::-webkit-color-swatch-wrapper { padding: 0; } .dot input::-webkit-color-swatch { border: 0; border-radius: 50%; }
  .start { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
  .note { color: var(--ink-3); font-size: var(--fs-xs); line-height: 16px; }
  .chips { display: flex; gap: var(--s-2); }
</style>
