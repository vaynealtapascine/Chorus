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
  const deviceNames = $derived(new Map(Object.entries(projection.rows.device ?? {}).map(([id, row]) => [id, String(row.fields.name ?? '')])));
  const device = (s?: Side) => (s ? deviceNames.get(s.device_id) || (s.device_id === sync.device?.device_id ? 'this device' : 'another device') : '');
  let picked = $state<Record<string, 'a' | 'b'>>({});

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
    // backdated and silent: a merge corrects the past, it doesn't switch anyone now
    sync.create('front.switch', sync.accountScope, sync.newId(), { entries: entries.map(({ layer, ...e }) => ({ ...e, level: layer })), notify: 'silent' },
      { userTime: Math.max(byId.get(r.switch_a)?.occurred_at ?? 0, byId.get(r.switch_b)?.occurred_at ?? 0) || undefined });
    resolve(r.id, 'merge');
  }
</script>

{#each open as r (r.id)}
  {@const a = byId.get(r.switch_a)}
  {@const b = byId.get(r.switch_b)}
  <!-- Figma F3.6: two devices switched within a minute; tap the right one, or keep both or merge -->
  <aside class="review" role="status">
    <p>Two devices switched within a minute.</p>
    {#each [a, b] as side, i (i)}
      <button class="side" class:on={picked[r.id] === (i === 0 ? 'a' : 'b')} aria-pressed={picked[r.id] === (i === 0 ? 'a' : 'b')} disabled={!side}
        onclick={() => (picked = { ...picked, [r.id]: i === 0 ? 'a' : 'b' })}>
        <span class="dev">{device(side).toUpperCase()} · {time(side)}</span><strong>{label(side)}</strong>
      </button>
    {/each}
    <div class="actions">
      <button onclick={() => resolve(r.id, 'keep_both')}>Keep both</button>
      <button disabled={!a || !b || !picked[r.id]} title={picked[r.id] ? '' : 'Tap the one that was right first'} onclick={() => picked[r.id] && keep(r, picked[r.id])}>{picked[r.id] ? 'Keep this one' : 'Keep one'}</button>
      <button disabled={!!named && (!a || !b)} onclick={() => merge(r)}>Merge</button>
    </div>
  </aside>
{/each}

<style>
  .review { background: var(--surface); border-radius: 16px; padding: 14px; display: grid; gap: 10px; }
  p { margin: 0; font-size: 14px; }
  .side { display: grid; gap: 2px; text-align: left; background: var(--surface-2); border: 1px solid var(--line); border-radius: 12px; padding: 12px 16px; color: var(--ink); font: inherit; cursor: pointer; }
  .side.on { border-color: var(--accent); }
  .side strong { font-size: 16px; font-weight: 600; }
  .dev { font-size: 11px; font-weight: 700; color: var(--ink-2); letter-spacing: 0.02em; }
  .actions { display: flex; flex-wrap: wrap; gap: 8px; }
  .actions button { background: var(--surface-2); border: 0; border-radius: 8px; padding: 10px 14px; color: var(--ink); font: inherit; font-size: 13px; font-weight: 600; cursor: pointer; }
  .actions button:disabled { color: var(--ink-3); cursor: default; }
</style>
