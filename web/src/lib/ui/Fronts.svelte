<script lang="ts">
  // Fronts and layers (D-080, D-085; Figma F3.4 / F3.9). Every rule — following, overrides,
  // purpose pools — is resolved in core; this page only writes sparse definitions.
  import { defineFront, editLayer, resumeFollowing } from '../front.svelte';
  import { frontLayers, frontList, type FrontSummary } from '../front-view';
  import { members } from '../data';
  import { sync, type Projection } from '../sync/client';
  import Icon from './Icon.svelte';

  let { projection }: { projection: Projection } = $props();

  const account = $derived(sync.accountId);
  const system = $derived(projection.front_systems?.[account]?.system);
  const fronts = $derived(frontList(projection, account, true));
  const live = $derived(fronts.filter((f) => !f.deleted));
  const removed = $derived(fronts.filter((f) => f.deleted));
  const layers = $derived(frontLayers(projection, account));
  const names = $derived(new Map(members(projection).map((m) => [m.id, m.display_name ?? m.name])));

  let editing = $state<string | null>(null);
  let newName = $state('');
  let newLayer = $state('');
  const TEMPLATES = ['Online', 'Work', 'Inner world', 'Subsystem'];

  // Purposes each front selects layers for. null = the existing default (D-080/D-085).
  const PURPOSES = [
    { key: 'authorship', label: 'Suggested speakers in chat', hint: 'Who the composer offers first.' },
    { key: 'notifications', label: 'Switch notifications', hint: 'Who followers can hear about (each follower still needs access).' },
    { key: 'mentions', label: '@front mentions', hint: 'Who a mention of the front reaches.' },
    { key: 'reading', label: 'Read tracking', hint: 'Who marks a chat read, with per-member reading on.' },
    { key: 'chat_alerts', label: '“Only while fronting” alerts', hint: 'Who counts as fronting for chat alerts.' },
  ] as const;
  type Purpose = (typeof PURPOSES)[number]['key'];

  function selection(front: string, purpose: Purpose): string[] | null {
    const f = system?.fronts[front];
    if (!f) return null;
    if (purpose === 'authorship') return f.authorship_layers;
    if (purpose === 'notifications') return f.notification_layers;
    return f.purpose_layers?.[purpose] ?? null;
  }
  function setSelection(front: string, purpose: Purpose, value: string[] | null) {
    if (purpose === 'authorship') defineFront(front, { authorship_layers: value });
    else if (purpose === 'notifications') defineFront(front, { notification_layers: value });
    else defineFront(front, { purpose_layers: { [purpose]: value } });
  }
  function toggleLayer(front: string, purpose: Purpose, layer: string, on: boolean) {
    const now = selection(front, purpose) ?? [];
    setSelection(front, purpose, on ? [...new Set([...now, layer])] : now.filter((l) => l !== layer));
  }

  function counts(f: FrontSummary) {
    return layers.map((l) => ({ ...l, n: f.entries.filter((e) => e.layer_id === l.id).length })).filter((l) => l.n);
  }
  function who(f: FrontSummary) {
    const inner = layers[0]?.id;
    const here = f.entries.filter((e) => e.layer_id === inner && e.subject_type === 'member').map((e) => names.get(e.subject_id) ?? 'Someone');
    return here.length ? here.join(' & ') : 'No one in front';
  }
  function badge(f: FrontSummary) {
    if (f.deleted) return 'Removed';
    if (f.state === 'following') return `Follows ${f.sourceName ?? 'another front'}`;
    if (f.state === 'overridden') return 'Changed by hand';
    if (f.state === 'unavailable') return f.issue === 'cycle' ? 'Following loops' : 'Source unavailable';
    return f.body ? 'Main' : null;
  }

  function create(name: string) {
    const n = name.trim();
    if (!n) return;
    const id = sync.newId();
    defineFront(id, { name: n, follow_source: null });
    newName = '';
    editing = id;
  }
  function rename(f: FrontSummary, value: string) {
    const n = value.trim();
    if (n && n !== f.name) defineFront(f.id, { name: n });
  }
  function addLayer() {
    const n = newLayer.trim();
    if (!n) return;
    const top = layers.reduce((m, l) => Math.max(m, l.level), -1);
    editLayer(sync.newId(), { name: n, level: top + 1 });
    newLayer = '';
  }
  function move(i: number, d: -1 | 1) {
    const a = layers[i], b = layers[i + d];
    if (!a || !b) return;
    // swap display levels; distinct levels keep the order stable everywhere
    const [la, lb] = a.level === b.level ? [b.level + d, a.level] : [b.level, a.level];
    editLayer(a.id, { level: la });
    editLayer(b.id, { level: lb });
  }
</script>

