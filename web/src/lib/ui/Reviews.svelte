<script lang="ts">
  // Concurrent switches from two devices, close in time (SYNC.md §5.7). Nothing was dropped;
  // this just asks which one was right. A configured account's cards come from the selected
  // front's own history fold (History screen only; never folded on Home's current path).
  import { groups, members } from '../data';
  import { namedReviewMerge } from '../front-reviews';
  import type { NamedHistory } from '../front-history';
  import { sync, type Projection } from '../sync/client';

  let { projection, front, named = null }: { projection: Projection; front?: string; named?: NamedHistory | null } = $props();

  type Who = { subject_type: string; subject_id: string; layer: string; is_primary: boolean };
  type Side = { id: string; occurred_at: number; device_id: string; entries: Who[] };
  const resolved = (id: string) => !!projection.rows.front_review?.[id]?.fields?.resolution;
  const open = $derived((named ? named.reviews : projection.reviews?.[sync.accountId] ?? []).filter((r) => !resolved(r.id)));
  const byId = $derived(new Map<string, Side>(named
    ? named.saved.switches.map((s) => [s.id, { ...s, entries: s.resulting_front.map(({ layer_id, ...e }) => ({ ...e, layer: layer_id })) }])
    : (projection.fronts[sync.accountId]?.switches ?? []).map((s) => [s.id, { ...s, entries: s.resulting_front.map(({ level, ...e }) => ({ ...e, layer: level })) }])));
  const names = $derived(
    new Map([
      ...members(projection).map((m) => [m.id, m.display_name ?? m.name] as const),
      ...groups(projection).map((g) => [g.id, g.name] as const),
    ]),
  );
  // legacy cards name who was fronting; a custom layer has no fronting role, so list everyone
  const label = (s?: Side) =>
    s ? s.entries.filter((e) => named || e.layer === 'front').map((e) => names.get(e.subject_id) ?? '?').join(' & ') || 'no one' : '?';
  const time = (s?: Side) => (s ? new Date(s.occurred_at).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : '');
  const device = (s?: Side) => (s?.device_id === sync.device?.device_id ? 'this device' : 'another device');

  function resolve(id: string, resolution: string) {
    sync.create('front.review_resolve', sync.accountScope, null, { review_id: id, resolution });
  }
  function keep(r: { id: string; switch_a: string; switch_b: string }, which: 'a' | 'b') {
    const drop = which === 'a' ? r.switch_b : r.switch_a;
    if (named) sync.create('front.change', sync.accountScope, front ?? sync.accountId, { action: 'retract', target_op_id: drop });
    else sync.create('front.retract', sync.accountScope, sync.newId(), { target_op_id: drop });
    resolve(r.id, which === 'a' ? 'keep_a' : 'keep_b');
  }
  function merge(r: { id: string; switch_a: string; switch_b: string }) {
    if (named) {
      const changes = namedReviewMerge(named.saved.switches, r);
      if (!changes) return;
      for (const payload of changes) sync.create('front.change', sync.accountScope, front ?? sync.accountId, payload);
      resolve(r.id, 'merge');
      return;
    }
    const seen = new Set<string>();
    const entries = [...(byId.get(r.switch_a)?.entries ?? []), ...(byId.get(r.switch_b)?.entries ?? [])].filter((e) => {
      const k = `${e.subject_type}:${e.subject_id}`;
      return seen.has(k) ? false : (seen.add(k), true);
    });
    sync.create('front.switch', sync.accountScope, sync.newId(), { entries: entries.map(({ layer, ...e }) => ({ ...e, level: layer })) });
    resolve(r.id, 'merge');
  }
</script>

{#each open as r (r.id)}
  {@const a = byId.get(r.switch_a)}
  {@const b = byId.get(r.switch_b)}
  <aside class="review" role="status">
    <p><strong>Two switches landed together.</strong> Both are kept — which was right?</p>
    <div class="pair">
      <div><span class="dev">{device(a)} · {time(a)}</span>{label(a)}</div>
      <div><span class="dev">{device(b)} · {time(b)}</span>{label(b)}</div>
    </div>
    <div class="actions">
      <button onclick={() => resolve(r.id, 'keep_both')}>Keep both</button>
      <button onclick={() => keep(r, 'a')}>Keep first</button>
      <button onclick={() => keep(r, 'b')}>Keep second</button>
      <button disabled={!!named && (!a || !b)} onclick={() => merge(r)}>Merge</button>
    </div>
  </aside>
{/each}

<style>
  .review {
    background: color-mix(in oklab, var(--warn) 12%, var(--surface));
    border: 1px solid color-mix(in oklab, var(--warn) 45%, var(--line));
    border-radius: var(--r-lg);
    padding: var(--s-4);
    display: grid;
    gap: var(--s-3);
  }
  p {
    margin: 0;
  }
  .pair {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: var(--s-3);
  }
  .pair div {
    display: grid;
    gap: 2px;
    background: var(--surface);
    border-radius: var(--r-sm);
    padding: var(--s-2) var(--s-3);
  }
  .dev {
    font-size: var(--fs-xs);
    color: var(--ink-3);
  }
  .actions {
    display: flex;
    flex-wrap: wrap;
    gap: var(--s-2);
  }
  .actions button {
    background: var(--surface);
    border: 1px solid var(--line);
    border-radius: var(--r-full);
    padding: var(--s-1) var(--s-3);
    cursor: pointer;
  }
</style>
