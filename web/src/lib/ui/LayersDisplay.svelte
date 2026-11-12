<script lang="ts">
  // Layers & display (Figma F3.9; Android LayersDisplayScreen) for one front: a preset, the layers
  // inner to outer (order, look, whether Fronting shows them), clutter switches and suggested
  // speakers. Layer names and order are the system's (`front.layer`); the rest is the display pref.
  import { defineFront, editLayer } from '../front.svelte';
  import { frontLayers, frontList } from '../front-view';
  import * as fd from '../fronting-display';
  import { sync, type Projection } from '../sync/client';
  import Icon from './Icon.svelte';
  import Sheet from './Sheet.svelte';
  import FormCard from './kit/FormCard.svelte';
  import SectionLabel from './kit/SectionLabel.svelte';
  import Swatches from './kit/Swatches.svelte';
  import Switch from './kit/Switch.svelte';
  import ValueRow from './kit/ValueRow.svelte';

  let { projection, front }: { projection: Projection; front: string } = $props();
  const display = $derived(fd.displayPrefs(projection.rows.pref, sync.accountId));
  const view = $derived(fd.front(display, front));
  const system = $derived(projection.front_systems?.[sync.accountId]?.system);
  const named = $derived(system?.fronts[front]);
  const summary = $derived(frontList(projection, sync.accountId).find((f) => f.id === front));
  const layers = $derived(frontLayers(projection, sync.accountId));
  const scene = $derived(fd.scene(layers, summary?.entries ?? [], display, front, []));
  const ORIGINAL = new Set(['front', 'cocon', 'present']);
  const SWATCHES = ['#42A397', '#E8776D', '#E0B560', '#B99CE8', '#6FA8DC', '#86BFA5'];

  let editing = $state<string | 'new' | null>(null);
  let dragging = $state<string | null>(null);
  let arranging = $state(false);
  let speakers = $state(false);
  let newState = $state('');
  let draft = $state({ name: '', about: '', style: null as fd.Style | null, color: null as string | null, separate: false });

  const setView = (f: (v: fd.FrontView) => fd.FrontView) => fd.save(fd.withFront(display, front, f));
  function choosePreset(p: fd.Preset) { setView((v) => ({ ...v, preset: p, hideEmpty: p !== 'complete' })); }
  function toggleHidden(id: string) { setView((v) => ({ ...v, hiddenLayers: v.hiddenLayers.includes(id) ? v.hiddenLayers.filter((x) => x !== id) : [...v.hiddenLayers, id] })); }
  // a new display order is written as fresh levels, inner first (never rewriting history)
  function reorder(ids: string[]) { ids.forEach((id, i) => { if (layers.find((l) => l.id === id)?.level !== i) editLayer(id, { level: i }); }); }
  function drop(on: string) {
    if (!dragging || dragging === on) return;
    const ids = scene.all.map((l) => l.id).filter((id) => id !== dragging);
    ids.splice(ids.indexOf(on), 0, dragging);
    reorder(ids);
    dragging = null;
  }
  function move(i: number, d: -1 | 1) {
    const ids = scene.all.map((l) => l.id);
    const [x] = ids.splice(i, 1);
    ids.splice(i + d, 0, x);
    reorder(ids);
  }
  function open(id: string | 'new') {
    const l = id === 'new' ? null : scene.all.find((x) => x.id === id);
    const look = l ? fd.layer(display, l.id) : null;
    draft = { name: l?.name ?? '', about: look?.about ?? l?.about ?? '', style: look?.style ?? null, color: look?.color ?? null, separate: look?.separate ?? false };
    editing = id;
  }
  function saveLayer() {
    const name = draft.name.trim();
    if (!name || !editing) return;
    const existing = editing === 'new' ? null : scene.all.find((l) => l.id === editing);
    const id = existing?.id ?? sync.newId();
    if (!existing) editLayer(id, { name, level: layers.reduce((m, l) => Math.max(m, l.level), -1) + 1 });
    else if (name !== existing.name) editLayer(id, { name });
    const about = draft.about.trim() || null;
    fd.save(fd.withLayer(display, id, () => ({ style: draft.style, about: about && about !== fd.defaultAbout(id) ? about : null, separate: draft.separate, color: draft.color })));
    editing = null;
  }
  const speakerSummary = $derived.by(() => {
    const sel = named?.authorship_layers;
    if (sel === null || sel === undefined) return layers.filter((l) => l.id === 'front' || l.id === 'cocon').map((l) => l.name).join(' + ') || 'Default';
    return sel.length ? layers.filter((l) => sel.includes(l.id)).map((l) => l.name).join(' + ') : 'Nobody';
  });
  function toggleSpeaker(id: string, on: boolean) {
    const now = named?.authorship_layers ?? [];
    defineFront(front, { authorship_layers: on ? [...new Set([...now, id])] : now.filter((x) => x !== id) });
  }
  const states = $derived(Object.entries(projection.rows.custom_state ?? {}).filter(([, r]) => r.exists && !r.fields.deleted_at));
