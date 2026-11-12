<script lang="ts">
  // Fronts (Figma F3.4; Android FrontsScreen): each front's orbit, how it's set up, whether
  // Fronting shows it, its order, and new fronts from templates. Following, overrides and purpose
  // pools are resolved in core (D-080, D-085); this page writes sparse definitions and the
  // display pref (D-122). Layers live on Layers & display.
  import Button from './kit/Button.svelte';
  import { defineFront, resumeFollowing } from '../front.svelte';
  import { frontLayers, frontList, type FrontSummary } from '../front-view';
  import * as fd from '../fronting-display';
  import { whoKey, whoMap, type Who } from '../fronting-subjects';
  import { sync, type Projection } from '../sync/client';
  import Icon from './Icon.svelte';
  import Orbit from './Orbit.svelte';
  import FormCard from './kit/FormCard.svelte';
  import SectionLabel from './kit/SectionLabel.svelte';
  import Switch from './kit/Switch.svelte';

  let { projection, dark }: { projection: Projection; dark: boolean } = $props();

  const account = $derived(sync.accountId);
  const system = $derived(projection.front_systems?.[account]?.system);
  const display = $derived(fd.displayPrefs(projection.rows.pref, account));
  const all = $derived(frontList(projection, account, true));
  const live = $derived(fd.ordered(all.filter((f) => !f.deleted), account, display));
  const removed = $derived(all.filter((f) => f.deleted));
  const layers = $derived(frontLayers(projection, account));
  const who = $derived(whoMap(projection));

  let editing = $state<string | null>(null);
  let arranging = $state(false);
  let naming = $state(false);
  let newName = $state('');
  let dragging = $state<string | null>(null);

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
  function tag(f: FrontSummary): string {
    if (f.state === 'following') return `Follows ${f.sourceName ?? 'another front'}`;
    if (f.state === 'overridden') return 'Changed by hand';
    if (f.state === 'unavailable') return f.issue === 'cycle' ? 'Following loops' : 'Source unavailable';
    return f.body ? 'Main' : 'Own layers';
  }
  const sceneOf = (f: FrontSummary) => fd.scene(layers, f.entries, display, f.id, []);
  const facesOf = (entries: FrontSummary['entries']) => entries.map((e) => who.get(whoKey(e.subject_type, e.subject_id))).filter((w): w is Who => !!w);
  function about(f: FrontSummary): string {
    const own = fd.front(display, f.id).about;
    if (own) return own;
    const inner = sceneOf(f).all.find((l) => !l.separate);
    const names = facesOf(inner?.entries ?? []).map((w) => w.name);
    return names.length ? `${names.join(' and ')} ${inner!.name.toLowerCase()}` : 'No one here right now';
  }
  function create(name: string, template?: (typeof fd.TEMPLATES)[number]) {
    const n = name.trim();
    if (!n) return;
    // the first front an unconfigured account makes moves Body onto named fronts too: ask first
    if (!system && !confirm(`Adding “${n}” turns on separate fronts for this system. Body keeps its history and who's in it; this can't be turned off again, though a front can be removed.`)) return;
    const id = sync.newId();
    defineFront(id, { name: n, follow_source: template?.followsBody ? account : null });
    if (template) fd.save(fd.withFront(display, id, (v) => ({ ...v, icon: template.icon, about: template.about })));
    newName = '';
    naming = false;
  }
  function reorder(ids: string[]) { fd.save({ ...display, order: ids }); }
  function move(i: number, d: -1 | 1) {
    const ids = live.map((f) => f.id);
    const [x] = ids.splice(i, 1);
    ids.splice(i + d, 0, x);
    reorder(ids);
  }
  function drop(on: string) {
    if (!dragging || dragging === on) return;
    const ids = live.map((f) => f.id).filter((id) => id !== dragging);
    ids.splice(ids.indexOf(on), 0, dragging);
    reorder(ids);
    dragging = null;
  }
  const look = (id: string, f: (v: fd.FrontView) => fd.FrontView) => fd.save(fd.withFront(display, id, f));
</script>

