<script lang="ts">
  import { untrack } from 'svelte';
  // Switch (Figma F3.2; Android Switcher.kt). Each tap places someone on the innermost layer, the
  // next moves them one layer outward, and past the last layer they are out again. Replace front
  // starts empty and becomes the whole front; Add to front starts from who is here and changes only
  // who was tapped. `addTo` opens in Add mode with the first tap landing on that layer.
  import { fuzzy } from '../data';
  import { doFrontSwitch, doSwitch, place, type Level, type Notify, type Placement } from '../front.svelte';
  import { frontLayers, frontView } from '../front-view';
  import type { FrontPresence } from '../core/fronts';
  import * as fd from '../fronting-display';
  import { ownMembers, whoKey, whoMap, type Who } from '../fronting-subjects';
  import { groups } from '../data';
  import { sync, type Projection } from '../sync/client';
  import Face from './Face.svelte';
  import Icon from './Icon.svelte';
  import EmptyState from './kit/EmptyState.svelte';

  let { projection, dark, onclose, front, addTo, handover = false }: {
    projection: Projection; dark: boolean; onclose: () => void; front?: string; addTo?: string; handover?: boolean;
  } = $props();
  const target = $derived(front ?? sync.accountId);
  const configured = $derived(!!projection.front_systems?.[sync.accountId]);
  const view = $derived(frontView(projection, sync.accountId, target));
  const display = $derived(fd.displayPrefs(projection.rows.pref, sync.accountId));
  const scene = $derived(fd.scene(frontLayers(projection, sync.accountId), view.entries, display, target, []));
  // the cycle: nested layers inner to outer, then the separately listed ones
  const cycle = $derived([...scene.all.filter((l) => !l.separate), ...scene.all.filter((l) => l.separate)]);
  const who = $derived(whoMap(projection));
  // a snapshot on open, deliberately not live
  const current: FrontPresence[] = untrack(() => frontView(projection, sync.accountId, front ?? sync.accountId).entries.map((e) => ({ ...e })));
  let add = $state(untrack(() => addTo !== undefined));
  let placed: FrontPresence[] = $state(untrack(() => (addTo !== undefined ? current.map((e) => ({ ...e })) : [])));
  let query = $state('');
  let searching = $state(false);
  let options = $state(untrack(() => handover));
  let note = $state('');
  let atText = $state('');
  let notify: Notify = $state('default');

  const subjects = $derived.by((): Who[] => {
    const recents = new Map<string, number>();
    (projection.fronts[sync.accountId]?.switches ?? []).forEach((s, i) => s.resulting_front.forEach((e) => recents.set(e.subject_id, i)));
    const members = ownMembers(projection).sort((a, b) => (recents.get(b.id) ?? -1) - (recents.get(a.id) ?? -1));
    const subs = groups(projection).filter((g) => g.kind === 'subsystem').map((g) => who.get(whoKey('group', g.id))).filter((w): w is Who => !!w);
    const states = [...who.values()].filter((w) => w.type === 'state');
    const all = [...members, ...subs, ...states];
    const q = query.trim();
    if (!q) return all;
    return all.map((w) => ({ w, s: fuzzy(q, w.name) })).filter((x) => x.s !== null).sort((a, b) => a.s! - b.s!).map((x) => x.w);
  });
  const placedOf = (w: Who) => placed.find((e) => e.subject_type === w.type && e.subject_id === w.id);
  const layerOf = (id: string) => cycle.find((l) => l.id === id);

  function mode(toAdd: boolean) {
    add = toAdd;
    placed = toAdd ? current.map((e) => ({ ...e })) : [];
  }
  function tap(w: Who) {
    const i = placed.findIndex((e) => e.subject_type === w.type && e.subject_id === w.id);
    const ids = cycle.map((l) => l.id);
    if (i < 0) {
      placed = [...placed, { subject_type: w.type, subject_id: w.id, layer_id: (add && addTo) || ids[0] || 'front', is_primary: false }];
    } else {
      const next = ids.indexOf(placed[i].layer_id) + 1;
      placed = next > 0 && next < ids.length ? placed.map((e, j) => (j === i ? { ...e, layer_id: ids[next], is_primary: false } : e)) : placed.filter((_, j) => j !== i);
    }
  }
  function withPrimary(entries: FrontPresence[]): FrontPresence[] {
    const inner = cycle[0]?.id;
    let p = entries.findIndex((e) => e.is_primary && e.layer_id === inner);
    if (p < 0) p = entries.findIndex((e) => e.layer_id === inner);
    return entries.map((e, i) => ({ ...e, is_primary: i === p }));
  }
  const changes = $derived.by((): Placement[] => {
    if (!add) return [];
    const moved = placed.flatMap((e): Placement[] => {
      const was = current.find((c) => c.subject_type === e.subject_type && c.subject_id === e.subject_id);
      return was?.layer_id === e.layer_id ? [] : [{ type: e.subject_type, id: e.subject_id, layer: e.layer_id, present: !!was }];
    });
    const gone = current.filter((c) => !placed.some((e) => e.subject_type === c.subject_type && e.subject_id === c.subject_id))
      .map((c): Placement => ({ type: c.subject_type, id: c.subject_id, layer: null, present: true }));
    return [...moved, ...gone];
  });
  const name = (type: string, id: string) => who.get(whoKey(type, id))?.name ?? 'Someone';
  const summary = $derived(add
    ? changes.map((c) => `${name(c.type, c.id)} ${c.layer ? (layerOf(c.layer)?.name ?? c.layer).toLowerCase() : 'away'}`).join(' · ')
    : placed.map((e) => `${name(e.subject_type, e.subject_id)} ${(layerOf(e.layer_id)?.name ?? e.layer_id).toLowerCase()}`).join(' · '));
  const at = $derived(atText ? new Date(atText).getTime() : undefined);
  const canSwitch = $derived(add ? changes.length > 0 : placed.length > 0);

  function commit(entries: FrontPresence[]) {
    const when = at !== undefined && Number.isFinite(at) ? Math.min(at, Date.now()) : undefined;
    if (add) {
      place(changes, `Changed ${view.name}: ${changes.map((c) => name(c.type, c.id)).join(', ')}`, configured ? target : null, current, cycle[0]?.id, when);
    } else {
      const inner = entries.filter((e) => e.layer_id === cycle[0]?.id).map((e) => name(e.subject_type, e.subject_id));
      const label = entries.length ? `Switched ${view.name} to ${(inner.length ? inner : entries.map((e) => name(e.subject_type, e.subject_id))).join(' & ')}` : `Switched ${view.name} out`;
      const opts = { note: note.trim() || undefined, at: when, notify };
      if (configured) doFrontSwitch(target, entries, label, opts);
      else doSwitch(entries.map(({ layer_id, ...e }) => ({ ...e, level: layer_id as Level })), label, opts);
    }
    onclose();
  }
  function onkey(e: KeyboardEvent) {
    if (e.key === 'Enter' && query && subjects.length) { e.preventDefault(); tap(subjects[0]); query = ''; }
  }
