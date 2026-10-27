<script lang="ts">
  import { core } from '../core';
  import { fuzzy, groups, members } from '../data';
  import { doFrontSwitch, doSwitch, type Level, type Notify } from '../front.svelte';
  import { frontLayers, frontView } from '../front-view';
  import type { FrontPresence } from '../core/fronts';
  import { sync, type Projection } from '../sync/client';
  import AvatarImage from './AvatarImage.svelte';

  /** `front`: the named front to change (D-080); Body when absent. */
  let { projection, dark, onclose, front }: { projection: Projection; dark: boolean; onclose: () => void; front?: string } = $props();
  type Entry = FrontPresence;
  const target = $derived(front ?? sync.accountId);
  const configured = $derived(!!projection.front_systems?.[sync.accountId]);
  const layers = $derived(frontLayers(projection, sync.accountId));
  const layerName = (id: string) => layers.find((l) => l.id === id)?.name ?? id;
  const title = $derived(target === sync.accountId ? "Who's here?" : `Who's in ${frontView(projection, sync.accountId, target).name}?`);

  interface Cand {
    type: 'member' | 'group';
    id: string;
    name: string;
    color: string;
    glyph: string;
    avatarBlob?: string;
  }

  const cands: Cand[] = $derived([
    ...members(projection)
      .filter((m) => !m.deleted && !m.archived)
      .map((m) => ({ type: 'member' as const, id: m.id, name: m.display_name ?? m.name, color: m.color, glyph: m.sigils[0] ?? m.name[0], avatarBlob: m.avatar_blob })),
    ...groups(projection)
      .filter((g) => g.kind === 'subsystem')
      .map((g) => ({ type: 'group' as const, id: g.id, name: g.name, color: g.color ?? '#A09184', glyph: '◌', avatarBlob: g.avatar_blob })),
  ]);
  const byKey = $derived(new Map(cands.map((c) => [`${c.type}:${c.id}`, c])));

  // start from who is here now (a snapshot on open, deliberately not live)
  const initial = () => frontView(projection, sync.accountId, front ?? sync.accountId).entries.map((e) => ({ ...e })) as Entry[];
  let selected: Entry[] = $state(initial());
  let query = $state('');
  let note = $state('');
  let atText = $state('');
  let notify: Notify = $state('default');
  let more = $state(false);

  const key = (e: { subject_type?: string; type?: string; subject_id?: string; id?: string }) =>
    `${e.subject_type ?? e.type}:${e.subject_id ?? e.id}`;
  const list = $derived.by(() => {
    const q = query.trim();
    const scored = cands
      .map((c) => ({ c, s: fuzzy(q, c.name) }))
      .filter((x) => x.s !== null)
      .sort((a, b) => a.s! - b.s! || a.c.name.localeCompare(b.c.name));
    return scored.map((x) => x.c);
  });

  function toggle(c: Cand) {
    const k = `${c.type}:${c.id}`;
    if (selected.some((e) => key(e) === k)) {
      selected = selected.filter((e) => key(e) !== k);
    } else {
      const inner = layers[0]?.id ?? 'front';
      const first = !selected.some((e) => e.layer_id === inner);
      selected = [...selected, { subject_type: c.type, subject_id: c.id, layer_id: inner, is_primary: first }];
    }
    query = '';
  }

  function cycleLevel(i: number) {
    const e = selected[i];
    const ids = layers.map((l) => l.id);
    const next = ids[(ids.indexOf(e.layer_id) + 1) % ids.length] ?? e.layer_id;
    selected[i] = { ...e, layer_id: next, is_primary: next === ids[0] ? e.is_primary : false };
  }

  function makePrimary(i: number) {
    const inner = layers[0]?.id ?? 'front';
    selected = selected.map((e, j) => ({ ...e, is_primary: j === i, layer_id: j === i ? inner : e.layer_id }));
  }

  function move(i: number, d: -1 | 1) {
    const j = i + d;
    if (j < 0 || j >= selected.length) return;
    const s = [...selected];
    [s[i], s[j]] = [s[j], s[i]];
    selected = s;
  }

  function commit(entries: Entry[]) {
    const at = atText ? new Date(atText).getTime() : undefined;
    const inner = layers[0]?.id ?? 'front';
    const names = entries.filter((e) => e.layer_id === inner).map((e) => byKey.get(key(e))?.name ?? '?');
    const label = names.length ? `Switched to ${names.join(' & ')}` : 'Switched out';
    const opts = { note: note.trim() || undefined, at: Number.isFinite(at) ? at : undefined, notify };
    if (configured) doFrontSwitch(target, entries, label, opts);
    else doSwitch(entries.map(({ layer_id, ...e }) => ({ ...e, level: layer_id as Level })), label, opts);
    onclose();
  }

  function onkey(e: KeyboardEvent) {
    if (e.key === 'Escape') onclose();
    if (e.key === 'Enter' && list.length && query) {
      e.preventDefault();
      toggle(list[0]);
    }
  }