<div class="fronts">
  <div class="bar">
    <a class="back" href="#/fronting" aria-label="Back to Fronting"><Icon name="back" size={22} /></a>
    <div class="titles"><strong>Fronts</strong></div>
    <button class="icon-btn" aria-pressed={arranging} aria-label={arranging ? 'Done arranging' : 'Arrange fronts'} onclick={() => (arranging = !arranging)}><Icon name={arranging ? 'check' : 'pencil'} size={22} /></button>
  </div>

  <ul class="cards">
    {#each live as f, i (f.id)}
      {@const sc = sceneOf(f)}{@const view = fd.front(display, f.id)}
      <li class="card" class:dragging={dragging === f.id} ondragover={(e) => e.preventDefault()} ondrop={() => drop(f.id)}>
        <div class="main" role="button" tabindex="0" aria-expanded={editing === f.id} aria-label="Edit {f.name}"
          onclick={() => (editing = editing === f.id ? null : f.id)} onkeydown={(e) => (e.key === 'Enter' || e.key === ' ') && (e.preventDefault(), (editing = editing === f.id ? null : f.id))}>
          <span class:dim={view.hidden}><Orbit rings={sc.all.filter((l) => !l.separate).slice(0, 3).map((l) => facesOf(l.entries))} {dark} /></span>
          <span class="text">
            <span class="name"><strong>{f.name}</strong><span class="badge">{tag(f)}</span></span>
            <span class="about">{about(f)}</span>
            <span class="tags">{#each sc.all.filter((l) => l.entries.length) as l (l.id)}<span class="tag" style={fd.inkVars(l.style, l.color)}>{l.name} {l.entries.length}</span>{/each}</span>
          </span>
        </div>
        <span class="side">
          {#if arranging}
            <button class="icon-btn" disabled={i === 0} aria-label="Move {f.name} up" onclick={() => move(i, -1)}>↑</button>
            <button class="icon-btn" disabled={i === live.length - 1} aria-label="Move {f.name} down" onclick={() => move(i, 1)}>↓</button>
          {:else}
            <button class="icon-btn" aria-label={view.hidden ? `Show ${f.name} on Fronting` : `Hide ${f.name} from Fronting`} aria-pressed={!view.hidden}
              onclick={() => look(f.id, (v) => ({ ...v, hidden: !v.hidden }))}><Icon name={view.hidden ? 'eye-off' : 'eye'} size={20} /></button>
            <span class="grip" draggable="true" role="button" tabindex="-1" aria-label="Drag to reorder {f.name}" ondragstart={() => (dragging = f.id)} ondragend={() => (dragging = null)}><Icon name="grip" size={18} /></span>
          {/if}
        </span>
        {#if f.state === 'overridden'}<button class="link resume" onclick={() => resumeFollowing(f.id)}>Resume following {f.sourceName ?? ''}</button>{/if}
        {#if editing === f.id}
          <div class="editor">
            <label>Name <input value={f.name} onchange={(e) => { const n = e.currentTarget.value.trim(); if (n && n !== f.name) defineFront(f.id, { name: n }); }} /></label>
            <label>Description <input value={view.about ?? ''} maxlength="140" placeholder={about(f)} onchange={(e) => look(f.id, (v) => ({ ...v, about: e.currentTarget.value.trim() || null }))} /></label>
            <div class="icons" role="radiogroup" aria-label="Icon">
              {#each fd.ICONS as k (k)}{@const on = fd.icon(f.id, f.name, account, display) === k}
                <button role="radio" aria-checked={on} aria-label={k} class:on onclick={() => look(f.id, (v) => ({ ...v, icon: k }))}><Icon name={k} size={18} /></button>
              {/each}
            </div>
            <label>Follows
              <select value={f.source ?? ''} onchange={(e) => defineFront(f.id, { follow_source: e.currentTarget.value || null })}>
                <option value="">Nothing — changed by hand</option>
                {#each live.filter((o) => o.id !== f.id) as o (o.id)}<option value={o.id}>{o.name}</option>{/each}
              </select>
            </label>
            <a class="link" href="#/layers/{f.id}">Layers &amp; display for {f.name}</a>
            <details>
              <summary>Who counts for what</summary>
              {#each PURPOSES as p (p.key)}
                {@const sel = selection(f.id, p.key)}
                <div class="purpose" role="group" aria-label={p.label}>
                <SectionLabel label={p.label} />
                <p class="hint">{p.hint}</p>
                <FormCard>
                  <Switch label="Use the default" checked={sel === null} onchange={(on) => setSelection(f.id, p.key, on ? null : [])} />
                  {#if sel !== null}{#each layers as l (l.id)}<Switch label={l.name} checked={sel.includes(l.id)} onchange={(on) => toggleLayer(f.id, p.key, l.id, on)} />{/each}{/if}
                </FormCard>
                </div>
              {/each}
            </details>
            {#if !f.body}<Button size="sm" variant="danger-soft" style="justify-self: start" onclick={() => { defineFront(f.id, { deleted: true }); editing = null; }}>Remove {f.name}</Button>{/if}
          </div>
        {/if}
      </li>
    {/each}
    <li class="new">
      <button class="new-head" onclick={() => (naming = true)}><span class="plus"><Icon name="plus" size={20} /></span><span><strong>New front</strong><span>Start from a template or build your own</span></span></button>
      <div class="templates">
        <!-- a template already in use isn't offered twice -->
        {#each fd.TEMPLATES.filter((t) => !live.some((f) => f.name.toLowerCase() === t.name.toLowerCase())) as t (t.name)}<button class="fr-chip" onclick={() => create(t.name, t)}>{t.name}</button>{/each}
        <button class="fr-chip" onclick={() => (naming = true)}>Custom</button>
      </div>
      {#if naming}
        <form class="add" onsubmit={(e) => { e.preventDefault(); create(newName); }}>
          <!-- svelte-ignore a11y_autofocus -->
          <input bind:value={newName} placeholder="Name, e.g. Headspace" aria-label="New front name" autofocus />
          <Button size="sm" variant="primary" disabled={!newName.trim()}>Add</Button>
        </form>
      {/if}
    </li>
  </ul>

  {#if removed.length}
    <details class="removed">
      <summary>Removed fronts ({removed.length})</summary>
      {#each removed as f (f.id)}<div class="row"><span>{f.name}</span><button class="link" onclick={() => defineFront(f.id, { deleted: false })}>Restore</button></div>{/each}
    </details>
  {/if}
</div>

<style>
  .fronts { display: grid; gap: 10px; max-width: 720px; }
  .bar { display: flex; align-items: center; gap: var(--s-1); margin: 0 0 2px -8px; }
  .back, .icon-btn { width: 44px; height: 44px; display: grid; place-items: center; border: 0; background: none; color: var(--ink-2); border-radius: 50%; cursor: pointer; font: inherit; font-size: 20px; }
  .back { color: var(--ink); }
  .icon-btn:disabled { color: var(--ink-3); opacity: 0.5; }
  .titles { flex: 1; display: grid; padding-left: var(--s-1); }
  @media (min-width: 1024px) { .titles { visibility: hidden; } }
  .titles strong { font-size: 17px; font-weight: 600; }
  .cards { list-style: none; margin: 0; padding: 0; display: grid; gap: 10px; }
  .card { display: grid; grid-template-columns: 1fr auto; gap: 0 var(--s-1); background: var(--surface); border-radius: 18px; padding: var(--s-3) var(--s-1) var(--s-3) var(--s-3); }
  .card.dragging { opacity: 0.5; }
  .main { display: flex; align-items: center; gap: 14px; cursor: pointer; min-width: 0; border-radius: var(--r-row); }
  .dim { opacity: 0.5; }
  .text { display: grid; gap: var(--s-1); min-width: 0; }
  .name { display: flex; align-items: center; gap: var(--s-2); min-width: 0; }
  .name strong { font-size: 17px; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .badge { font-size: 11px; color: var(--ink-2); background: var(--surface-2); border-radius: 6px; padding: 1px 6px; white-space: nowrap; }
  .about { color: var(--ink-2); font-size: var(--fs-sm); line-height: 17px; display: -webkit-box; -webkit-line-clamp: 2; line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
  .tags { display: flex; flex-wrap: wrap; gap: var(--s-1) 6px; }
  .tag { font-size: 11px; line-height: 14px; font-weight: 700; padding: 1px 7px; border-radius: 8px; background: var(--l-tag-fill); color: var(--l-tag-ink); border: 1px var(--l-dash) var(--l-tag-border); }
  .side { display: grid; justify-items: center; align-content: center; }
  .grip { width: 40px; height: 40px; display: grid; place-items: center; color: var(--ink-3); cursor: grab; }
  .resume { grid-column: 1 / -1; justify-self: start; }
  .link { background: none; border: 0; padding: var(--s-2) 0; color: var(--accent); font: inherit; font-weight: 600; cursor: pointer; text-decoration: none; }
  .editor { grid-column: 1 / -1; display: grid; gap: 10px; padding: var(--s-3) var(--s-2) var(--s-1) 0; border-top: 1px solid var(--line); margin-top: var(--s-3); }
  .editor label { display: grid; gap: var(--s-1); font-size: var(--fs-sm); color: var(--ink-2); }
  .editor input:not([type='checkbox']), .editor select { font: inherit; color: var(--ink); background: var(--bg); border: 1px solid var(--line); border-radius: 8px; padding: var(--s-2) 10px; }
  .icons { display: flex; gap: 6px; flex-wrap: wrap; }
  .icons button { width: 40px; height: 40px; display: grid; place-items: center; border-radius: 50%; border: 1px solid transparent; background: var(--surface-2); color: var(--ink-2); cursor: pointer; }
  .icons button.on { background: var(--accent-soft); border-color: var(--accent); color: var(--accent); }
  .hint { margin: 0; color: var(--ink-3); font-size: var(--fs-xs); }
  details summary { cursor: pointer; color: var(--accent); font-weight: 600; font-size: var(--fs-sm); padding: 6px 0; }
  .new { display: grid; gap: var(--s-3); padding: var(--s-4); border: 1px dashed var(--line); border-radius: 18px; }
  .new-head { display: flex; align-items: center; gap: var(--s-3); border: 0; background: none; color: var(--ink); font: inherit; text-align: left; cursor: pointer; padding: 0; }
  .new-head > span:last-child { display: grid; }
  .new-head strong { color: var(--accent); font-size: var(--fs-md); font-weight: 600; }
  .new-head span span { color: var(--ink-3); font-size: var(--fs-xs); }
  .plus { width: 36px; height: 36px; display: grid; place-items: center; border-radius: 50%; background: var(--accent-soft); color: var(--accent); }
  .templates { display: flex; flex-wrap: wrap; gap: 6px; }
  .add { display: flex; gap: var(--s-2); }
  .add input { flex: 1; min-width: 0; font: inherit; color: var(--ink); background: var(--bg); border: 1px solid var(--line); border-radius: var(--r-sm); padding: 10px var(--s-3); }
  .removed summary { color: var(--ink-2); }
  .removed .row { display: flex; justify-content: space-between; align-items: center; padding: var(--s-1) var(--s-3); background: var(--surface); border-radius: var(--r-row); margin-top: 6px; }
</style>