</script>

<svelte:window onkeydown={(e) => e.key === 'Escape' && onclose()} />
<div class="scrim" onclick={onclose} role="presentation"></div>
<div class="sheet" role="dialog" aria-modal="true" aria-label="Switch {view.name}">
  <div class="handle" aria-hidden="true"></div>
  <header>
    <div class="titles"><h2>Switch · {view.name}</h2><p>Tap to place someone. Tap again to move them outward.</p></div>
    <button class="icon-btn" aria-label={searching ? 'Close search' : 'Search'} onclick={() => { searching = !searching; query = ''; }}><Icon name="search" size={22} /></button>
    <button class="icon-btn" aria-label="Close" onclick={onclose}><Icon name="close" size={22} /></button>
  </header>
  <div class="seg" role="tablist" aria-label="How to switch">
    <button role="tab" aria-selected={!add} class:on={!add} onclick={() => mode(false)}>Replace front</button>
    <button role="tab" aria-selected={add} class:on={add} onclick={() => mode(true)}>Add to front</button>
  </div>
  <!-- svelte-ignore a11y_autofocus -->
  {#if searching}<input class="search" bind:value={query} onkeydown={onkey} placeholder="Search {subjects.length} members" aria-label="Search members" autofocus />{/if}
  <div class="grid">
    {#each subjects as w (whoKey(w.type, w.id))}
      {@const e = placedOf(w)}{@const l = e ? layerOf(e.layer_id) : undefined}
      <button class="pick" class:on={!!l} style={l ? fd.inkVars(l.style, l.color) : ''} data-style={l?.style} onclick={() => tap(w)}
        aria-label="{w.name}{l ? `, ${l.name}` : ''}">
        <span class="ring"><Face who={w} size={52} {dark} /></span>
        <span class="nm">{w.name}</span>
        {#if l}<span class="tag">{l.name}</span>{:else}<span class="tag empty"></span>{/if}
      </button>
    {:else}<div class="none"><EmptyState icon="members" label={query ? 'No one matches' : 'No members yet'} /></div>{/each}
  </div>
  <div class="start">
    <button class="when" onclick={() => (options = !options)}>
      <Icon name="clock" size={18} /><span>{at && Number.isFinite(at) ? `Starting ${new Date(at).toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' })}` : 'Starting now'}</span>
      <strong>{options ? 'Done' : 'Change'}</strong>
    </button>
    {#if options}
      <div class="opts">
        <label>Happened at<input type="datetime-local" bind:value={atText} max={new Date(Date.now() - new Date().getTimezoneOffset() * 60_000).toISOString().slice(0, 16)} /></label>
        {#if !add}
          <label>{handover ? "Handover note for whoever's next" : 'Note (optional)'}<input bind:value={note} /></label>
          <label>Alerts<select bind:value={notify}><option value="default">Usual alerts</option><option value="silent">Silent</option><option value="now">Alert now</option><option value="extra_delay">Delay</option></select></label>
        {/if}
      </div>
    {/if}
    {#if !add && !placed.length && current.length}<button class="out" onclick={() => commit([])}>Switch everyone out of {view.name}</button>{/if}
  </div>
  <button class="confirm" disabled={!canSwitch} onclick={() => commit(withPrimary(placed))}>
    <strong>Switch</strong><span>{summary || 'Tap someone to place them'}</span>
  </button>
</div>

<style>
  .scrim { position: fixed; inset: 0; z-index: 30; background: rgba(0, 0, 0, 0.55); }
  .sheet { position: fixed; z-index: 31; inset: auto 0 0 0; margin: 0 auto; max-width: 560px; max-height: 92vh; overflow: auto; background: var(--surface);
    border-radius: 20px 20px 0 0; box-shadow: var(--shadow-pop); padding-bottom: env(safe-area-inset-bottom); display: grid; }
  @media (min-width: 641px) { .sheet { inset: 6vh 0 auto 0; border-radius: 20px; } }
  .handle { width: 36px; height: 4px; border-radius: 2px; background: var(--line); margin: 8px auto 4px; }
  header { display: flex; align-items: center; gap: 4px; padding: 6px 8px 4px 20px; }
  .titles { flex: 1; min-width: 0; }
  h2 { margin: 0; font-size: 18px; line-height: 24px; font-weight: 700; }
  .titles p { margin: 0; color: var(--ink-3); font-size: 12px; line-height: 16px; }
  .icon-btn { width: 44px; height: 44px; display: grid; place-items: center; border: 0; background: none; color: var(--ink-2); border-radius: 50%; cursor: pointer; }
  .seg { display: flex; gap: 3px; margin: 8px 16px 6px; padding: 3px; border-radius: 12px; background: var(--surface-2); }
  .seg button { flex: 1; padding: 8px; min-height: 36px; border: 0; border-radius: 9px; background: none; color: var(--ink-3); font: inherit; font-size: 13px; font-weight: 500; cursor: pointer; }
  .seg button.on { background: var(--surface); color: var(--ink); font-weight: 600; }
  .search { margin: 4px 16px; font: inherit; color: var(--ink); background: var(--bg); border: 1px solid var(--line); border-radius: 10px; padding: 12px 14px; }
  .grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px 0; padding: 10px 12px 6px; max-height: 44vh; overflow: auto; }
  .pick { display: grid; justify-items: center; gap: 6px; padding: 0; border: 0; background: none; color: var(--ink); font: inherit; cursor: pointer; min-width: 0; }
  .ring { padding: 3px; border-radius: 50%; border: 2.5px solid transparent; display: grid; }
  .pick.on .ring { border-color: var(--l-ring); }
  .pick[data-style='faint'] .ring, .pick[data-style='dashed'] .ring { border-width: 2px; }
  .pick[data-style='plain'] .ring { background: var(--line); }
  .nm { max-width: 100%; font-size: 13px; font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .pick.on .nm { font-weight: 600; }
  .tag { font-size: 10px; line-height: 13px; font-weight: 700; padding: 1px 7px; border-radius: 8px; background: var(--l-tag-fill); color: var(--l-tag-ink); border: 1px var(--l-dash) var(--l-tag-border); max-width: 100%; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .tag.empty { visibility: hidden; height: 15px; padding: 0; border: 0; }
  .none { grid-column: 1 / -1; color: var(--ink-3); padding: 8px; }
  .start { border-top: 1px solid var(--line); display: grid; }
  .when { display: flex; align-items: center; gap: 10px; padding: 10px 20px; border: 0; background: none; color: var(--ink); font: inherit; font-size: 14px; cursor: pointer; text-align: left; }
  .when > :global(.icon) { color: var(--ink-2); }
  .when span { flex: 1; }
  .when strong { color: var(--accent); font-weight: 600; }
  .opts { display: grid; gap: 10px; padding: 0 20px 10px; }
  .opts label { display: grid; gap: 4px; font-size: 13px; color: var(--ink-2); }
  .opts input, .opts select { font: inherit; color: var(--ink); background: var(--bg); border: 1px solid var(--line); border-radius: 8px; padding: 8px 10px; }
  .out { justify-self: start; margin: 0 20px 6px; padding: 8px 0; border: 0; background: none; color: var(--danger); font: inherit; font-size: 13px; cursor: pointer; }
  .confirm { display: grid; justify-items: center; gap: 1px; margin: 4px 16px 20px; padding: 12px 16px; border: 0; border-radius: 14px; background: var(--accent); color: var(--on-accent); font: inherit; cursor: pointer; }
  .confirm strong { font-size: 15px; font-weight: 600; }
  .confirm span { font-size: 12px; max-width: 100%; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .confirm:disabled { background: var(--surface-3, var(--surface-2)); color: var(--ink-3); cursor: default; }
</style>