<div class="fronts">
  <header>
    <h1 class="display">Fronts</h1>
    <p>Each front has its own members and layers, so you can track who's in the body, who's posting and who's in your inner world without mixing them up.</p>
  </header>

  <ul class="cards">
    {#each live as f (f.id)}
      {@const b = badge(f)}
      <li class="card" class:open={editing === f.id}>
        <div class="row">
          <div class="title">
            <strong>{f.name}</strong>{#if b}<span class="badge">{b}</span>{/if}
            <span class="who">{who(f)}</span>
            <span class="chips">{#each counts(f) as l (l.id)}<span class="chip" class:inner={l.id === layers[0]?.id}>{l.name} {l.n}</span>{/each}</span>
          </div>
          <button class="ghost" aria-expanded={editing === f.id} onclick={() => (editing = editing === f.id ? null : f.id)}>
            <Icon name="edit" size={18} /><span class="sr">Edit {f.name}</span>
          </button>
        </div>
        {#if f.state === 'overridden'}
          <button class="ghost resume" onclick={() => resumeFollowing(f.id)}>Resume following {f.sourceName ?? ''}</button>
        {/if}
        {#if editing === f.id}
          <div class="editor">
            <label>Name <input value={f.name} onchange={(e) => rename(f, e.currentTarget.value)} aria-label={`Name of ${f.name}`} /></label>
            <label>Follows
              <select value={f.source ?? ''} onchange={(e) => defineFront(f.id, { follow_source: e.currentTarget.value || null })} aria-label={`What ${f.name} follows`}>
                <option value="">Nothing — changed by hand</option>
                {#each live.filter((o) => o.id !== f.id) as o (o.id)}<option value={o.id}>{o.name}</option>{/each}
              </select>
            </label>
            <p class="hint">A following front mirrors its source. Changing it by hand keeps your change until you choose Resume following.</p>
            <details>
              <summary>Who counts for what</summary>
              {#each PURPOSES as p (p.key)}
                {@const sel = selection(f.id, p.key)}
                <fieldset>
                  <legend>{p.label}</legend>
                  <p class="hint">{p.hint}</p>
                  <label class="inline"><input type="checkbox" checked={sel === null} onchange={(e) => setSelection(f.id, p.key, e.currentTarget.checked ? null : [])} /> Use the default</label>
                  {#if sel !== null}
                    <div class="layer-checks">
                      {#each layers as l (l.id)}
                        <label class="inline"><input type="checkbox" checked={sel.includes(l.id)} onchange={(e) => toggleLayer(f.id, p.key, l.id, e.currentTarget.checked)} /> {l.name}</label>
                      {/each}
                    </div>
                    {#if !sel.length}<p class="hint">Nobody is selected.</p>{/if}
                  {/if}
                </fieldset>
              {/each}
            </details>
            {#if !f.body}
              <button class="danger" onclick={() => { defineFront(f.id, { deleted: true }); editing = null; }}>Remove {f.name}</button>
              <p class="hint">Its history stays; you can restore it below.</p>
            {/if}
          </div>
        {/if}
      </li>
    {/each}
    <li class="card new">
      <form onsubmit={(e) => { e.preventDefault(); create(newName); }}>
        <strong><Icon name="plus" size={18} /> New front</strong>
        <div class="add">
          <input bind:value={newName} placeholder="Name, e.g. Headspace" aria-label="New front name" />
          <button class="primary" disabled={!newName.trim()}>Add</button>
        </div>
        <div class="templates">{#each TEMPLATES as t (t)}<button type="button" class="pill" onclick={() => create(t)}>{t}</button>{/each}</div>
      </form>
    </li>
  </ul>

  {#if removed.length}
    <details class="removed">
      <summary>Removed fronts ({removed.length})</summary>
      {#each removed as f (f.id)}
        <div class="row"><span>{f.name}</span><button class="ghost" onclick={() => defineFront(f.id, { deleted: false })}>Restore</button></div>
      {/each}
    </details>
  {/if}

  <section aria-label="Layers">
    <h2>Layers · inner to outer</h2>
    <p class="hint">Layers are shared by every front. Renaming or reordering never rewrites history, and a custom layer never stands in for Fronting, Co-conscious or Present.</p>
    <ol class="layers">
      {#each layers as l, i (l.id)}
        <li>
          <span class="swatch" class:inner={i === 0}></span>
          <input value={l.name} aria-label={`Name of layer ${l.name}`} onchange={(e) => { const n = e.currentTarget.value.trim(); if (n && n !== l.name) editLayer(l.id, { name: n }); }} />
          <button class="icon" onclick={() => move(i, -1)} disabled={i === 0} aria-label={`Move ${l.name} inward`}>↑</button>
          <button class="icon" onclick={() => move(i, 1)} disabled={i === layers.length - 1} aria-label={`Move ${l.name} outward`}>↓</button>
          {#if l.custom}<button class="icon" onclick={() => editLayer(l.id, { deleted: true })} aria-label={`Remove layer ${l.name}`}>✕</button>{:else}<span class="icon"></span>{/if}
        </li>
      {/each}
    </ol>
    <form class="add" onsubmit={(e) => { e.preventDefault(); addLayer(); }}>
      <input bind:value={newLayer} placeholder="Add a layer, e.g. Passive influence" aria-label="New layer name" />
      <button class="primary" disabled={!newLayer.trim()}>Add</button>
    </form>
  </section>
</div>

<style>
  .fronts { display: grid; gap: var(--s-5); max-width: 760px; }
  header p, .hint { color: var(--ink-3); font-size: var(--fs-sm); margin: 0; }
  h1 { margin: 0 0 var(--s-2); font-size: var(--fs-xl); }
  h2 { font-size: var(--fs-sm); color: var(--accent); margin: 0 0 var(--s-2); }
  .cards { list-style: none; margin: 0; padding: 0; display: grid; gap: var(--s-3); }
  .card { background: var(--surface); border-radius: var(--r-lg); padding: var(--s-4); display: grid; gap: var(--s-3); }
  .card.new { background: transparent; border: 1px dashed var(--line); }
  .row { display: flex; gap: var(--s-3); align-items: start; justify-content: space-between; }
  .title { display: grid; gap: 6px; min-width: 0; }
  .title strong { font-size: var(--fs-lg, 1.125rem); }
  .badge { justify-self: start; font-size: var(--fs-xs); padding: 2px 8px; border-radius: var(--r-full); background: var(--surface-2); color: var(--ink-2); }
  .who { color: var(--ink-2); font-size: var(--fs-sm); }
  .chips { display: flex; flex-wrap: wrap; gap: 6px; }
  .chip { font-size: var(--fs-xs); padding: 3px 10px; border-radius: var(--r-full); border: 1px dashed var(--line); color: var(--ink-2); }
  .chip.inner { border-style: solid; border-color: transparent; background: var(--accent-soft); color: var(--ink); }
  .editor { display: grid; gap: var(--s-3); border-top: 1px solid var(--line); padding-top: var(--s-3); }
  .editor label, fieldset { display: grid; gap: var(--s-1); font-size: var(--fs-sm); color: var(--ink-2); }
  fieldset { border: 1px solid var(--line); border-radius: var(--r-md); padding: var(--s-3); margin: var(--s-2) 0 0; }
  legend { color: var(--ink); padding: 0 4px; }
  .inline { display: flex !important; align-items: center; gap: 8px; min-height: 40px; }
  .layer-checks { display: flex; flex-wrap: wrap; gap: 0 var(--s-4); }
  input:not([type='checkbox']), select { font: inherit; color: var(--ink); background: var(--surface-2); border: 1px solid var(--line); border-radius: var(--r-sm); padding: var(--s-2) var(--s-3); min-height: 44px; min-width: 0; }
  details summary { cursor: pointer; color: var(--accent); min-height: 40px; display: flex; align-items: center; }
  .add { display: flex; gap: var(--s-2); }
  .add input { flex: 1; }
  .templates { display: flex; flex-wrap: wrap; gap: var(--s-2); }
  .pill { min-height: 36px; padding: 0 14px; border-radius: var(--r-full); border: 0; background: var(--surface-2); color: var(--ink); cursor: pointer; }
  .new form { display: grid; gap: var(--s-3); }
  .new strong { display: flex; align-items: center; gap: 8px; color: var(--accent); }
  .layers { list-style: none; margin: 0 0 var(--s-3); padding: 0; display: grid; gap: var(--s-2); }
  .layers li { display: flex; align-items: center; gap: var(--s-2); background: var(--surface); border-radius: var(--r-md); padding: var(--s-2) var(--s-3); }
  .layers input { flex: 1; background: transparent; border-color: transparent; }
  .layers input:focus { border-color: var(--line); }
  .swatch { width: 28px; height: 20px; border-radius: 6px; border: 1.5px dashed var(--ink-3); flex: none; }
  .swatch.inner { border: 1.5px solid var(--accent); background: var(--accent-soft); }
  .icon { width: 40px; height: 40px; flex: none; border: 0; background: none; color: var(--ink-2); border-radius: 50%; cursor: pointer; }
  .icon:disabled { opacity: 0.3; cursor: default; }
  .ghost { min-height: 40px; border: 0; background: none; color: var(--accent); cursor: pointer; display: flex; align-items: center; gap: 6px; }
  .resume { justify-self: start; }
  .primary { border: 0; border-radius: var(--r-full); padding: 0 var(--s-5); min-height: 44px; background: var(--accent); color: var(--on-accent); font-weight: 600; cursor: pointer; }
  .primary:disabled { opacity: 0.5; cursor: default; }
  .danger { justify-self: start; min-height: 40px; border: 1px solid var(--danger, #d16969); color: var(--danger, #d16969); background: none; border-radius: var(--r-full); padding: 0 var(--s-4); cursor: pointer; }
  .removed { color: var(--ink-2); }
  .sr { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); }
</style>
