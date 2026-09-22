<script lang="ts">
  import { core } from '../core';
  import { groups, members } from '../data';
  import { LEVEL_LABEL, type Entry, type SwitchRow } from '../front.svelte';
  import { sync, type Projection } from '../sync/client';

  let { projection, dark }: { projection: Projection; dark: boolean } = $props();

  let span = $state<'day' | 'week'>('day');
  const now = Date.now();
  const to = $derived(now);
  const from = $derived(now - (span === 'day' ? 86_400_000 : 7 * 86_400_000));

  const fold = $derived(projection.fronts[sync.accountId]);
  const names = $derived(
    new Map<string, { name: string; color: string }>([
      ...members(projection).map((m) => [m.id, { name: m.display_name ?? m.name, color: m.color }] as const),
      ...groups(projection).map((g) => [g.id, { name: g.name, color: g.color ?? '#A09184' }] as const),
    ]),
  );
  const who = (e: { subject_id: string }) => names.get(e.subject_id)?.name ?? 'Someone';

  // lanes: one per subject seen in the window, busiest first
  const lanes = $derived.by(() => {
    const inWin = (fold?.intervals ?? []).filter((i) => (i.end_at ?? to) > from && i.start_at < to);
    const bySubject = new Map<string, typeof inWin>();
    for (const i of inWin) {
      if (!bySubject.has(i.subject_id)) bySubject.set(i.subject_id, []);
      bySubject.get(i.subject_id)!.push(i);
    }
    const total = (list: typeof inWin) =>
      list.reduce((s, i) => s + (Math.min(i.end_at ?? to, to) - Math.max(i.start_at, from)), 0);
    return [...bySubject.entries()].sort((a, b) => total(b[1]) - total(a[1]));
  });
  const pct = (t: number) => ((Math.min(Math.max(t, from), to) - from) / (to - from)) * 100;

  const switches = $derived([...(fold?.switches ?? [])].reverse() as SwitchRow[]);
  const fmt = (t: number) =>
    new Date(t).toLocaleString([], { weekday: 'short', hour: '2-digit', minute: '2-digit' });
  const describe = (s: SwitchRow) => {
    // an undone switch never took effect: describe what it contained, not the front at the time
    const shown = s.retracted ? s.entries : s.resulting_front;
    const front = shown.filter((e: Entry) => e.level === 'front').map(who);
    const other = shown.filter((e: Entry) => e.level !== 'front').map((e) => `${who(e)} (${LEVEL_LABEL[e.level]})`);
    const main = front.length ? front.join(' & ') : 'No one fronting';
    return other.length ? `${main} · ${other.join(', ')}` : main;
  };

  let editing = $state<string | null>(null);
  let editAt = $state('');
  function startEdit(s: SwitchRow) {
    editing = s.id;
    const d = new Date(s.occurred_at);
    editAt = new Date(d.getTime() - d.getTimezoneOffset() * 60_000).toISOString().slice(0, 16);
  }
  function saveEdit(s: SwitchRow) {
    const t = new Date(editAt).getTime();
    if (Number.isFinite(t)) sync.create('front.amend', sync.accountScope, sync.newId(), { target_op_id: s.id, occurred_at: t });
    editing = null;
  }
</script>

<section class="page">
  <div class="head">
    <h1 class="display">History</h1>
    <div class="seg" role="tablist">
      <button class:on={span === 'day'} onclick={() => (span = 'day')}>24 hours</button>
      <button class:on={span === 'week'} onclick={() => (span = 'week')}>7 days</button>
    </div>
  </div>

  <div class="timeline" aria-label="Who was present">
    {#each lanes as [subject, ivs] (subject)}
      {@const n = names.get(subject)}
      {@const c = core.adaptColor(n?.color ?? '#A09184', dark)}
      <div class="lane">
        <span class="lane-name" style="color: {c.name}">{n?.name ?? 'Someone'}</span>
        <div class="track">
          {#each ivs as i (i.start_at + i.level)}
            <span
              class="bar"
              data-level={i.level}
              style="left: {pct(i.start_at)}%; width: {Math.max(0.4, pct(i.end_at ?? to) - pct(i.start_at))}%; background: {c.ring}"
              title="{n?.name ?? ''} · {LEVEL_LABEL[i.level as 'front']} · {fmt(i.start_at)}"
            ></span>
          {/each}
        </div>
      </div>
    {:else}
      <p class="muted">Nothing recorded in this window.</p>
    {/each}
    <div class="axis"><span>{fmt(from)}</span><span>now</span></div>
  </div>

  <h2>Switches</h2>
  <ol class="log">
    {#each switches as s (s.id)}
      <li class:undone={s.retracted}>
        <time>{fmt(s.occurred_at)}</time>
        <span class="what">{s.retracted ? 'Undone: ' : ''}{describe(s)}{s.note ? ` — “${s.note}”` : ''}</span>
        {#if editing === s.id}
          <input type="datetime-local" bind:value={editAt} aria-label="New time" />
          <button class="ghost" onclick={() => saveEdit(s)}>Save</button>
        {:else if s.retracted}
          <button class="ghost" onclick={() => sync.create('front.unretract', sync.accountScope, sync.newId(), { target_op_id: s.id })}>Redo</button>
        {:else}
          <button class="ghost" onclick={() => startEdit(s)}>Edit time</button>
          <button class="ghost" onclick={() => sync.create('front.retract', sync.accountScope, sync.newId(), { target_op_id: s.id })}>Undo</button>
        {/if}
      </li>
    {:else}
      <li class="muted">No switches yet.</li>
    {/each}
  </ol>
</section>

<style>
  .page {
    display: grid;
    gap: var(--s-5);
  }
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
  .log li.undone {
    opacity: 0.55;
  }
  time {
    font-size: var(--fs-sm);
    color: var(--ink-3);
    min-width: 7.5em;
  }
  .what {
    flex: 1;
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
    font-size: var(--fs-sm);
  }
  .muted {
    color: var(--ink-3);
  }
</style>
