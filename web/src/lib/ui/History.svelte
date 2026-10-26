<script lang="ts">
  import { core } from '../core';
  import { groups, members } from '../data';
  import { LEVEL_LABEL } from '../front.svelte';
  import { sync, type Projection } from '../sync/client';

  let { projection, dark }: { projection: Projection; dark: boolean } = $props();
  let selected = $state('');
  let savedView = $state(false);
  let span = $state<'day' | 'week'>('day');
  let shown = $state(200);
  let error = $state('');
  let editing = $state<string | null>(null);
  let editAt = $state('');
  const now = Date.now();
  const to = now;
  const from = $derived(now - (span === 'day' ? 86_400_000 : 7 * 86_400_000));
  const system = $derived(projection.front_systems?.[sync.accountId]?.system);
  const front = $derived(selected && system?.fronts[selected] ? selected : sync.accountId);
  const named = $derived.by(() => { projection.front_systems; return system ? sync.frontHistory(front) : null; });
  const loading = $derived(!!system && !named);
  const fold = $derived(projection.fronts[sync.accountId]);
  const names = $derived(new Map<string, { name: string; color: string }>([
    ...members(projection).map((m) => [m.id, { name: m.display_name ?? m.name, color: m.color }] as const),
    ...groups(projection).map((g) => [g.id, { name: g.name, color: g.color ?? '#A09184' }] as const),
    ...Object.entries(projection.rows.custom_state ?? {}).filter(([, row]) => row.exists).map(([id, row]) =>
      [id, { name: String(row.fields.name ?? 'State'), color: String(row.fields.color ?? '#A09184') }] as const),
  ]));
  type Entry = { subject_type: string; subject_id: string; layer_id?: string; level?: string; is_primary: boolean };
  type Row = { id: string; occurred_at: number; entries: Entry[]; resulting_front: Entry[]; note: string | null; retracted: boolean; editable: boolean; state?: string; deleted?: boolean };
  const who = (e: { subject_id: string }) => names.get(e.subject_id)?.name ?? 'Someone';
  const layer = (e: { layer_id?: string; level?: string }) => e.layer_id ?? e.level ?? '';
  const layerName = (id: string) => system?.layers[id]?.name ?? LEVEL_LABEL[id as keyof typeof LEVEL_LABEL] ?? id;
  const intervals = $derived(system ? (savedView ? named?.saved.intervals : named?.effective.intervals) ?? [] : fold?.intervals ?? []);
  const lanes = $derived.by(() => {
    const inWin = intervals.filter((i) => (i.end_at ?? to) > from && i.start_at < to);
    const bySubject = new Map<string, typeof inWin>();
    for (const i of inWin) {
      if (!bySubject.has(i.subject_id)) bySubject.set(i.subject_id, []);
      bySubject.get(i.subject_id)!.push(i);
    }
    const total = (list: typeof inWin) => list.reduce((s, i) => s + Math.min(i.end_at ?? to, to) - Math.max(i.start_at, from), 0);
    return [...bySubject.entries()].sort((a, b) => total(b[1]) - total(a[1]));
  });
  const pct = (t: number) => ((Math.min(Math.max(t, from), to) - from) / (to - from)) * 100;
  const rows = $derived.by((): Row[] => {
    if (!system) return [...(fold?.switches ?? [])].reverse().map((s) => ({ ...s, editable: true }));
    if (savedView) return [...(named?.saved.switches ?? [])].reverse().map((s) => ({ ...s, editable: true }));
    return [...(named?.effective.changes ?? [])].reverse().map((s) => ({ ...s, resulting_front: s.entries, note: null, retracted: false, editable: false }));
  });
  const fmt = (t: number) => Number.isFinite(new Date(t).getTime()) ? new Date(t).toLocaleString([], { weekday: 'short', hour: '2-digit', minute: '2-digit' }) : 'Unknown time';
  const describe = (s: Row) => {
    const entries = s.retracted ? s.entries : s.resulting_front;
    if (system) {
      if (s.deleted) return 'Deleted front';
      if (s.state === 'unavailable') return 'Source unavailable';
      return entries.map((e) => `${who(e)} (${layerName(layer(e))})`).join(' & ') || 'No one recorded';
    }
    const main = entries.filter((e) => layer(e) === 'front').map(who).join(' & ') || 'No one fronting';
    const other = entries.filter((e) => layer(e) !== 'front').map((e) => `${who(e)} (${layerName(layer(e))})`);
    return other.length ? `${main} · ${other.join(', ')}` : main;
  };
  function chooseFront() { shown = 200; editing = null; error = ''; }
  function changeSaved(value: boolean) { savedView = value; chooseFront(); }
  function changeEntry(action: 'amend' | 'retract' | 'unretract', id: string, extra: Record<string, unknown> = {}) {
    try {
      if (system) sync.create('front.change', sync.accountScope, front, { action, target_op_id: id, ...extra });
      else sync.create(`front.${action}`, sync.accountScope, sync.newId(), { target_op_id: id, ...extra });
      error = ''; return true;
    } catch (e) { error = e instanceof Error ? e.message : 'Could not save the change. Try again.'; return false; }
  }
  function startEdit(s: Row) {
    editing = s.id;
    const d = new Date(s.occurred_at);
    if (!Number.isFinite(d.getTime())) d.setTime(now);
    editAt = new Date(d.getTime() - d.getTimezoneOffset() * 60_000).toISOString().slice(0, 16);
  }
  function saveEdit(s: Row) {
    const t = new Date(editAt).getTime();
    if (Number.isFinite(t) && changeEntry('amend', s.id, { occurred_at: t })) editing = null;
  }
