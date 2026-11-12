<script lang="ts">
  // F8.7: a fake name for each author and the shown times, for this stage only.
  import { shiftLabel, type Def } from '../../stage-def';
  import AvatarImage from '../AvatarImage.svelte';
  import Segmented from '../kit/Segmented.svelte';
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
  const localInput = (t: number) => new Date(t - new Date(t).getTimezoneOffset() * 60_000).toISOString().slice(0, 16);
  const mode = $derived(def.time.mode === 'shift' || def.time.mode === 'start' ? def.time.mode : '');
  function pick(next: string) {
    if (next === def.time.mode) def.time = { mode: 'real' };
    else if (next === 'shift') def.time = { mode: 'shift', offset_ms: 0 };
    else if (next === 'start') def.time = { mode: 'start', start: firstAt ?? Date.now() };
  }
  const minutes = $derived(def.time.mode === 'shift' ? def.time.offset_ms / 60_000 : 0);
</script>

{#if authors.length}
  <Label>Names</Label>
  {#each authors as a (a.id)}
    <div class="author">
      <span class="face" style="--c: {a.color}"><AvatarImage hash={a.blob} glyph={a.glyph} name={a.name} /></span>
      <span class="real">{a.name}</span>
      <span class="arrow" aria-hidden="true">›</span>
      <input placeholder={a.name} value={def.fake_names[a.id]?.label ?? ''} onchange={(e) => setFake(a.id, e.currentTarget.value)} aria-label="Show {a.name} as" />
    </div>
  {/each}
{/if}
<Label>Times</Label>
<Segmented label="Times" value={mode as 'shift' | 'start'} options={[{ id: 'shift', label: 'Shift all' }, { id: 'start', label: 'Start at' }]} onchange={pick} />
{#if def.time.mode === 'shift'}
  <Stepper value={shiftLabel(minutes)} label="Shift all times"
    minus={() => (def.time = { mode: 'shift', offset_ms: (minutes - 15) * 60_000 })} plus={() => (def.time = { mode: 'shift', offset_ms: (minutes + 15) * 60_000 })} />
{:else if def.time.mode === 'start'}
  <input type="datetime-local" class="when" value={localInput(def.time.start)} aria-label="Start time"
    onchange={(e) => { const t = new Date(e.currentTarget.value).getTime(); if (!Number.isNaN(t)) def.time = { mode: 'start', start: t }; }} />
{/if}

<style>
  .author { display: flex; align-items: center; gap: 10px; }
  .face { flex: none; display: inline-grid; place-items: center; width: 32px; height: 32px; border-radius: 50%; overflow: hidden; background: var(--c); }
  .real { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--ink-2); font-size: 15px; }
  .arrow { color: var(--ink-3); }
  input { flex: 1.2; min-width: 0; height: 40px; padding: 0 12px; border: 1px solid var(--line); border-radius: 10px; background: var(--surface-2); color: var(--ink); font: inherit; }
  .when { flex: none; justify-self: start; }
</style>