</script>

<div class="scrim" onclick={onclose} role="presentation"></div>
<div class="sheet" role="dialog" aria-modal="true" aria-label="Switch">
  <div class="top">
    <h2 class="display">{title}</h2>
    <button class="ghost" onclick={onclose}>Cancel</button>
  </div>

  {#if selected.length}
    <ol class="tray">
      {#each selected as e, i (key(e))}
        {@const c = byKey.get(key(e))}
        {@const col = core.adaptColor(c?.color ?? '#A09184', dark)}
        <li style="--ring: {col.ring}">
          <span class="avatar small" aria-hidden="true"><AvatarImage hash={c?.avatarBlob} glyph={c?.glyph ?? '?'} name={c?.name} /></span>
          <span class="nm" style="color: {col.name}">{c?.name ?? 'Unknown'}</span>
          <button class="lvl" data-level={e.layer_id === layers[0]?.id ? 'front' : e.layer_id} onclick={() => cycleLevel(i)} title="Change layer">{layerName(e.layer_id)}</button>
          <button class="icon" class:on={e.is_primary} onclick={() => makePrimary(i)} title="Primary" aria-label="Make primary">★</button>
          <button class="icon" onclick={() => move(i, -1)} aria-label="Move up" disabled={i === 0}>↑</button>
          <button class="icon" onclick={() => move(i, 1)} aria-label="Move down" disabled={i === selected.length - 1}>↓</button>
          <button class="icon" onclick={() => (selected = selected.filter((_, j) => j !== i))} aria-label="Remove">✕</button>
        </li>
      {/each}
    </ol>
  {/if}

  <!-- svelte-ignore a11y_autofocus -->
  <input class="search" bind:value={query} onkeydown={onkey} placeholder="Search members and subsystems" autofocus aria-label="Search" />

  <div class="list">
    {#each list as c (`${c.type}:${c.id}`)}
      {@const col = core.adaptColor(c.color, dark)}
      {@const on = selected.some((e) => key(e) === `${c.type}:${c.id}`)}
      <button class="cand" class:on style="--ring: {col.ring}; --tint: {col.tint}" onclick={() => toggle(c)}>
        <span class="avatar" aria-hidden="true"><AvatarImage hash={c.avatarBlob} glyph={c.glyph} name={c.name} /></span>
        <span style="color: {col.name}">{c.name}</span>
        {#if c.type === 'group'}<span class="tagline">subsystem</span>{/if}
      </button>
    {/each}
  </div>

  <button class="ghost more" onclick={() => (more = !more)}>{more ? 'Fewer options' : 'Note, time, notifications…'}</button>
  {#if more}
    <div class="opts">
      <label>Note <input bind:value={note} placeholder="Optional" /></label>
      <label>Happened at <input type="datetime-local" bind:value={atText} /> <span class="hint">leave empty for now</span></label>
      <label>
        Tell followers
        <select bind:value={notify}>
          <option value="default">As usual</option>
          <option value="silent">Silently</option>
          <option value="now">Right away</option>
          <option value="extra_delay">With extra delay</option>
        </select>
      </label>
    </div>
  {/if}

  <div class="actions">
    <button class="ghost" onclick={() => commit([])}>Switch out</button>
    <button class="primary" onclick={() => commit(selected)} disabled={!selected.length}>Switch</button>
  </div>
</div>

<style>
  .scrim {
    position: fixed;
    inset: 0;
    background: rgb(27 23 20 / 0.35);
    z-index: 30;
  }
  .sheet {
    position: fixed;
    z-index: 31;
    inset: auto 0 0 0;
    max-height: 88vh;
    overflow: auto;
    margin: 0 auto;
    max-width: 560px;
    background: var(--bg);
    border-radius: var(--r-lg) var(--r-lg) 0 0;
    box-shadow: var(--shadow-pop);
    padding: var(--s-5) var(--s-4) calc(var(--s-5) + env(safe-area-inset-bottom));
    display: grid;
    gap: var(--s-4);
  }
  @media (min-width: 641px) {
    .sheet {
      inset: 10vh 0 auto 0;
      border-radius: var(--r-lg);
    }
  }
  .top {
    display: flex;
    justify-content: space-between;
    align-items: center;
  }
  h2 {
    font-size: var(--fs-xl);
  }
  .tray {
    list-style: none;
    margin: 0;
    padding: 0;
    display: grid;
    gap: var(--s-2);
  }
  .tray li {
    display: flex;
    align-items: center;
    gap: var(--s-2);
    background: var(--surface);
    border: 1px solid var(--line);
    border-radius: var(--r-md);
    padding: var(--s-2) var(--s-3);
  }
  .nm {
    flex: 1;
    font-weight: 500;
  }
  .lvl {
    font-size: var(--fs-xs);
    border-radius: var(--r-full);
    border: 1px solid var(--line);
    background: var(--surface-2);
    padding: 2px var(--s-2);
    cursor: pointer;
    color: var(--ink-2);
  }
  .lvl[data-level='front'] {
    background: var(--accent-soft);
    color: var(--ink);
  }
  .icon {
    background: none;
    border: 0;
    cursor: pointer;
    color: var(--ink-3);
    width: 28px;
    height: 28px;
    border-radius: 50%;
  }
  .icon.on {
    color: var(--warn);
  }
  .icon:disabled {
    opacity: 0.3;
  }
  .search,
  .opts input,
  .opts select {
    font: inherit;
    color: var(--ink);
    background: var(--surface);
    border: 1px solid var(--line);
    border-radius: var(--r-md);
    padding: var(--s-3) var(--s-4);
  }
  .opts input,
  .opts select {
    border-radius: var(--r-sm);
    padding: var(--s-2) var(--s-3);
  }
  .list {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
    gap: var(--s-2);
    max-height: 40vh;
    overflow: auto;
  }
  .cand {
    display: flex;
    align-items: center;
    gap: var(--s-2);
    padding: var(--s-2);
    background: var(--surface);
    border: 1px solid var(--line);
    border-radius: var(--r-md);
    cursor: pointer;
    text-align: left;
  }
  .cand.on {
    background: var(--tint);
    border-color: var(--ring);
  }
  .tagline {
    margin-left: auto;
    font-size: var(--fs-xs);
    color: var(--ink-3);
  }
  .avatar {
    width: 32px;
    height: 32px;
    flex: none;
    display: grid;
    place-items: center;
    border-radius: 50%;
    background: var(--surface-2);
    box-shadow: 0 0 0 2px var(--ring);
  }
  .avatar.small {
    width: 28px;
    height: 28px;
  }
  .opts {
    display: grid;
    gap: var(--s-3);
  }
  .opts label {
    display: grid;
    gap: var(--s-1);
    font-size: var(--fs-sm);
    color: var(--ink-2);
  }
  .hint {
    font-size: var(--fs-xs);
    color: var(--ink-3);
  }
  .more {
    justify-self: start;
  }
  .actions {
    display: flex;
    justify-content: space-between;
    align-items: center;
  }
  .primary {
    background: var(--accent);
    color: var(--on-accent);
    border: 0;
    border-radius: var(--r-full);
    padding: var(--s-3) var(--s-6);
    font-weight: 600;
    cursor: pointer;
  }
  .primary:disabled {
    opacity: 0.5;
  }
  .ghost {
    background: none;
    border: 0;
    color: var(--accent);
    cursor: pointer;
  }
</style>
