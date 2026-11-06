<script lang="ts">
  // Layers & display (Figma F3.9; Android LayersDisplayScreen) for one front: a preset, the layers
  // inner to outer (order, look, whether Fronting shows them), clutter switches and suggested
  // speakers. Layer names and order are the system's (`front.layer`); the rest is the display pref.
  import { defineFront, editLayer } from '../front.svelte';
  import { frontLayers, frontList } from '../front-view';
  import * as fd from '../fronting-display';
  import { sync, type Projection } from '../sync/client';
  import Icon from './Icon.svelte';

  let { projection, front }: { projection: Projection; front: string } = $props();
  const display = $derived(fd.displayPrefs(projection.rows.pref, sync.accountId));
  const view = $derived(fd.front(display, front));
  const system = $derived(projection.front_systems?.[sync.accountId]?.system);
  const named = $derived(system?.fronts[front]);
  const summary = $derived(frontList(projection, sync.accountId).find((f) => f.id === front));
  const layers = $derived(frontLayers(projection, sync.accountId));
  const scene = $derived(fd.scene(layers, summary?.entries ?? [], display, front, []));
  const ORIGINAL = new Set(['front', 'cocon', 'present']);
  const SWATCHES = [null, '#42A397', '#E8776D', '#E0B560', '#B99CE8', '#6FA8DC', '#86BFA5'];

  let editing = $state<string | 'new' | null>(null);
  let dragging = $state<string | null>(null);
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
    <a class="back" href="#/" aria-label="Back to Fronting"><Icon name="back" size={22} /></a>
    <div class="titles"><strong>Layers &amp; display</strong><span>{summary?.name ?? 'Body'} · each front can be set up differently</span></div>
  </div>

  <h2>Preset</h2>
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

  <h2>Layers · inner to outer</h2>
  <div class="panel">
    {#each scene.all as l, i (l.id)}
      <div class="layer-row" class:dragging={dragging === l.id} role="listitem" ondragover={(e) => e.preventDefault()} ondrop={() => drop(l.id)}>
        <span class="grip" draggable="true" role="button" tabindex="-1" aria-label="Drag to reorder {l.name}" ondragstart={() => (dragging = l.id)} ondragend={() => (dragging = null)}><Icon name="grip" size={18} /></span>
        <span class="swatch" style={fd.inkVars(l.style, l.color)} data-style={l.style}></span>
        <button class="text" onclick={() => open(l.id)} aria-label="Edit {l.name}"><strong>{l.name}</strong><span>{l.about ?? (l.separate ? 'Listed separately' : '')}</span></button>
        <span class="keys">
          <button class="mini" disabled={i === 0} aria-label="Move {l.name} inward" onclick={() => move(i, -1)}>↑</button>
          <button class="mini" disabled={i === scene.all.length - 1} aria-label="Move {l.name} outward" onclick={() => move(i, 1)}>↓</button>
        </span>
        <button class="icon-btn" aria-pressed={!l.hidden} aria-label={l.hidden ? `Show ${l.name} on Fronting` : `Hide ${l.name} on Fronting`} onclick={() => toggleHidden(l.id)}><Icon name={l.hidden ? 'eye-off' : 'eye'} size={20} /></button>
      </div>
    {/each}
    <div class="layer-row">
      <span class="grip"></span><span class="swatch" data-style="plain" style={fd.inkVars('plain', null)}></span>
      <span class="text static"><strong>Away</strong><span>Not fronting · {!scene.awayShown ? 'hidden on Fronting' : view.collapseAway ? 'collapsed on Fronting' : 'listed on Fronting'}</span></span>
      <button class="icon-btn" aria-pressed={scene.awayShown} aria-label={scene.awayShown ? 'Hide Away on Fronting' : 'Show Away on Fronting'} onclick={() => toggleHidden(fd.AWAY)}><Icon name={scene.awayShown ? 'eye' : 'eye-off'} size={20} /></button>
    </div>
    <button class="add-layer" onclick={() => open('new')}><Icon name="plus" size={20} />Add a layer, e.g. Passive influence</button>
  </div>
  <p class="hint">Names and order are shared by every front; renaming or reordering never rewrites history.</p>

  {#if editing}
    {@const l = editing === 'new' ? null : scene.all.find((x) => x.id === editing)}
    <form class="panel editor" onsubmit={(e) => { e.preventDefault(); saveLayer(); }}>
      <strong>{l ? l.name : 'New layer'}</strong>
      <label>Name <input bind:value={draft.name} maxlength="40" /></label>
      <label>Description <input bind:value={draft.about} maxlength="80" placeholder="e.g. In control · counts as fronting" /></label>
      <div class="choices" role="radiogroup" aria-label="Look">
        <button type="button" role="radio" aria-checked={draft.style === null} class:on={draft.style === null} onclick={() => (draft.style = null)}>Automatic</button>
        {#each fd.STYLES as s (s)}<button type="button" role="radio" aria-checked={draft.style === s} class:on={draft.style === s} onclick={() => (draft.style = s)}>{s[0].toUpperCase() + s.slice(1)}</button>{/each}
      </div>
      <div class="swatches" role="radiogroup" aria-label="Colour">
        {#each SWATCHES as hex (hex ?? 'accent')}<button type="button" role="radio" aria-checked={draft.color === hex} aria-label={hex ?? 'Accent'} class:on={draft.color === hex} style="background: {hex ?? 'var(--surface-2)'}" onclick={() => (draft.color = hex)}>{hex ? '' : 'A'}</button>{/each}
      </div>
      <label class="inline"><input type="checkbox" bind:checked={draft.separate} /> List separately (its own row, not nested)</label>
      <div class="row-buttons">
        <button class="primary" disabled={!draft.name.trim()}>Save</button>
        <button type="button" class="plain" onclick={() => (editing = null)}>Cancel</button>
        {#if l && !ORIGINAL.has(l.id)}<button type="button" class="plain danger" onclick={() => { editLayer(l.id, { deleted: true }); editing = null; }}>Remove layer</button>{/if}
      </div>
    </form>
  {/if}

  <h2>Reduce clutter</h2>
  <div class="panel">
    {#each [['hideEmpty', 'Hide empty layers'], ['collapseAway', 'Collapse away members'], ['showTime', 'Show time in front'], ['showOthers', 'Show other fronts on Fronting']] as [key, label] (key)}
      {@const k = key as 'hideEmpty' | 'collapseAway' | 'showTime' | 'showOthers'}
      <label class="toggle"><span>{label}</span><input type="checkbox" role="switch" checked={view[k]} onchange={(e) => setView((v) => ({ ...v, [k]: e.currentTarget.checked }))} /></label>
    {/each}
    {#if named}
      <button class="toggle link" aria-expanded={speakers} onclick={() => (speakers = !speakers)}><span>Suggested speakers in chat</span><em>{speakerSummary}</em><Icon name="chevron-right" size={18} /></button>
      {#if speakers}
        <div class="speakers">
          <label class="inline"><input type="checkbox" checked={named.authorship_layers === null} onchange={(e) => defineFront(front, { authorship_layers: e.currentTarget.checked ? null : layers.slice(0, 2).map((l) => l.id) })} /> Default (fronting and co-conscious)</label>
          {#if named.authorship_layers !== null}{#each layers as l (l.id)}<label class="inline"><input type="checkbox" checked={named.authorship_layers.includes(l.id)} onchange={(e) => toggleSpeaker(l.id, e.currentTarget.checked)} /> {l.name}</label>{/each}{/if}
        </div>
      {/if}
    {/if}
  </div>

  <h2>Front states</h2>
  <div class="panel pad">
    <p class="hint">States like Blurry or Dissociated can be switched in like a member.</p>
    <div class="state-chips">
      {#each states as [id, row] (id)}<span class="state">{String(row.fields.name ?? 'State')}<button aria-label="Remove {String(row.fields.name ?? 'state')}" onclick={() => sync.create('state.delete', sync.accountScope, id, {})}><Icon name="close" size={14} /></button></span>{/each}
    </div>
    <form class="add" onsubmit={(e) => { e.preventDefault(); const n = newState.trim(); if (n) { sync.create('state.create', sync.accountScope, sync.newId(), { name: n, icon: '?' }); newState = ''; } }}>
      <input bind:value={newState} maxlength="40" placeholder="New state, e.g. Blurry" aria-label="New state" /><button class="primary" disabled={!newState.trim()}>Add</button>
    </form>
  </div>
</div>

<style>
  .page { display: grid; gap: 6px; max-width: 720px; padding-bottom: 32px; }
  .bar { display: flex; align-items: center; gap: 4px; margin-left: -8px; }
  .back { width: 44px; height: 44px; display: grid; place-items: center; color: var(--ink); border-radius: 50%; }
  .titles { display: grid; padding-left: 4px; }
  .titles strong { font-size: 17px; font-weight: 600; }
  .titles span { color: var(--ink-3); font-size: 12px; }
  h2 { margin: 16px 4px 6px; color: var(--accent); font-size: 13px; font-weight: 600; }
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
  .keys { display: flex; opacity: 0; }
  .layer-row:hover .keys, .layer-row:focus-within .keys { opacity: 1; }
  .mini { width: 28px; height: 36px; border: 0; background: none; color: var(--accent); font: inherit; cursor: pointer; }
  .mini:disabled { color: var(--ink-3); }
  .icon-btn { width: 44px; height: 44px; flex: none; display: grid; place-items: center; border: 0; background: none; color: var(--ink-2); border-radius: 50%; cursor: pointer; }
  .add-layer { display: flex; align-items: center; gap: 12px; padding: 12px 12px 12px 14px; border: 0; background: none; color: var(--accent); font: inherit; font-size: 14px; font-weight: 500; cursor: pointer; text-align: left; }
  .hint { margin: 4px; color: var(--ink-3); font-size: 12px; }
  .editor { padding: 14px; gap: 10px; margin-top: 8px; }
  .editor label { display: grid; gap: 4px; font-size: 13px; color: var(--ink-2); }
  .editor input:not([type='checkbox']), .add input { font: inherit; color: var(--ink); background: var(--bg); border: 1px solid var(--line); border-radius: 8px; padding: 8px 10px; }
  .inline { display: flex !important; align-items: center; gap: 6px; }
  .choices { display: flex; flex-wrap: wrap; gap: 6px; }
  .choices button { border: 1px solid var(--line); border-radius: 999px; background: none; color: var(--ink-2); font: inherit; font-size: 13px; padding: 6px 12px; cursor: pointer; }
  .choices button.on { background: var(--accent-soft); border-color: var(--accent); color: var(--accent); }
  .swatches { display: flex; gap: 8px; }
  .swatches button { width: 32px; height: 32px; border-radius: 50%; border: 1px solid var(--line); color: var(--ink-3); font-size: 12px; cursor: pointer; }
  .swatches button.on { border: 2px solid var(--ink); }
  .row-buttons { display: flex; gap: 8px; align-items: center; }
  .primary { border: 0; border-radius: 10px; background: var(--accent); color: var(--on-accent); font: inherit; font-weight: 600; padding: 10px 16px; cursor: pointer; }
  .primary:disabled { opacity: 0.5; }
  .plain { border: 0; background: none; color: var(--ink-2); font: inherit; cursor: pointer; }
  .danger { color: var(--danger); }
  .toggle { display: flex; align-items: center; gap: 16px; min-height: 48px; padding: 10px 16px; font-size: 15px; color: var(--ink); border: 0; background: none; font: inherit; text-align: left; cursor: pointer; }
  .toggle > span { flex: 1; }
  .toggle input { width: 40px; height: 24px; accent-color: var(--accent); }
  .toggle em { font-style: normal; color: var(--accent); font-size: 14px; }
  .toggle :global(.icon) { color: var(--ink-3); }
  .speakers { display: grid; gap: 6px; padding: 0 16px 12px; }
  .state-chips { display: flex; flex-wrap: wrap; gap: 6px; }
  .state { display: inline-flex; align-items: center; gap: 2px; padding: 4px 4px 4px 12px; border-radius: 999px; background: var(--surface-2); font-size: 13px; }
  .state button { width: 28px; height: 28px; display: grid; place-items: center; border: 0; background: none; color: var(--ink-3); border-radius: 50%; cursor: pointer; }
  .add { display: flex; gap: 8px; }
  .add input { flex: 1; min-width: 0; }
</style>
