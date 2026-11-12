<script lang="ts">
  // Front history (Figma F3.7; Android History.kt): a lane timeline per member (day, week or
  // month), the changes as sentences, review cards when two devices switched together (F3.6), the
  // saved entries that can be edited, undone or restored, and Log a past switch (F3.5).
  import { frontLayers, frontList } from '../front-view';
  import type { FrontPresence } from '../core/fronts';
  import * as fd from '../fronting-display';
  import { frontingUi } from '../fronting-ui.svelte';
  import { whoKey, whoMap } from '../fronting-subjects';
  import { sync, type Projection } from '../sync/client';
  import ReviewRow from './ReviewRow.svelte';
  import LogPastSwitch from './LogPastSwitch.svelte';
  import Face from './Face.svelte';
  import Icon from './Icon.svelte';

  let { projection, dark }: { projection: Projection; dark: boolean } = $props();
  const DAY = 86_400_000;
  const ZOOMS = [{ id: 'day', label: 'Day', days: 1 }, { id: 'week', label: 'Week', days: 7 }, { id: 'month', label: 'Month', days: 30 }] as const;
  let zoom = $state<(typeof ZOOMS)[number]>(ZOOMS[0]);
  let dayOffset = $state(0);
  let logging = $state(false);
  let savedOpen = $state(false);
  let shown = $state(200);
  let error = $state('');
  // the open line, by its key: a line kept in $state would be a proxy, never === the line (CI1)
  let acting = $state<string | null>(null);
  const lineKey = (l: fd.StoryLine) => l.changeId + l.subjectId + l.text;
  let editAt = $state('');

  const system = $derived(projection.front_systems?.[sync.accountId]?.system);
  const configured = $derived(!!system);
  const display = $derived(fd.displayPrefs(projection.rows.pref, sync.accountId));
  const fronts = $derived(fd.ordered(frontList(projection, sync.accountId), sync.accountId, display));
  const front = $derived(fronts.find((f) => f.id === frontingUi.front)?.id ?? sync.accountId);
  const named = $derived.by(() => { projection.front_systems; return system ? sync.frontHistory(front) : null; });
  const loading = $derived(configured && !named);
  const fold = $derived(projection.fronts[sync.accountId]);
  const layers = $derived(frontLayers(projection, sync.accountId));
  const scene = $derived(fd.scene(layers, [], display, front, []));
  const order = $derived([...scene.all.filter((l) => !l.separate), ...scene.all.filter((l) => l.separate)].map((l) => l.id));
  const who = $derived(whoMap(projection));
  const name = (type: string, id: string) => who.get(whoKey(type, id))?.name ?? 'Someone';
  const layerOf = (id: string | null) => (id ? scene.all.find((l) => l.id === id) : undefined);
  type Saved = { id: string; occurred_at: number; entries: FrontPresence[]; resulting_front: FrontPresence[]; note: string | null; retracted: boolean };
  const asPresence = (e: { subject_type: string; subject_id: string; level?: string; layer_id?: string; is_primary: boolean }): FrontPresence =>
    ({ subject_type: e.subject_type as FrontPresence['subject_type'], subject_id: e.subject_id, layer_id: e.layer_id ?? e.level ?? '', is_primary: e.is_primary });
  const saved = $derived<Saved[]>(configured ? named?.saved.switches ?? []
    : (fold?.switches ?? []).map((s) => ({ ...s, entries: s.entries.map(asPresence), resulting_front: s.resulting_front.map(asPresence) })));
  const states = $derived<fd.FrontState[]>(configured
    ? (named?.effective.changes ?? []).filter((c) => !c.deleted && c.state !== 'unavailable').map((c) => ({ id: c.id, at: c.occurred_at, entries: c.entries }))
    : saved.filter((s) => !s.retracted).map((s) => ({ id: s.id, at: s.occurred_at, entries: s.resulting_front })));
  const spans = $derived(configured ? named?.effective.intervals ?? []
    : (fold?.intervals ?? []).map((i) => ({ ...i, layer_id: i.level })));
  const lines = $derived(fd.story(states, order, scene.all.filter((l) => !l.separate).at(-1)?.id ?? null, (id) => layerOf(id)?.name ?? id, name));
  const dayStart = $derived.by(() => { const d = new Date(); d.setHours(0, 0, 0, 0); d.setDate(d.getDate() - dayOffset); return d.getTime(); });
  const rangeEnd = $derived(dayStart + DAY);
  const rangeStart = $derived(rangeEnd - zoom.days * DAY);
  const now = Date.now();
  const inRange = $derived(lines.filter((l) => l.at >= rangeStart && l.at < rangeEnd).reverse());
  const visible = $derived(spans.filter((s) => (s.end_at ?? now) > rangeStart && s.start_at < rangeEnd));
  // a day zooms to the hours that were used (at least six); the longer views show it all
  const win = $derived.by((): [number, number] => {
    if (zoom.id !== 'day' || !visible.length) return [rangeStart, rangeEnd];
    const h3 = 3 * 3_600_000;
    let a = Math.floor((Math.min(...visible.map((s) => Math.max(s.start_at, rangeStart))) - rangeStart) / h3) * h3 + rangeStart;
    let b = Math.min(rangeEnd, Math.ceil((Math.max(...visible.map((s) => Math.min(s.end_at ?? now, rangeEnd))) - rangeStart) / h3) * h3 + rangeStart);
    if (b - a < 6 * 3_600_000) { a = Math.max(rangeStart, Math.min(a, b - 6 * 3_600_000)); b = Math.min(rangeEnd, Math.max(b, a + 6 * 3_600_000)); }
    return [a, b];
  });
  const lanes = $derived.by(() => {
    const by = new Map<string, typeof visible>();
    for (const s of visible) { const k = whoKey(s.subject_type, s.subject_id); by.set(k, [...(by.get(k) ?? []), s]); }
    return [...by.entries()].sort((a, b) => Math.min(...a[1].map((s) => s.start_at)) - Math.min(...b[1].map((s) => s.start_at))).slice(0, 12);
  });
  const ticks = $derived.by(() => {
    const [a, b] = win;
    const step = zoom.id === 'day' ? 3 * 3_600_000 : zoom.id === 'week' ? DAY : 7 * DAY;
    const out: number[] = [];
    for (let t = a; t <= b - (zoom.id === 'day' ? 0 : 1); t += step) out.push(t);
    return out;
  });
  const pct = (t: number) => ((Math.min(Math.max(t, win[0]), win[1]) - win[0]) / (win[1] - win[0])) * 100;
  const tick = (t: number) => zoom.id === 'day' ? new Date(t).toLocaleTimeString([], { hour: '2-digit', hour12: false }).slice(0, 2)
    : zoom.id === 'week' ? new Date(t).toLocaleDateString([], { weekday: 'short' }) : new Date(t).toLocaleDateString([], { day: 'numeric', month: 'short' });
  const stamp = (t: number) => zoom.id === 'day' ? new Date(t).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })
    : new Date(t).toLocaleString([], { weekday: 'short', hour: 'numeric', minute: '2-digit' });
  const colorOf = (type: string, id: string) => who.get(whoKey(type, id))?.color ?? '#A09184';

  function savedFor(line: fd.StoryLine): Saved | undefined {
    if (!configured) return saved.find((s) => s.id === line.changeId);
    const cause = named?.effective.changes.find((c) => c.id === line.changeId)?.causes.find((c) => c.front_id === front);
    return cause ? saved.find((s) => s.id === cause.op_id) : undefined;
  }
  function change(action: 'amend' | 'retract' | 'unretract', id: string, extra: Record<string, unknown> = {}) {
    try {
      if (configured) sync.create('front.change', sync.accountScope, front, { action, target_op_id: id, ...extra });
      else sync.create(`front.${action}`, sync.accountScope, sync.newId(), { target_op_id: id, ...extra });
      error = ''; return true;
    } catch (e) { error = e instanceof Error ? e.message : 'Could not save the change. Try again.'; return false; }
  }
  const local = (t: number) => new Date(t - new Date(t).getTimezoneOffset() * 60_000).toISOString().slice(0, 16);