</script>

<section class="page">
  <div class="head">
    <h1 class="display">History</h1>
    {#if system}
      <label class="front-choice">Front<select aria-label="Front history selection" value={front} onchange={(e) => { selected = e.currentTarget.value; chooseFront(); }}>
        {#each Object.values(system.fronts) as f (f.id)}<option value={f.id}>{f.name}{f.deleted ? ' (deleted)' : ''}</option>{/each}
      </select></label>
    {/if}
  </div>
  <div class="tools">
    {#if system}<div class="seg" role="group" aria-label="History view">
      <button class:on={!savedView} aria-pressed={!savedView} onclick={() => changeSaved(false)}>Front history</button>
      <button class:on={savedView} aria-pressed={savedView} onclick={() => changeSaved(true)}>Saved entries</button>
    </div>{/if}
    <div class="seg" role="group" aria-label="Timeline window">
      <button class:on={span === 'day'} aria-pressed={span === 'day'} onclick={() => (span = 'day')}>24 hours</button>
      <button class:on={span === 'week'} aria-pressed={span === 'week'} onclick={() => (span = 'week')}>7 days</button>
    </div>
  </div>
  {#if system}<p class="muted explanation">{savedView ? 'Edit this front’s saved records here. Past edits keep following active.' : 'Includes inherited changes. Open Saved entries to edit this front’s records.'}</p>{/if}
  {#if error}<p class="error" role="alert">{error}</p>{/if}
  {#if loading}<p class="muted" role="status">Loading saved history…</p>{/if}
  <div class="timeline" aria-label="Who was present" aria-busy={loading}>
    {#each lanes as [subject, ivs] (subject)}
      {@const n = names.get(subject)}{@const c = core.adaptColor(n?.color ?? '#A09184', dark)}
      <div class="lane"><span class="lane-name" style="color: {c.name}">{n?.name ?? 'Someone'}</span><div class="track">
        {#each ivs as i (i.start_at + layer(i))}<span class="bar" data-level={layer(i)} style="left: {pct(i.start_at)}%; width: {Math.max(0.4, pct(i.end_at ?? to) - pct(i.start_at))}%; background: {c.ring}" title="{n?.name ?? ''} · {layerName(layer(i))} · {fmt(i.start_at)}"></span>{/each}
      </div></div>
    {:else}{#if !loading}<p class="muted">Nothing recorded in this window.</p>{/if}{/each}
    <div class="axis"><span>{fmt(from)}</span><span>now</span></div>
  </div>
  <h2>{system ? savedView ? 'Saved entries' : 'Front changes' : 'Switches'}</h2>
  <ol class="log">
    {#each rows.slice(0, shown) as s (s.id)}
      <li class:undone={s.retracted}>
        <time>{fmt(s.occurred_at)}</time><span class="what">{s.retracted ? 'Undone: ' : ''}{describe(s)}{s.note ? ` — “${s.note}”` : ''}</span>
        {#if s.editable}
          {#if editing === s.id}<input type="datetime-local" bind:value={editAt} aria-label="New time" />
            <button class="ghost" disabled={!Number.isFinite(new Date(editAt).getTime())} onclick={() => saveEdit(s)}>Save</button>
            <button class="ghost" onclick={() => (editing = null)}>Cancel</button>
          {:else if s.retracted}<button class="ghost" onclick={() => changeEntry('unretract', s.id)}>Redo</button>
          {:else}<button class="ghost" onclick={() => startEdit(s)}>Edit time</button><button class="ghost" onclick={() => changeEntry('retract', s.id)}>Undo</button>{/if}
        {:else if s.state === 'overridden'}<span class="muted">Own override</span>{/if}
      </li>
    {:else}{#if !loading}<li class="muted">{system && savedView ? 'No saved entries for this front.' : 'No changes recorded yet.'}</li>{/if}{/each}
  </ol>
  {#if rows.length > shown}<button class="ghost older" onclick={() => (shown += 200)}>Show older entries</button>{/if}
</section>

<style>
  .page {
    display: grid;
    gap: var(--s-5);
  }
  .tools { display: flex; flex-wrap: wrap; justify-content: space-between; gap: var(--s-3); }
  .front-choice { display: flex; align-items: center; gap: var(--s-2); color: var(--ink-2); font-size: var(--fs-sm); min-width: 0; }
  .front-choice select { min-height: 44px; min-width: 0; max-width: min(16rem, 55vw); background: var(--surface); color: var(--ink); border: 1px solid var(--line); border-radius: var(--r-sm); padding: var(--s-2); font: inherit; }
  .explanation { margin: 0; font-size: var(--fs-sm); }
  .error { color: var(--danger); }
  .older { justify-self: center; }
  .head {
    display: flex;
    justify-content: space-between;
    align-items: center;
  }
  h1 {
    font-size: var(--fs-2xl);
  }
  h2 {
    font-size: var(--fs-sm);
    font-weight: 600;
    color: var(--ink-2);
  }
  .seg {
    display: flex;
    background: var(--surface-2);
    border-radius: var(--r-full);
    padding: 3px;
  }
  .seg button {
    border: 0;
    background: none;
    min-height: 44px;
    padding: var(--s-1) var(--s-3);
    border-radius: var(--r-full);
    cursor: pointer;
    color: var(--ink-2);
    font-size: var(--fs-sm);
  }
  .seg button.on {
    background: var(--surface);
    color: var(--ink);
  }
  .timeline {
    background: var(--surface);
    border: 1px solid var(--line);
    border-radius: var(--r-lg);
    padding: var(--s-4);
    display: grid;
    gap: var(--s-2);
  }
  .lane {
    display: grid;
    grid-template-columns: 6.5em 1fr;
    align-items: center;
    gap: var(--s-3);
  }
  .lane-name {
    font-size: var(--fs-sm);
    font-weight: 500;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .track {
    position: relative;
    height: 14px;
    background: var(--surface-2);
    border-radius: var(--r-full);
  }
  .bar {
    position: absolute;
    top: 0;
    bottom: 0;
    border-radius: var(--r-full);
  }
  .bar[data-level='cocon'] {
    opacity: 0.55;
  }
  .bar[data-level='present'] {
    opacity: 0.3;
  }
  .axis {
    display: flex;
    justify-content: space-between;
    margin-left: calc(6.5em + var(--s-3));
    font-size: var(--fs-xs);
    color: var(--ink-3);
  }
  .log {
    list-style: none;
    margin: 0;
    padding: 0;
    display: grid;
    gap: var(--s-2);
  }
  .log li {
    display: flex;
    flex-wrap: wrap;
    gap: var(--s-1) var(--s-3);
    align-items: baseline;
    padding: var(--s-3) var(--s-4);
    background: var(--surface);
    border: 1px solid var(--line);
    border-radius: var(--r-md);
  }
  .log li.undone .what {
    color: var(--ink-2);
    text-decoration: line-through;
  }
  time {
    font-size: var(--fs-sm);
    color: var(--ink-3);
    min-width: 7.5em;
  }
  .what {
    flex: 1 1 14rem;
    min-width: 0;
    overflow-wrap: anywhere;
  }
  .log input {
    font: inherit;
    color: var(--ink);
    background: var(--surface-2);
    border: 1px solid var(--line);
    border-radius: var(--r-sm);
    padding: 2px var(--s-2);
  }
  .ghost {
    background: none;
    border: 0;
    color: var(--accent);
    cursor: pointer;
    min-height: 44px;
    font-size: var(--fs-sm);
  }
  .ghost:disabled { opacity: 0.5; cursor: default; }
  .muted {
    color: var(--ink-3);
  }
</style>
