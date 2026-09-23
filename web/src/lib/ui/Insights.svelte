<script lang="ts">
  import { onMount } from 'svelte';
  import { members } from '../data';
  import { cofrontPairs, csv, frontDaily, frontWeeks, localDay, switchesPerDay, windowStart, type DailyTime } from '../insights';
  import { sync, type Projection } from '../sync/client';

  let { projection }: { projection: Projection } = $props();
  let span = $state<'week' | 'month'>('week');
  let now = $state(Date.now());
  onMount(() => { const timer = setInterval(() => (now = Date.now()), 60_000); return () => clearInterval(timer); });
  const days = $derived(span === 'week' ? 7 : 28);
  const timeZone = $derived(typeof projection.rows.system?.[sync.accountId]?.fields.timezone === 'string' ? projection.rows.system[sync.accountId].fields.timezone as string : undefined);
  const from = $derived(windowStart(now, days, timeZone));
  const fold = $derived(projection.fronts[sync.accountId]);
  const people = $derived(new Map(members(projection).map((m) => [m.id, m])));
  const daily = $derived(frontDaily(fold?.intervals ?? [], from, now, timeZone).filter((r) => r.subject_type === 'member' && r.level === 'front'));
  const weekly = $derived(frontWeeks(daily));
  const pairs = $derived(cofrontPairs(fold?.intervals ?? [], from, now));
  const switchDays = $derived(switchesPerDay(fold?.switches ?? [], from, now, timeZone));

  const name = (id: string) => people.get(id)?.display_name ?? people.get(id)?.name ?? 'Someone';
  const color = (id: string) => people.get(id)?.color ?? '#A09184';
  const hours = (seconds: number) => (seconds / 3600).toFixed(1);

  function stacks(rows: DailyTime[], fillDays = false) {
    const byDay = new Map<string, DailyTime[]>();
    if (fillDays) {
      for (let i = 0; i < days; i++) {
        const day = new Date(`${localDay(from, timeZone)}T00:00:00Z`);
        day.setUTCDate(day.getUTCDate() + i);
        byDay.set(day.toISOString().slice(0, 10), []);
      }
    }
    for (const row of rows) byDay.set(row.day, [...(byDay.get(row.day) ?? []), row]);
    return [...byDay].sort((a, b) => a[0].localeCompare(b[0])).map(([day, parts]) => ({
      day, parts: parts.sort((a, b) => b.seconds - a.seconds), seconds: parts.reduce((sum, row) => sum + row.seconds, 0),
    }));
  }
  const dailyStacks = $derived(stacks(daily, true));
  const weeklyStacks = $derived(stacks(weekly));
  const maxDaily = $derived(Math.max(1, ...dailyStacks.map((r) => r.seconds)));
  const maxWeekly = $derived(Math.max(1, ...weeklyStacks.map((r) => r.seconds)));
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

  function download(filename: string, headers: string[], rows: (string | number)[][]) {
    const url = URL.createObjectURL(new Blob([csv(headers, rows)], { type: 'text/csv;charset=utf-8' }));
    const link = document.createElement('a');
    link.href = url; link.download = filename; link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
</script>

<section class="insights-page">
  <header>
    <h1 class="display">Insights</h1>
    <div class="seg" role="tablist" aria-label="Insight range">
      <button role="tab" aria-selected={span === 'week'} class:on={span === 'week'} onclick={() => (span = 'week')}>7 days</button>
      <button role="tab" aria-selected={span === 'month'} class:on={span === 'month'} onclick={() => (span = 'month')}>28 days</button>
    </div>
  </header>
  <p class="hint">Front history on this device · through {new Date(now).toLocaleString()}. Bars show member-hours, so simultaneous fronts add together.</p>

  <section class="chart">
    <div class="chart-head"><h2>Front time by day</h2><button onclick={() => download('front-by-day.csv', ['day', 'member_id', 'member', 'level', 'seconds', 'hours'], daily.map((r) => [r.day, r.subject_id, name(r.subject_id), r.level, r.seconds, hours(r.seconds)]))}>Export CSV</button></div>
    <div class="bars" aria-label="Front hours by day">
      {#each dailyStacks as row (row.day)}
        <div class="bar-row"><span class="bar-label">{row.day.slice(5)}</span><div class="bar-track"><div class="bar-stack" style:width={`${row.seconds / maxDaily * 100}%`}>
          {#each row.parts as part (part.subject_id)}<span style:background={color(part.subject_id)} style:width={`${part.seconds / (row.seconds || 1) * 100}%`} title={`${name(part.subject_id)} · ${hours(part.seconds)} h`}></span>{/each}
        </div></div><span class="bar-value">{hours(row.seconds)} h</span></div>
      {/each}
    </div>
    <details><summary>View data</summary><table><thead><tr><th>Day</th><th>Member</th><th>Hours</th></tr></thead><tbody>
      {#each daily as row (`${row.day}:${row.subject_id}`)}<tr><td>{row.day}</td><td>{name(row.subject_id)}</td><td>{hours(row.seconds)}</td></tr>{/each}
    </tbody></table></details>
  </section>

  <section class="chart">
    <div class="chart-head"><h2>Front time by week</h2><button onclick={() => download('front-by-week.csv', ['week_start', 'member_id', 'member', 'seconds', 'hours'], weekly.map((r) => [r.day, r.subject_id, name(r.subject_id), r.seconds, hours(r.seconds)]))}>Export CSV</button></div>
    <div class="bars" aria-label="Front hours by week">
      {#each weeklyStacks as row (row.day)}
        <div class="bar-row"><span class="bar-label">{row.day.slice(5)}</span><div class="bar-track"><div class="bar-stack" style:width={`${row.seconds / maxWeekly * 100}%`}>
          {#each row.parts as part (part.subject_id)}<span style:background={color(part.subject_id)} style:width={`${part.seconds / (row.seconds || 1) * 100}%`} title={`${name(part.subject_id)} · ${hours(part.seconds)} h`}></span>{/each}
        </div></div><span class="bar-value">{hours(row.seconds)} h</span></div>
      {:else}<p class="hint">No front time in this range.</p>{/each}
    </div>
    <details><summary>View data</summary><table><thead><tr><th>Week of</th><th>Member</th><th>Hours</th></tr></thead><tbody>
      {#each weekly as row (`${row.day}:${row.subject_id}`)}<tr><td>{row.day}</td><td>{name(row.subject_id)}</td><td>{hours(row.seconds)}</td></tr>{/each}
    </tbody></table></details>
  </section>

  <section class="chart">
    <div class="chart-head"><h2>Co-front pairs</h2><button onclick={() => download('cofront-pairs.csv', ['member_a_id', 'member_a', 'member_b_id', 'member_b', 'seconds', 'hours'], pairs.map((r) => [r.a, name(r.a), r.b, name(r.b), r.seconds, hours(r.seconds)]))}>Export CSV</button></div>
    <div class="bars" aria-label="Hours fronting together">
      {#each pairs as pair (`${pair.a}:${pair.b}`)}
        <div class="bar-row"><span class="pair-label">{name(pair.a)} & {name(pair.b)}</span><div class="bar-track"><span class="single-bar" style:width={`${pair.seconds / maxPair * 100}%`}></span></div><span class="bar-value">{hours(pair.seconds)} h</span></div>
      {:else}<p class="hint">No overlapping fronts in this range.</p>{/each}
    </div>
    <details><summary>View data</summary><table><thead><tr><th>First</th><th>Second</th><th>Hours</th></tr></thead><tbody>
      {#each pairs as pair (`${pair.a}:${pair.b}`)}<tr><td>{name(pair.a)}</td><td>{name(pair.b)}</td><td>{hours(pair.seconds)}</td></tr>{/each}
    </tbody></table></details>
  </section>

  <section class="chart">
    <div class="chart-head"><h2>Switches per day</h2><button onclick={() => download('switches-per-day.csv', ['day', 'switches'], allSwitchDays.map((r) => [r.day, r.count]))}>Export CSV</button></div>
    <div class="bars" aria-label="Switch count by day">
      {#each allSwitchDays as row (row.day)}
        <div class="bar-row"><span class="bar-label">{row.day.slice(5)}</span><div class="bar-track"><span class="single-bar" style:width={`${row.count / maxSwitches * 100}%`}></span></div><span class="bar-value">{row.count}</span></div>
      {/each}
    </div>
    <details><summary>View data</summary><table><thead><tr><th>Day</th><th>Switches</th></tr></thead><tbody>
      {#each allSwitchDays as row (row.day)}<tr><td>{row.day}</td><td>{row.count}</td></tr>{/each}
    </tbody></table></details>
  </section>
</section>

<style>
  .insights-page { display: grid; gap: var(--s-5); }
  header, .chart-head { display: flex; align-items: center; justify-content: space-between; gap: var(--s-3); }
  h1, h2, p { margin: 0; }
  h2 { font-size: var(--fs-lg); }
  .hint { color: var(--ink-3); font-size: var(--fs-sm); }
  .seg { display: inline-flex; padding: 2px; background: var(--surface-2); border-radius: var(--r-full); }
  .seg button { border: 0; border-radius: var(--r-full); background: none; color: var(--ink-2); padding: var(--s-1) var(--s-3); cursor: pointer; }
  .seg button.on { color: var(--ink); background: var(--surface); box-shadow: var(--shadow-sm); }
  .chart { display: grid; gap: var(--s-3); border: 1px solid var(--line); border-radius: var(--r-lg); padding: var(--s-4); background: var(--surface); }
  .chart-head button { border: 1px solid var(--line); background: var(--surface-2); border-radius: var(--r-sm); padding: var(--s-1) var(--s-3); color: var(--ink); cursor: pointer; white-space: nowrap; }
  .bars { display: grid; gap: var(--s-1); }
  .bar-row { display: grid; grid-template-columns: minmax(3.2rem, auto) 1fr 3.8rem; gap: var(--s-2); align-items: center; font-size: var(--fs-xs); }
  .bar-label, .pair-label { color: var(--ink-2); white-space: nowrap; }
  .pair-label { min-width: 8rem; }
  .bar-track { height: 1rem; border-radius: var(--r-sm); background: var(--surface-2); overflow: hidden; }
  .bar-stack { display: flex; height: 100%; }
  .bar-stack span, .single-bar { display: block; height: 100%; }
  .single-bar { background: var(--accent); border-radius: var(--r-sm); }
  .bar-value { text-align: right; color: var(--ink-3); }
  details summary { cursor: pointer; color: var(--accent); font-size: var(--fs-sm); }
  table { width: 100%; border-collapse: collapse; margin-top: var(--s-2); font-size: var(--fs-sm); }
  th, td { padding: var(--s-2); border-bottom: 1px solid var(--line); text-align: left; }
  th { color: var(--ink-3); }
  @media (max-width: 520px) { .chart { padding: var(--s-3); } .pair-label { min-width: 5rem; white-space: normal; } .bar-row { grid-template-columns: minmax(3rem, auto) 1fr 3rem; } }
</style>