</script>

{#if logging}
  <LogPastSwitch {projection} {dark} front={front} {named} onclose={() => (logging = false)} />
{:else}
<section class="page">
  <div class="bar">
    <a class="back" href="#/fronting" aria-label="Back to Fronting"><Icon name="back" size={22} /></a>
    <div class="titles"><strong>Front history</strong><span>{new Date(dayStart).toLocaleDateString([], { weekday: 'long', day: 'numeric', month: 'long' })}</span></div>
    <button class="icon-btn" aria-label="Log a past switch" onclick={() => (logging = true)}><Icon name="plus" size={22} /></button>
  </div>
  <div class="seg" role="tablist" aria-label="Zoom">
    {#each ZOOMS as z (z.id)}<button role="tab" aria-selected={zoom.id === z.id} class:on={zoom.id === z.id} onclick={() => (zoom = z)}>{z.label}</button>{/each}
  </div>
  {#if configured && fronts.length > 1}
    <div class="fronts">{#each fronts as f (f.id)}<button class:on={f.id === front} aria-pressed={f.id === front} onclick={() => { frontingUi.front = f.id; shown = 200; }}>{f.name}</button>{/each}</div>
  {/if}
  <ReviewRow count={(configured ? named?.reviews ?? [] : projection.reviews?.[sync.accountId] ?? []).filter((r) => !projection.rows.front_review?.[r.id]?.fields?.resolution).length} front={configured ? front : ''} />

  <div class="timeline" aria-label="Who was present" aria-busy={loading}>
    <div class="axis">
      <span class="nav"><button aria-label="Earlier" onclick={() => (dayOffset += zoom.days)}>‹</button>{#if dayOffset > 0}<button aria-label="Later" onclick={() => (dayOffset = Math.max(0, dayOffset - zoom.days))}>›</button>{/if}</span>
      <span class="ticks">{#each ticks.filter((t) => pct(t) <= 92) as t (t)}<span style="left: {pct(t)}%">{tick(t)}</span>{/each}</span>
    </div>
    {#each lanes as [k, ivs] (k)}
      {@const w = who.get(k)}
      <div class="lane">
        <span class="lane-name"><Face who={w} size={22} {dark} /><span>{w?.name ?? 'Someone'}</span></span>
        <span class="track">
          {#each ivs as i (i.start_at + i.layer_id + i.subject_id)}
            {@const l = layerOf(i.layer_id)}
            <span class="bar-seg" data-style={l?.style ?? 'faint'} style="left: {pct(i.start_at)}%; width: max(3px, {pct(i.end_at ?? now) - pct(i.start_at)}%); --c: {colorOf(i.subject_type, i.subject_id)}"
              title="{w?.name ?? ''} · {l?.name ?? i.layer_id} · {stamp(i.start_at)}"></span>
          {/each}
          {#if now >= win[0] && now <= win[1]}<span class="now" style="left: {pct(now)}%"></span>{/if}
        </span>
      </div>
    {:else}{#if !loading}<p class="muted">No one was here in this time.</p>{/if}{/each}
    <div class="legend">{#each scene.all.filter((l) => !l.separate).slice(0, 3) as l (l.id)}<span><i data-style={l.style}></i>{l.name}</span>{/each}</div>
  </div>

  <h2>{zoom.id !== 'day' ? `Last ${zoom.days} days` : dayOffset === 0 ? 'Today' : dayOffset === 1 ? 'Yesterday' : new Date(dayStart).toLocaleDateString([], { dateStyle: 'medium' })}</h2>
  {#if error}<p class="error" role="alert">{error}</p>{/if}
  {#if loading}<p class="muted" role="status">Loading saved history…</p>
  {:else if !inRange.length}<p class="muted">No changes recorded {zoom.id === 'day' ? 'this day' : 'in this time'}.</p>
  {:else}
    <ol class="list">
      {#each inRange.slice(0, shown) as line (lineKey(line))}
        {@const l = layerOf(line.layerId)}
        <li><button onclick={() => { acting = acting === lineKey(line) ? null : lineKey(line); const s = savedFor(line); editAt = s ? local(s.occurred_at) : ''; }} aria-expanded={acting === lineKey(line)}>
          <time>{stamp(line.at)}</time><Face who={who.get(whoKey(line.subjectType, line.subjectId))} size={34} {dark} />
          <span class="what">{line.text}</span>{#if l}<span class="tag" style={fd.inkVars(l.style, l.color)}>{l.name}</span>{/if}
        </button>
        {#if acting === lineKey(line)}
          {@const s = savedFor(line)}
          <div class="actions">
            {#if s}
              {#if s.note}<p class="note">{s.note}</p>{/if}
              <label>Time <input type="datetime-local" bind:value={editAt} /></label>
              <button class="link" disabled={!Number.isFinite(new Date(editAt).getTime())} onclick={() => change('amend', s.id, { occurred_at: new Date(editAt).getTime() }) && (acting = null)}>Save time</button>
              <button class="link" onclick={() => change('retract', s.id) && (acting = null)}>Undo this switch</button>
            {:else}<p class="muted">{configured ? 'Comes from the front it follows' : "This entry can't be edited here."}</p>{/if}
          </div>
        {/if}</li>
      {/each}
    </ol>
    {#if inRange.length > shown}<button class="link" onclick={() => (shown += 200)}>Show older</button>{/if}
  {/if}
  <button class="log" onclick={() => (logging = true)}><Icon name="history" size={20} />Log a past switch</button>

  <button class="saved-head" aria-expanded={savedOpen} onclick={() => (savedOpen = !savedOpen)}>
    <span><strong>Saved entries</strong></span><Icon name="chevron-right" size={18} />
  </button>
  {#if savedOpen}
    <ol class="saved">
      {#each [...saved].reverse().slice(0, shown) as s (s.id)}
        <li class:undone={s.retracted}>
          <span class="what">{(s.retracted ? s.entries : s.resulting_front).map((e) => `${name(e.subject_type, e.subject_id)} (${layerOf(e.layer_id)?.name ?? e.layer_id})`).join(' & ') || 'No one recorded'}{s.note ? ` — “${s.note}”` : ''}</span>
          <time>{new Date(s.occurred_at).toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' })}{s.retracted ? ' · undone' : ''}</time>
          <button class="link" onclick={() => change(s.retracted ? 'unretract' : 'retract', s.id)}>{s.retracted ? 'Redo' : 'Undo'}</button>
        </li>
      {:else}<li class="muted">No saved entries.</li>{/each}
    </ol>
  {/if}
</section>
{/if}

<style>
  .page { display: grid; gap: 10px; max-width: 720px; padding-bottom: var(--s-8); }
  .bar { display: flex; align-items: center; gap: var(--s-1); margin-left: -8px; }
  .back, .icon-btn { width: 44px; height: 44px; display: grid; place-items: center; color: var(--ink); border: 0; background: none; border-radius: 50%; cursor: pointer; }
  .titles { flex: 1; display: grid; padding-left: var(--s-1); }
  /* the desktop app bar already names the page */
  @media (min-width: 1024px) { .titles { visibility: hidden; } }
  .titles strong { font-size: 17px; font-weight: 600; }
  .titles span { color: var(--ink-3); font-size: var(--fs-xs); }
  .seg { display: flex; gap: 3px; padding: 3px; border-radius: var(--r-row); background: var(--surface-2); }
  .seg button { flex: 1; min-height: 36px; border: 0; border-radius: 9px; background: none; color: var(--ink-3); font: inherit; font-size: var(--fs-sm); font-weight: 500; cursor: pointer; }
  .seg button.on { background: var(--surface); color: var(--ink); font-weight: 600; }
  .fronts { display: flex; gap: var(--s-2); overflow-x: auto; }
  .fronts button { border: 1px solid var(--line); border-radius: var(--r-full); background: var(--surface); color: var(--ink); font: inherit; font-size: var(--fs-sm); font-weight: 600; padding: 7px var(--s-3); cursor: pointer; white-space: nowrap; }
  .fronts button.on { background: var(--accent-soft); border-color: var(--accent); color: var(--accent); }
  .timeline { background: var(--surface); border-radius: var(--r-md); padding: var(--s-3); display: grid; gap: var(--s-2); }
  .axis, .lane { display: grid; grid-template-columns: 96px 1fr; align-items: center; }
  .nav { display: flex; gap: var(--s-1); }
  .nav button { border: 0; background: none; color: var(--accent); font-size: var(--fs-lg); padding: 0 var(--s-2); cursor: pointer; }
  .ticks { position: relative; height: 16px; }
  .ticks span { position: absolute; top: 0; color: var(--ink-3); font-size: 11px; }
  .lane-name { display: flex; align-items: center; gap: 6px; min-width: 0; padding-right: 6px; font-size: var(--fs-sm); }
  .lane-name > span:last-child { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .track { position: relative; height: 22px; border-radius: 4px; background: var(--surface-2); }
  .bar-seg { position: absolute; top: 0; bottom: 0; border-radius: 4px; background: var(--c); }
  .bar-seg[data-style='dashed'] { background: color-mix(in oklab, var(--c) 45%, transparent); }
  .bar-seg[data-style='faint'] { background: none; border: 1.2px dashed var(--c); }
  .bar-seg[data-style='plain'] { background: var(--line); }
  .now { position: absolute; top: -4px; bottom: -4px; width: 2px; background: var(--accent); }
  .legend { display: flex; justify-content: center; gap: 14px; padding-top: var(--s-1); color: var(--ink-3); font-size: 11px; }
  .legend span { display: flex; align-items: center; gap: 5px; }
  .legend i { width: 14px; height: 10px; border-radius: 3px; background: var(--ink-3); }
  .legend i[data-style='dashed'] { background: color-mix(in oklab, var(--ink-3) 45%, transparent); }
  .legend i[data-style='faint'], .legend i[data-style='plain'] { background: none; border: 1px dashed var(--ink-3); }
  h2 { margin: var(--s-2) var(--s-1) 0; color: var(--accent); font-size: var(--fs-sm); font-weight: 600; }
  .muted { color: var(--ink-3); margin: var(--s-1); }
  .error { color: var(--danger); }
  .list { list-style: none; margin: 0; padding: 0; background: var(--surface); border-radius: var(--r-md); overflow: hidden; }
  .list li + li { border-top: 1px solid var(--line); }
  .list li > button { display: flex; align-items: center; gap: var(--s-3); width: 100%; padding: 10px var(--s-3); border: 0; background: none; color: var(--ink); font: inherit; text-align: left; cursor: pointer; }
  .list time { width: 64px; flex: none; color: var(--ink-3); font-size: var(--fs-sm); white-space: nowrap; }
  .what { flex: 1; min-width: 0; font-size: var(--fs-md); line-height: 20px; }
  .tag { font-size: 11px; line-height: 14px; font-weight: 700; padding: 1px 7px; border-radius: 8px; background: var(--l-tag-fill); color: var(--l-tag-ink); border: 1px var(--l-dash) var(--l-tag-border); white-space: nowrap; }
  .actions { display: flex; flex-wrap: wrap; align-items: center; gap: var(--s-2) var(--s-4); padding: 0 var(--s-3) var(--s-3) 64px; }
  .actions label { display: flex; gap: 6px; align-items: center; font-size: var(--fs-sm); color: var(--ink-2); }
  .actions input { font: inherit; color: var(--ink); background: var(--bg); border: 1px solid var(--line); border-radius: 8px; padding: 6px var(--s-2); }
  .note { margin: 0; width: 100%; color: var(--ink-2); font-size: 14px; }
  .link { border: 0; background: none; color: var(--accent); font: inherit; font-weight: 600; cursor: pointer; padding: 6px 0; }
  .link:disabled { color: var(--ink-3); }
  .log { display: flex; align-items: center; gap: 10px; padding: 10px var(--s-1); border: 0; background: none; color: var(--accent); font: inherit; font-size: var(--fs-md); font-weight: 500; cursor: pointer; }
  .saved-head { display: flex; align-items: center; gap: var(--s-2); padding: var(--s-2) var(--s-1); border: 0; background: none; font: inherit; text-align: left; cursor: pointer; color: var(--ink-3); }
  .saved-head > span { flex: 1; display: grid; }
  .saved-head strong { color: var(--ink-2); font-size: var(--fs-sm); }
  .saved { list-style: none; margin: 0; padding: 0; display: grid; gap: 6px; }
  .saved li { display: grid; grid-template-columns: 1fr auto; gap: 2px var(--s-3); padding: 10px var(--s-3); background: var(--surface); border-radius: var(--r-control); }
  .saved li time { color: var(--ink-3); font-size: var(--fs-xs); }
  .saved li .link { grid-row: 1 / 3; grid-column: 2; }
  .saved li.undone .what { color: var(--ink-3); text-decoration: line-through; }
</style>
