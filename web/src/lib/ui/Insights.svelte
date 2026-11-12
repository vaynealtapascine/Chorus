<script lang="ts">
  import PageHeader from './kit/PageHeader.svelte';
  import { onMount } from 'svelte';
  import { members } from '../data';
  import { cofrontPairs, csv, frontDaily, localDay, namedFrontDays, switchesPerDay, windowStart } from '../insights';
  import { sync, type Projection } from '../sync/client';
  import { duration, insightColumns, insightStats, memberOrder, RANGES, share, switchDelta, type InsightRange } from '../insights-view';
  import Segmented from './kit/Segmented.svelte';
  import SectionLabel from './kit/SectionLabel.svelte';
  import Icon from './Icon.svelte';

  let { projection, selectedFront = $bindable(''), selectedLayer = $bindable('front'), savedView = $bindable(false) }:
    { projection: Projection; selectedFront?: string; selectedLayer?: string; savedView?: boolean } = $props();
  let range = $state<InsightRange>('week');
  let showData = $state(false);
  let now = $state(Date.now());
  onMount(() => { const timer = setInterval(() => (now = Date.now()), 60_000); return () => clearInterval(timer); });
  const spec = $derived(RANGES.find((r) => r.id === range)!);
  const days = $derived(spec.days);
  const timeZone = $derived(typeof projection.rows.system?.[sync.accountId]?.fields.timezone === 'string' ? projection.rows.system[sync.accountId].fields.timezone as string : undefined);
  const from = $derived(windowStart(now, days, timeZone));
  const fold = $derived(projection.fronts[sync.accountId]);
  const system = $derived(projection.front_systems?.[sync.accountId]?.system);
  const front = $derived(selectedFront && system?.fronts[selectedFront] ? selectedFront : sync.accountId);
  let retry = $state(0);
  const historyRead = $derived.by(() => {
    projection.front_systems; retry;
    try { return { data: system ? sync.frontHistory(front) : null, error: false }; }
    catch { return { data: null, error: true }; }
  });
  const named = $derived(historyRead.data);
  const loading = $derived(!!system && !named);
  const frontName = $derived(system?.fronts[front]?.name ?? 'Body');
  const layerName = $derived(system?.layers[selectedLayer]?.name ?? selectedLayer);
  const sourceIntervals = $derived((savedView ? named?.saved.intervals : named?.effective.intervals) ?? []);
  const layers = $derived.by(() => {
    const ids = new Set([...Object.keys(system?.layers ?? {}), ...sourceIntervals.map((i) => i.layer_id), selectedLayer]);
    return [...ids].map((id) => system?.layers[id] ?? { id, name: id, level: 2147483647, deleted: false })
      .sort((a,b) => a.level-b.level || a.id.localeCompare(b.id));
  });
  const layer = $derived(system ? selectedLayer : 'front');
  const people = $derived(new Map(members(projection).map((m) => [m.id, m])));
  const daily = $derived((system ? namedFrontDays(sourceIntervals, front, layer, from, now, timeZone)
    : frontDaily(fold?.intervals ?? [], from, now, timeZone)).filter((r) => r.subject_type === 'member' && r.level === layer));
  const displayIntervals = $derived(system ? sourceIntervals.map((i) => ({ ...i, level: i.layer_id })) : fold?.intervals ?? []);
  const pairs = $derived(cofrontPairs(displayIntervals, from, now, layer));
  const changes = $derived(system ? savedView ? named?.saved.switches ?? [] :
    (named?.effective.changes ?? []).map((c) => ({ occurred_at: c.occurred_at, retracted: false })) : fold?.switches ?? []);
  const switchDays = $derived(switchesPerDay(changes, from, now, timeZone));

  const name = (id: string) => people.get(id)?.display_name ?? people.get(id)?.name ?? 'Someone';
  const color = (id: string) => people.get(id)?.color ?? '#A09184';
  const hours = (seconds: number) => (seconds / 3600).toFixed(1);

  const columns = $derived(insightColumns(daily, range, from, now, timeZone));
  const maxColumn = $derived(Math.max(1, ...columns.map((c) => c.total)));
  const order = $derived(memberOrder(daily));
  const stats = $derived(insightStats(changes.filter((c) => !c.retracted).map((c) => c.occurred_at), displayIntervals as never, from, now, days, layer, timeZone));
  const top = $derived(order[0]);
  const chartTitle = $derived((system ? 'Time by ' : 'Front time by ') + (range === 'week' ? 'day' : range === 'month' ? 'week' : 'month'));
  const patterns = $derived.by(() => {
    const out: { title: string; line: string; icon: string }[] = [];
    if (stats.busiestHour !== null) out.push({ title: 'Busiest hour', line: `Most switches happen ${String(stats.busiestHour).padStart(2, '0')}:00–${String((stats.busiestHour + 1) % 24).padStart(2, '0')}:00`, icon: 'clock' });
    if (stats.longest && stats.longest.seconds >= 60) {
      const d = new Date(stats.longest.start);
      const on = range === 'week' ? d.toLocaleDateString(undefined, { weekday: 'long', timeZone }) : d.toLocaleDateString(undefined, { day: 'numeric', month: 'short', timeZone });
      out.push({ title: 'Longest front', line: `${name(stats.longest.id)}, ${duration(stats.longest.seconds)} on ${on}`, icon: 'history' });
    }
    return out;
  });
  const maxPair = $derived(Math.max(1, ...pairs.map((r) => r.seconds)));
  const allSwitchDays = $derived.by(() => {
    const counts = new Map(switchDays.map((r) => [r.day, r.count]));
    const out: { day: string; count: number }[] = [];
    for (let i = 0; i < days; i++) {
      const d = new Date(`${localDay(from, timeZone)}T00:00:00Z`);
      d.setUTCDate(d.getUTCDate() + i);
      const day = d.toISOString().slice(0, 10);
      out.push({ day, count: counts.get(day) ?? 0 });
    }
    return out;
  });
  const maxSwitches = $derived(Math.max(1, ...allSwitchDays.map((r) => r.count)));

  function download(filename: string, headers: string[], rows: (string | number)[][], includeLayer = true) {
    if (system) {
      const scopeHeaders = ['front_id', 'history'];
      const scope = [front, savedView ? 'own' : 'effective'];
      if (includeLayer && !headers.includes('layer_id')) { scopeHeaders.push('layer_id'); scope.push(layer); }
      headers = [...scopeHeaders, ...headers]; rows = rows.map((row) => [...scope, ...row]);
    }
    const url = URL.createObjectURL(new Blob([csv(headers, rows)], { type: 'text/csv;charset=utf-8' }));
    const link = document.createElement('a');
    link.href = url; link.download = filename; link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
</script>

<section class="insights-page">
  <PageHeader title="Insights" sub="For your own reflection · only your system sees this" />
  <Segmented label="Insight range" options={RANGES.map((r) => ({ id: r.id, label: r.label }))} bind:value={range} />
  {#if system}
    <details class="options">
      <summary>Options</summary>
      <div class="scope">
        <label>Front<select aria-label="Insight front" value={front} onchange={(e) => (selectedFront = e.currentTarget.value)}>
          {#each Object.values(system.fronts) as f (f.id)}<option value={f.id}>{f.name}{f.deleted ? ' (deleted)' : ''}</option>{/each}
        </select></label>
        <label>Time layer<select aria-label="Insight time layer" bind:value={selectedLayer}>
          {#each layers as l (l.id)}<option value={l.id}>{l.name}{l.deleted ? ' (deleted)' : ''}</option>{/each}
        </select></label>
        <label>History<select aria-label="Insight history" value={savedView ? 'own' : 'effective'} onchange={(e) => (savedView = e.currentTarget.value === 'own')}>
          <option value="effective">Front history</option><option value="own">Saved entries</option>
        </select></label>
      </div>
    </details>
    <p class="hint">{frontName} · {layerName} time · {savedView ? 'saved entries' : 'effective history'}</p>
  {/if}
  {#if historyRead.error}
    <div role="alert"><p>Could not load saved history.</p><button onclick={() => retry++}>Try again</button></div>
  {:else if loading}<p class="hint" role="status">Preparing insights…</p>{:else}

  <div class="tiles">
    <div class="tile"><span class="k">Switches</span><strong>{stats.switches}</strong><span class="s">{switchDelta(stats, spec.noun)}</span></div>
    <div class="tile"><span class="k">Most time</span><strong>{top ? name(top) : '—'}</strong>{#if top}<span class="s">{share(daily, top, from, now)}% of the {spec.noun}</span>{/if}</div>
    <div class="tile"><span class="k">Together</span><strong>{duration(stats.together, true)}</strong><span class="s">co-fronting</span></div>
  </div>

  <section class="chart">
    <div class="chart-head"><h2>{chartTitle}</h2><span class="hint">hours</span></div>
    <div class="cols" class:tight={columns.length > 8} role="group" aria-label={system ? 'Hours' : 'Front hours'}>
      {#each columns as col (col.key)}
        <div class="col" role="img" aria-label={`${col.label}: ${hours(col.total)} h`}>
          {#if col.total === 0}<span class="empty-col"></span>{/if}
          {#each col.parts as part (part.id)}<span class="seg-bit" style:background={color(part.id)} style:height={`${Math.max(2, part.seconds / maxColumn * 140)}px`} title={`${name(part.id)} · ${hours(part.seconds)} h`}></span>{/each}
        </div>
      {/each}
    </div>
    <div class="col-labels" class:tight={columns.length > 8}>{#each columns as col (col.key)}<span>{col.label}</span>{/each}</div>
    {#if !order.length}<p class="hint">{system ? 'No time in this range.' : 'No front time in this range.'}</p>{/if}
    <div class="legend">{#each order as id (id)}<span><i style:background={color(id)}></i>{name(id)}</span>{/each}</div>
  </section>
  <div class="buttons">
    <button onclick={() => (showData = !showData)} aria-expanded={showData}><Icon name="doc" size={16} />View data</button>
    <button onclick={() => download('front-by-day.csv', ['day', 'member_id', 'member', system ? 'layer_id' : 'level', 'seconds', 'hours'], daily.map((r) => [r.day, r.subject_id, name(r.subject_id), r.level, r.seconds, hours(r.seconds)]))}><Icon name="share" size={16} />Download CSV</button>
  </div>
  {#if showData}
    <section class="chart"><table><thead><tr><th>Day</th><th>Member</th><th>Hours</th></tr></thead><tbody>
      {#each [...daily].sort((a, b) => b.day.localeCompare(a.day) || a.subject_id.localeCompare(b.subject_id)) as row (`${row.day}:${row.subject_id}`)}<tr><td>{row.day}</td><td>{name(row.subject_id)}</td><td>{hours(row.seconds)}</td></tr>{/each}
    </tbody></table></section>
  {/if}

  {#if patterns.length}
    <SectionLabel label="Patterns" />
    {#each patterns as p (p.title)}
      <div class="pattern"><Icon name={p.icon} size={22} /><div><strong>{p.title}</strong><span>{p.line}</span></div></div>
    {/each}
  {/if}

  <section class="chart">
    <div class="chart-head"><h2>{system ? 'Time together' : 'Co-front pairs'}</h2><button onclick={() => download('cofront-pairs.csv', ['member_a_id', 'member_a', 'member_b_id', 'member_b', 'seconds', 'hours'], pairs.map((r) => [r.a, name(r.a), r.b, name(r.b), r.seconds, hours(r.seconds)]))}>Export CSV</button></div>
    <div class="bars" aria-label={system ? 'Hours together' : 'Hours fronting together'}>
      {#each pairs as pair (`${pair.a}:${pair.b}`)}
        <div class="bar-row"><span class="pair-label">{name(pair.a)} & {name(pair.b)}</span><div class="bar-track"><span class="single-bar" style:width={`${pair.seconds / maxPair * 100}%`}></span></div><span class="bar-value">{hours(pair.seconds)} h</span></div>
      {:else}<p class="hint">{system ? 'No time together in this range.' : 'No overlapping fronts in this range.'}</p>{/each}
    </div>
    <details><summary>View data</summary><table><thead><tr><th>First</th><th>Second</th><th>Hours</th></tr></thead><tbody>
      {#each pairs as pair (`${pair.a}:${pair.b}`)}<tr><td>{name(pair.a)}</td><td>{name(pair.b)}</td><td>{hours(pair.seconds)}</td></tr>{/each}
    </tbody></table></details>
  </section>

  <section class="chart">
    <div class="chart-head"><h2>{system ? 'Changes by day' : 'Switches per day'}</h2><button onclick={() => download('switches-per-day.csv', ['day', system ? 'changes' : 'switches'], allSwitchDays.map((r) => [r.day, r.count]), false)}>Export CSV</button></div>
    <div class="bars" aria-label={system ? 'Change count by day' : 'Switch count by day'}>
      {#each allSwitchDays as row (row.day)}
        <div class="bar-row"><span class="bar-label">{row.day.slice(5)}</span><div class="bar-track"><span class="single-bar" style:width={`${row.count / maxSwitches * 100}%`}></span></div><span class="bar-value">{row.count}</span></div>
      {/each}
    </div>
    <details><summary>View data</summary><table><thead><tr><th>Day</th><th>{system ? 'Changes' : 'Switches'}</th></tr></thead><tbody>
      {#each allSwitchDays as row (row.day)}<tr><td>{row.day}</td><td>{row.count}</td></tr>{/each}
    </tbody></table></details>
  </section>
  {/if}
</section>

<style>
  .scope { display: flex; flex-wrap: wrap; gap: var(--s-3); }
  .scope label { display: grid; gap: var(--s-1); flex: 1 1 10rem; min-width: 0; color: var(--ink-2); font-size: var(--fs-sm); }
  .scope select { min-height: 44px; min-width: 0; width: 100%; padding: var(--s-2); background: var(--surface); color: var(--ink); border: 1px solid var(--line); border-radius: var(--r-sm); font: inherit; }
  .insights-page { display: grid; gap: var(--s-5); }
  .chart-head { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: var(--s-3); }
  h2, p { margin: 0; }
  h2 { font-size: var(--fs-lg); }
  .hint { color: var(--ink-3); font-size: var(--fs-sm); }
  .chart { display: grid; gap: var(--s-3); border: 1px solid var(--line); border-radius: var(--r-lg); padding: var(--s-4); background: var(--surface); }
  .chart-head button { min-height: 44px; border: 1px solid var(--line); background: var(--surface-2); border-radius: var(--r-sm); padding: var(--s-1) var(--s-3); color: var(--ink); cursor: pointer; white-space: nowrap; }
  .options summary { cursor: pointer; color: var(--accent); font-size: var(--fs-sm); }
  .options .scope { margin-top: var(--s-2); }
  .tiles { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--s-2); }
  .tile { display: grid; gap: 2px; align-content: start; min-width: 0; padding: var(--s-3); border-radius: var(--r-md); background: var(--surface); }
  .tile .k { font-size: 11px; font-weight: 500; color: var(--ink-2); }
  .tile strong { font-size: 20px; line-height: 24px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .tile .s { font-size: 11px; line-height: 14px; color: var(--ink-3); }
  .cols { display: flex; align-items: flex-end; gap: 10px; height: 140px; }
  .cols.tight, .col-labels.tight { gap: var(--s-1); }
  .col { flex: 1; min-width: 0; height: 100%; display: flex; flex-direction: column-reverse; gap: 2px; padding: 0 3px; box-sizing: border-box; }
  .seg-bit { display: block; border-radius: 4px; }
  .empty-col { display: block; height: 3px; border-radius: 2px; background: var(--surface-2); }
  .col-labels { display: flex; gap: 10px; }
  .col-labels span { flex: 1; min-width: 0; text-align: center; font-size: 11px; color: var(--ink-3); white-space: nowrap; overflow: hidden; }
  .legend { display: flex; flex-wrap: wrap; gap: var(--s-1) var(--s-3); font-size: 11px; color: var(--ink-2); }
  .legend span { display: inline-flex; align-items: center; gap: 5px; }
  .legend i { width: 8px; height: 8px; border-radius: 50%; }
  .buttons { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-2); }
  .buttons button { display: flex; align-items: center; justify-content: center; gap: 6px; min-height: 44px; border: 0; border-radius: var(--r-control); background: var(--surface); color: var(--ink); font: inherit; font-size: var(--fs-md); font-weight: 600; cursor: pointer; }
  .pattern { display: flex; align-items: center; gap: var(--s-4); min-height: 62px; padding: var(--s-2) var(--s-1); color: var(--ink-2); }
  .pattern div { display: grid; }
  .pattern strong { font-size: var(--fs-base); font-weight: 400; color: var(--ink); }
  .pattern span { font-size: var(--fs-sm); color: var(--ink-3); }
  .bars { display: grid; gap: var(--s-1); }
  .bar-row { display: grid; grid-template-columns: minmax(3.2rem, auto) 1fr 3.8rem; gap: var(--s-2); align-items: center; font-size: var(--fs-xs); }
  .bar-label, .pair-label { color: var(--ink-2); white-space: nowrap; }
  .pair-label { min-width: 8rem; }
  .bar-track { height: 1rem; border-radius: var(--r-sm); background: var(--surface-2); overflow: hidden; }
  .single-bar { display: block; height: 100%; background: var(--accent); border-radius: var(--r-sm); }
  .bar-value { text-align: right; color: var(--ink-3); }
  details summary { cursor: pointer; color: var(--accent); font-size: var(--fs-sm); }
  table { width: 100%; border-collapse: collapse; margin-top: var(--s-2); font-size: var(--fs-sm); }
  th, td { padding: var(--s-2); border-bottom: 1px solid var(--line); text-align: left; }
  th { color: var(--ink-3); }
  @media (max-width: 520px) { .chart { padding: var(--s-3); } .pair-label { min-width: 5rem; white-space: normal; } .bar-row { grid-template-columns: minmax(3rem, auto) 1fr 3rem; } }
</style>