</script>

<div class="page">
  <div class="bar">
    <a class="back" href="#/fronting" aria-label="Back to Fronting"><Icon name="back" size={22} /></a>
    <div class="titles"><strong>Layers &amp; display</strong><span>{summary?.name ?? 'Body'}</span></div>
    <button class="icon-btn" aria-pressed={arranging} aria-label={arranging ? 'Done arranging' : 'Arrange layers'} onclick={() => (arranging = !arranging)}><Icon name={arranging ? 'check' : 'pencil'} size={22} /></button>
  </div>

  <SectionLabel label="Preset" />
  <div class="presets" role="radiogroup" aria-label="Preset">
    {#each fd.PRESETS as p (p.id)}
      {@const steps = p.id === 'simple' ? 0 : p.id === 'detailed' ? 2 : 4}
      <button role="radio" aria-checked={view.preset === p.id} class:on={view.preset === p.id} onclick={() => choosePreset(p.id)}>
        <span class="glyph" aria-hidden="true">
          {#each Array(steps) as _, i (i)}<span class="ring" style="width: {56 - i * (steps === 2 ? 14 : 9)}px; height: {40 - i * (steps === 2 ? 11 : 7)}px; border-radius: {8 - i}px"></span>{/each}
          <span class="core" style="width: {steps ? 56 - steps * (steps === 2 ? 14 : 9) : 56}px; height: {steps ? 40 - steps * (steps === 2 ? 11 : 7) : 40}px"></span>
        </span>
        <strong>{p.label}</strong><span>{p.about}</span>
      </button>
    {/each}
  </div>

  <SectionLabel label="Layers · inner to outer" />
  <div class="panel">
    {#each scene.all as l, i (l.id)}
      <div class="layer-row" class:dragging={dragging === l.id} role="listitem" ondragover={(e) => e.preventDefault()} ondrop={() => drop(l.id)}>
        <span class="grip" draggable="true" role="button" tabindex="-1" aria-label="Drag to reorder {l.name}" ondragstart={() => (dragging = l.id)} ondragend={() => (dragging = null)}><Icon name="grip" size={18} /></span>
        <span class="swatch" style={fd.inkVars(l.style, l.color)} data-style={l.style}></span>
        <button class="text" onclick={() => open(l.id)} aria-label="Edit {l.name}"><strong>{l.name}</strong><span>{l.about ?? (l.separate ? 'Listed separately' : '')}</span></button>
        {#if arranging}<span class="keys">
          <button class="mini" disabled={i === 0} aria-label="Move {l.name} inward" onclick={() => move(i, -1)}>↑</button>
          <button class="mini" disabled={i === scene.all.length - 1} aria-label="Move {l.name} outward" onclick={() => move(i, 1)}>↓</button>
        </span>{:else}
        <button class="icon-btn" aria-pressed={!l.hidden} aria-label={l.hidden ? `Show ${l.name} on Fronting` : `Hide ${l.name} on Fronting`} onclick={() => toggleHidden(l.id)}><Icon name={l.hidden ? 'eye-off' : 'eye'} size={20} /></button>{/if}
      </div>
    {/each}
    <div class="layer-row">
      <span class="grip"></span><span class="swatch" data-style="plain" style={fd.inkVars('plain', null)}></span>
      <span class="text static"><strong>Away</strong><span>Not fronting · {!scene.awayShown ? 'hidden on Fronting' : view.collapseAway ? 'collapsed on Fronting' : 'listed on Fronting'}</span></span>
      <button class="icon-btn" aria-pressed={scene.awayShown} aria-label={scene.awayShown ? 'Hide Away on Fronting' : 'Show Away on Fronting'} onclick={() => toggleHidden(fd.AWAY)}><Icon name={scene.awayShown ? 'eye' : 'eye-off'} size={20} /></button>
    </div>
    <button class="add-layer" onclick={() => open('new')}><Icon name="plus" size={20} />Add a layer, e.g. Passive influence</button>
  </div>

  {#if editing}
    {@const l = editing === 'new' ? null : scene.all.find((x) => x.id === editing)}
    <Sheet title={l ? l.name : 'New layer'} onclose={() => (editing = null)}>
      <label class="field">Name <input bind:value={draft.name} maxlength="40" /></label>
      <label class="field">Description <input bind:value={draft.about} maxlength="80" placeholder="e.g. In control · counts as fronting" /></label>
      <SectionLabel label="Look" />
      <div class="fr-chips pad" role="radiogroup" aria-label="Look">
        <button type="button" class="fr-chip" role="radio" aria-checked={draft.style === null} class:on={draft.style === null} onclick={() => (draft.style = null)}>Automatic</button>
        {#each fd.STYLES as st (st)}<button type="button" class="fr-chip" role="radio" aria-checked={draft.style === st} class:on={draft.style === st} onclick={() => (draft.style = st)}>{st[0].toUpperCase() + st.slice(1)}</button>{/each}
      </div>
      <SectionLabel label="Colour" />
      <div class="colour">
        <button type="button" class="fr-chip" aria-pressed={draft.color === null} onclick={() => (draft.color = null)}>Automatic</button>
        <Swatches value={draft.color} colors={SWATCHES} custom onpick={(c) => (draft.color = c)} />
      </div>
      <FormCard><Switch label="List separately" bind:checked={draft.separate} /></FormCard>
      {#snippet footer()}
        {#if l && !ORIGINAL.has(l.id)}<button type="button" class="fr-wide neutral remove" onclick={() => { editLayer(l.id, { deleted: true }); editing = null; }}>Remove layer</button>{/if}
        <button type="button" class="fr-wide" disabled={!draft.name.trim()} onclick={saveLayer}>Save</button>
      {/snippet}
    </Sheet>
  {/if}

  <SectionLabel label="Reduce clutter" />
  <FormCard>
    {#each [['hideEmpty', 'Hide empty layers'], ['collapseAway', 'Collapse away members'], ['showTime', 'Show time in front'], ['showOthers', 'Show other fronts on Fronting']] as [key, label] (key)}
      {@const k = key as 'hideEmpty' | 'collapseAway' | 'showTime' | 'showOthers'}
      <Switch {label} checked={view[k]} onchange={(on) => setView((v) => ({ ...v, [k]: on }))} />
    {/each}
    {#if named}<ValueRow label="Suggested speakers in chat" value={speakerSummary} onclick={() => (speakers = true)} />{/if}
  </FormCard>
  {#if speakers && named}
    <Sheet title="Suggested speakers in chat" onclose={() => (speakers = false)}>
      <FormCard>
        <Switch label="Default (fronting and co-conscious)" checked={named.authorship_layers === null} onchange={(on) => defineFront(front, { authorship_layers: on ? null : layers.slice(0, 2).map((l) => l.id) })} />
        {#if named.authorship_layers !== null}{#each layers as l (l.id)}<Switch label={l.name} checked={named.authorship_layers.includes(l.id)} onchange={(on) => toggleSpeaker(l.id, on)} />{/each}{/if}
      </FormCard>
      {#snippet footer()}<button class="fr-wide" onclick={() => (speakers = false)}>Done</button>{/snippet}
    </Sheet>
  {/if}

  <SectionLabel label="Front states" />
  <div class="panel pad">
    <div class="state-chips">
      {#each states as [id, row] (id)}<span class="state">{String(row.fields.name ?? 'State')}<button aria-label="Remove {String(row.fields.name ?? 'state')}" onclick={() => sync.create('state.delete', sync.accountScope, id, {})}><Icon name="close" size={14} /></button></span>{/each}
    </div>
    <form class="add" onsubmit={(e) => { e.preventDefault(); const n = newState.trim(); if (n) { sync.create('state.create', sync.accountScope, sync.newId(), { name: n, icon: '?' }); newState = ''; } }}>
      <input bind:value={newState} maxlength="40" placeholder="New state, e.g. Blurry" aria-label="New state" /><button class="fr-btn primary" disabled={!newState.trim()}>Add</button>
    </form>
  </div>
</div>

<style>
  .page { display: grid; gap: 6px; max-width: 720px; padding-bottom: 32px; }
  .bar { display: flex; align-items: center; gap: 4px; margin-left: -8px; }
  .back { width: 44px; height: 44px; display: grid; place-items: center; color: var(--ink); border-radius: 50%; }
  .titles { flex: 1; display: grid; padding-left: 4px; }
  /* the desktop app bar already names the page */
  @media (min-width: 1024px) { .titles { visibility: hidden; } }
  .titles strong { font-size: 17px; font-weight: 600; }
  .titles span { color: var(--ink-3); font-size: 12px; }
  .presets { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; }
  .presets button { display: grid; justify-items: center; align-content: start; gap: 6px; padding: 12px 8px; border: 1.5px solid transparent; border-radius: 14px; background: var(--surface); color: var(--ink); font: inherit; cursor: pointer; text-align: center; }
  .presets button.on { background: var(--accent-soft); border-color: var(--accent); }
  .presets button.on strong { color: var(--accent); }
  .presets strong { font-size: 14px; font-weight: 600; }
  .presets button > span:last-child { color: var(--ink-3); font-size: 12px; line-height: 16px; }
  .glyph { position: relative; width: 56px; height: 40px; display: grid; place-items: center; }
  .glyph > span { grid-area: 1 / 1; }
  .ring { border: 1.2px dashed color-mix(in oklab, var(--ink-3) 45%, transparent); }
  .core { border: 1.2px solid var(--accent); background: color-mix(in oklab, var(--accent) 25%, transparent); border-radius: 6px; }
  .panel { background: var(--surface); border-radius: 16px; display: grid; overflow: hidden; }
  .panel.pad { padding: 12px; gap: 8px; }
  .layer-row { display: flex; align-items: center; gap: 8px; padding: 6px 4px; border-bottom: 1px solid var(--line); }
  .layer-row.dragging { opacity: 0.5; }
  .grip { width: 36px; height: 36px; flex: none; display: grid; place-items: center; color: var(--ink-3); cursor: grab; }
  .swatch { width: 28px; height: 20px; flex: none; border-radius: 6px; border: 1.5px var(--l-dash) var(--l-border); background: var(--l-fill); }
  .text { flex: 1; min-width: 0; display: grid; text-align: left; border: 0; background: none; color: var(--ink); font: inherit; padding: 0 0 0 4px; cursor: pointer; }
  .text.static { cursor: default; }
  .text strong { font-size: 15px; font-weight: 500; }
  .text span { color: var(--ink-3); font-size: 12px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .keys { display: flex; }
  .mini { width: 40px !important; height: 44px !important; font-size: 18px !important; }
  .mini { width: 28px; height: 36px; border: 0; background: none; color: var(--accent); font: inherit; cursor: pointer; }
  .mini:disabled { color: var(--ink-3); }
  .icon-btn { width: 44px; height: 44px; flex: none; display: grid; place-items: center; border: 0; background: none; color: var(--ink-2); border-radius: 50%; cursor: pointer; }
  .add-layer { display: flex; align-items: center; gap: 12px; padding: 12px 12px 12px 14px; border: 0; background: none; color: var(--accent); font: inherit; font-size: 14px; font-weight: 500; cursor: pointer; text-align: left; }
  .field { display: grid; gap: 4px; font-size: 13px; color: var(--ink-2); }
  .field input, .add input { font: inherit; color: var(--ink); background: var(--bg); border: 1px solid var(--line); border-radius: 8px; padding: 8px 10px; }
  .pad { padding: 0; }
  .colour { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }
  .remove { flex: 0 0 auto; color: var(--danger); }
  .state-chips { display: flex; flex-wrap: wrap; gap: 6px; }
  .state { display: inline-flex; align-items: center; gap: 2px; padding: 4px 4px 4px 12px; border-radius: 999px; background: var(--surface-2); font-size: 13px; }
  .state button { width: 28px; height: 28px; display: grid; place-items: center; border: 0; background: none; color: var(--ink-3); border-radius: 50%; cursor: pointer; }
  .add { display: flex; gap: 8px; }
  .add input { flex: 1; min-width: 0; }
</style>
