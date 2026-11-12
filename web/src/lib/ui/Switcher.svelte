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
  import Sheet from './Sheet.svelte';
  import EmptyState from './kit/EmptyState.svelte';
  import FormCard from './kit/FormCard.svelte';
  import Segmented from './kit/Segmented.svelte';
  import ValueRow from './kit/ValueRow.svelte';

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

<Sheet title="Switch · {view.name}" {onclose}>
  {#snippet actions()}
    <button class="icon-btn" aria-label={searching ? 'Close search' : 'Search'} onclick={() => { searching = !searching; query = ''; }}><Icon name="search" size={22} /></button>
  {/snippet}
  <Segmented label="How to switch" value={add ? 'add' : 'replace'} options={[{ id: 'replace', label: 'Replace front' }, { id: 'add', label: 'Add to front' }]} onchange={(v) => mode(v === 'add')} />
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
  <FormCard>
    <ValueRow icon="clock" label="Starting" value={at && Number.isFinite(at) ? new Date(at).toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' }) : 'Now'} onclick={() => (options = !options)} />
    {#if options}
      <div class="opts">
        <label>Happened at<input type="datetime-local" bind:value={atText} max={new Date(Date.now() - new Date().getTimezoneOffset() * 60_000).toISOString().slice(0, 16)} /></label>
        {#if !add}
          <label>{handover ? "Handover note for whoever's next" : 'Note (optional)'}<input bind:value={note} /></label>
          <div class="alerts"><span>Alerts</span><Segmented label="Alerts" bind:value={notify} options={[{ id: 'default', label: 'Usual' }, { id: 'silent', label: 'Silent' }, { id: 'now', label: 'Now' }, { id: 'extra_delay', label: 'Delay' }]} /></div>
        {/if}
      </div>
    {/if}
  </FormCard>
  {#if !add && !placed.length && current.length}<button class="fr-btn out" onclick={() => commit([])}>Switch everyone out of {view.name}</button>{/if}
  {#if summary}<p class="sum">{summary}</p>{/if}
  {#snippet footer()}<button class="fr-wide" disabled={!canSwitch} onclick={() => commit(withPrimary(placed))}>Switch</button>{/snippet}
</Sheet>

<style>
  .icon-btn { width: 44px; height: 44px; display: grid; place-items: center; border: 0; background: none; color: var(--ink-2); border-radius: 50%; cursor: pointer; }
  .search { font: inherit; color: var(--ink); background: var(--bg); border: 1px solid var(--line); border-radius: var(--r-sm); padding: var(--s-3) 14px; }
  .grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px 0; padding: var(--s-1) 0; max-height: 44vh; overflow: auto; }
  .pick { display: grid; justify-items: center; gap: 6px; padding: 0; border: 0; background: none; color: var(--ink); font: inherit; cursor: pointer; min-width: 0; }
  .ring { padding: 3px; border-radius: 50%; border: 2.5px solid transparent; display: grid; }
  .pick.on .ring { border-color: var(--l-ring); }
  .pick[data-style='faint'] .ring, .pick[data-style='dashed'] .ring { border-width: 2px; }
  .pick[data-style='plain'] .ring { background: var(--line); }
  .nm { max-width: 100%; font-size: var(--fs-sm); font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .pick.on .nm { font-weight: 600; }
  .tag { font-size: 10px; line-height: 13px; font-weight: 700; padding: 1px 7px; border-radius: 8px; background: var(--l-tag-fill); color: var(--l-tag-ink); border: 1px var(--l-dash) var(--l-tag-border); max-width: 100%; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .tag.empty { visibility: hidden; height: 15px; padding: 0; border: 0; }
  .none { grid-column: 1 / -1; color: var(--ink-3); padding: var(--s-2); }
  .opts { display: grid; gap: 10px; padding: 10px 14px 14px; }
  .opts label, .alerts { display: grid; gap: var(--s-1); font-size: var(--fs-sm); color: var(--ink-2); }
  .opts input { font: inherit; color: var(--ink); background: var(--bg); border: 1px solid var(--line); border-radius: 8px; padding: var(--s-2) 10px; }
  .out { justify-self: start; color: var(--danger); }
  .sum { margin: 0; color: var(--ink-3); font-size: var(--fs-xs); text-align: center; }
</style>
