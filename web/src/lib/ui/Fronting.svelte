<script lang="ts">
  // Fronting (Figma F3.1, F3.11; Android Fronting.kt): one tab per front, the selected front's layers
  // as nested containers, separately listed layers, Away, the other fronts and a Switch button.
  // Each front shows Simple, Detailed or Complete (F3.9, `fronting.display`, D-122).
  import { sync, type Projection } from '../sync/client';
  import { place, resumeFollowing } from '../front.svelte';
  import { frontLayers, frontList } from '../front-view';
  import type { FrontPresence } from '../core/fronts';
  import * as fd from '../fronting-display';
  import { frontingUi } from '../fronting-ui.svelte';
  import { ownMembers, whoKey, whoMap, type Who } from '../fronting-subjects';
  import LinkDevice from './LinkDevice.svelte';
  import Icon from './Icon.svelte';
  import Face from './Face.svelte';
  import ReviewRow from './ReviewRow.svelte';
  import PlaceSheet from './PlaceSheet.svelte';
  import MemberState from './MemberState.svelte';
  import MenuSheet from './kit/MenuSheet.svelte';
  import MenuRow from './kit/MenuRow.svelte';

  let { projection, dark, onswitch }: { projection: Projection; dark: boolean; onswitch: (front?: string, addTo?: string) => void } = $props();

  const configured = $derived(!!projection.front_systems?.[sync.accountId]);
  const display = $derived(fd.displayPrefs(projection.rows.pref, sync.accountId));
  const statuses = $derived(fd.memberStatuses(projection.rows.pref, sync.accountId));
  const customStates = $derived(fd.customStates(projection.rows.custom_state));
  const fronts = $derived(fd.ordered(frontList(projection, sync.accountId), sync.accountId, display));
  const tabs = $derived(fronts.filter((f) => f.body || !fd.front(display, f.id).hidden));
  const selected = $derived(fronts.find((f) => f.id === frontingUi.front) ?? fronts[0]);
  const frontId = $derived(selected?.id ?? sync.accountId);
  const view = $derived(fd.front(display, frontId));
  const layers = $derived(frontLayers(projection, sync.accountId));
  const who = $derived(whoMap(projection));
  const own = $derived(ownMembers(projection));
  const scene = $derived(fd.scene(layers, selected?.entries ?? [], display, frontId, own.map((m) => m.id)));
  // when each subject arrived: the front's effective history, cached per front change (never per render)
  const since = $derived.by(() => {
    // a front delta (or the replica finishing its load) brings a new history to read
    void projection.front_systems;
    const out = new Map<string, number>();
    const intervals = configured ? sync.frontHistory(frontId)?.effective.intervals ?? [] : projection.fronts[sync.accountId]?.intervals ?? [];
    for (const i of intervals) if (i.end_at === null) out.set(i.subject_id, Math.min(out.get(i.subject_id) ?? Infinity, i.start_at));
    return out;
  });
  const note = $derived.by(() => {
    void projection.front_systems;
    if (view.preset !== 'complete') return null;
    const rows = configured ? sync.frontHistory(frontId)?.saved.switches ?? [] : projection.fronts[sync.accountId]?.switches ?? [];
    return [...rows].reverse().find((r) => !r.retracted)?.note || null;
  });
  const frontSince = $derived(since.size ? Math.min(...since.values()) : null);
  let now = $state(Date.now());
  $effect(() => { const t = setInterval(() => (now = Date.now()), 30_000); return () => clearInterval(t); });
  const time = (t: number) => new Date(t).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
  // two devices that switched together (F3.6) are answered on the Review page
  const reviews = $derived.by(() => {
    void projection.front_systems;
    const open = configured ? sync.frontHistory(frontId)?.reviews ?? [] : projection.reviews?.[sync.accountId] ?? [];
    return open.filter((r) => !projection.rows.front_review?.[r.id]?.fields?.resolution).length;
  });
  const showEmpty = $derived(!view.hideEmpty || view.preset === 'complete');
  const rings = $derived(scene.nested.filter((l, i) => i === 0 || showEmpty || l.entries.length));
  let awayOpen = $state<boolean | null>(null);
  const awayShown = $derived(awayOpen ?? !view.collapseAway);
  let openSeparate = $state<string[]>([]);
  let simpleMore = $state(false);
  let menu = $state(false);
  let placing = $state<Who | null>(null);
  let stating = $state<Who | null>(null);
  let rowMenu = $state<Who | null>(null);

  const whoOf = (e: FrontPresence) => who.get(whoKey(e.subject_type, e.subject_id));
  function line(e: FrontPresence): string {
    const t = since.get(e.subject_id);
    const st = view.preset !== 'simple' && e.subject_type === 'member' ? statuses.get(e.subject_id) : undefined;
    if (st && fd.statusShowing(st, t)) return fd.statusLine(st, customStates);
    return [whoOf(e)?.pronouns, t ? `since ${time(t)}` : null].filter(Boolean).join(' · ');
  }
  const took = (e: FrontPresence) => (view.showTime && since.has(e.subject_id) ? fd.duration(now - since.get(e.subject_id)!) : '');
  function removeFrom(w: Who) {
    const name = selected?.name ?? 'Body';
    place([{ type: w.type, id: w.id, layer: null, present: true }], `${w.name} left ${name}`, configured ? frontId : null, selected?.entries ?? [], scene.nested[0]?.id);
  }
  const open = (e: FrontPresence) => { const w = whoOf(e); if (w) placing = w; };
  function otherSummary(f: (typeof fronts)[number]): string {
    const named = projection.front_systems?.[sync.accountId];
    const r = named?.resolved[f.id];
    const speaking = r?.authorship[0] ? who.get(whoKey(r.authorship[0].subject_type, r.authorship[0].subject_id))?.name : undefined;
    const parts = [f.source && speaking ? `posting as ${speaking}` : null,
      f.state === 'following' ? `follows ${f.sourceName ?? 'another front'}` : f.state === 'overridden' ? 'changed by hand' : null].filter(Boolean) as string[];
    if (parts.length) return parts.join(' · ');
    return layers.map((l) => {
      const here = f.entries.filter((e) => e.layer_id === l.id);
      return here.length ? `${l.name}: ${here.length === 1 ? whoOf(here[0])?.name ?? '' : here.length}` : null;
    }).filter(Boolean).join(' · ') || 'No one here';
  }
  const simpleRest = $derived(scene.all.filter((l) => l.id !== scene.nested[0]?.id && !l.hidden).flatMap((l) => l.entries.map((e) => ({ e, l }))));
</script>

{#snippet memberRow(e: FrontPresence, simple: boolean)}
  <div class="row" class:simple>
    <button class="main" onclick={() => open(e)}>
      <Face who={whoOf(e)} size={40} {dark} />
      <span class="info"><strong>{whoOf(e)?.name ?? 'Someone'}</strong>{#if line(e)}<span>{line(e)}</span>{/if}</span>
      {#if took(e)}<span class="took">{took(e)}</span>{/if}
    </button>
    <button class="icon-btn" aria-label="{whoOf(e)?.name ?? 'Someone'} options" onclick={() => { const w = whoOf(e); if (w) rowMenu = w; }}><Icon name="more-vert" size={18} /></button>
  </div>
{/snippet}

<div class="home">
    <div class="tabs" role="tablist" aria-label="Fronts">
      {#each tabs as f (f.id)}
        <button role="tab" aria-selected={f.id === frontId} class:on={f.id === frontId} onclick={() => (frontingUi.front = f.id)}>
          <Icon name={fd.icon(f.id, f.name, sync.accountId, display)} size={16} /><span>{f.name}</span><span class="count">{f.entries.length}</span>
        </button>
      {/each}
      <a class="add-front" href="#/fronts" aria-label="Add or manage fronts"><Icon name="plus" size={16} /></a>
    </div>

    {#if selected && selected.state !== 'independent'}
      <div class="follow-state" role="status">
        {#if selected.state === 'following'}<span>Follows {selected.sourceName ?? 'another front'}</span>
        {:else if selected.state === 'overridden'}<span>Changed by hand · not following {selected.sourceName ?? 'its source'}</span><button class="link" onclick={() => resumeFollowing(selected.id)}>Resume following</button>
        {:else}<span>{selected.issue === 'cycle' ? 'Following loops back on itself' : 'Its source is unavailable'} · showing nothing as current</span>{/if}
      </div>
    {/if}

    <ReviewRow count={reviews} front={configured ? frontId : ''} />

    {#if view.preset === 'simple'}
      {@const inner = scene.nested[0] ?? scene.all.find((l) => !l.separate)}
      <section class="card" aria-label="Fronting now">
        <header class="card-head"><Icon name="target" size={18} /><strong>{inner?.name ?? 'Fronting'} now</strong><span class="since">{frontSince ? `since ${time(frontSince)}` : ''}</span></header>
        {#each inner?.entries ?? [] as e (whoKey(e.subject_type, e.subject_id))}
          {@render memberRow(e, true)}
        {:else}<p class="none">No one in {inner?.name ?? 'front'}</p>{/each}
        {#if simpleRest.length}
          {@const names = simpleRest.map(({ e }) => whoOf(e)).filter((w): w is Who => !!w)}
          {@const label = [...new Set(simpleRest.map(({ l }) => l.name))].length === 1 ? simpleRest[0].l.name.toLowerCase() : 'here'}
          <button class="rest" aria-expanded={simpleMore} onclick={() => (simpleMore = !simpleMore)}>
            <span class="stack">{#each names.slice(0, 3) as w (whoKey(w.type, w.id))}<Face who={w} size={22} {dark} />{/each}</span>
            <span class="rest-text">+{names.length} {label} · {names.map((w) => w.name).join(', ')}</span>
            <span class="chev" class:up={simpleMore}><Icon name="chevron" size={18} /></span>
          </button>
          {#if simpleMore}<div class="chips">{#each simpleRest as { e, l } (whoKey(e.subject_type, e.subject_id))}
            <button class="chip" onclick={() => open(e)}><Face who={whoOf(e)} size={24} {dark} /><span>{whoOf(e)?.name}</span>{#if took(e)}<small>{took(e)}</small>{/if}<span class="tag" style={fd.inkVars(l.style, l.color)}>{l.name}</span></button>
          {/each}</div>{/if}
        {/if}
      </section>
    {:else}
      <section class="card" aria-label={selected?.name ?? 'Front'}>
        <header class="card-head">
          <Icon name={fd.icon(frontId, selected?.name ?? 'Body', sync.accountId, display)} size={18} />
          <strong>{selected?.name ?? 'Body'}</strong>
          <span class="since">{[frontSince ? `since ${time(frontSince)}` : null, `${scene.here} here`].filter(Boolean).join(' · ')}</span>
          <span class="menu-wrap">
            <button class="icon-btn" aria-label="{selected?.name ?? 'Front'} options" aria-expanded={menu} onclick={() => (menu = !menu)}><Icon name="more-vert" size={20} /></button>
            {#if menu}<div class="menu" role="menu">
              <button role="menuitem" onclick={() => { menu = false; onswitch(frontId); }}>Switch…</button>
              <a role="menuitem" href="#/layers/{frontId}" onclick={() => (menu = false)}>Layers &amp; display</a>
              <a role="menuitem" href="#/history" onclick={() => (menu = false)}>Front history</a>
              <a role="menuitem" href="#/fronts" onclick={() => (menu = false)}>Manage fronts</a>
            </div>{/if}
          </span>
        </header>
        {#if note}<p class="note">{note}</p>{/if}
        {#if rings.length}
          {#snippet ring(i: number)}
            {@const l = rings[i]}
            {#if i === 0}
              <div class="layer inner" style={fd.inkVars(l.style, l.color)}>
                <div class="layer-label"><strong>{l.name}</strong><span>{[fd.shortAbout(l.about), l.entries.length > 1 ? `${l.entries.length} co-${l.name.toLowerCase()}` : null].filter(Boolean).join(' · ')}</span>
                  <button class="icon-btn add" aria-label="Add someone to {l.name}" onclick={() => onswitch(frontId, l.id)}><Icon name="plus" size={18} /></button></div>
                {#each l.entries as e (whoKey(e.subject_type, e.subject_id))}
                  {@render memberRow(e, false)}
                {:else}<p class="none">No one in {l.name}</p>{/each}
              </div>
            {:else}
              <div class="layer" style="{fd.inkVars(l.style, l.color)}; --r: {16 + 2 * i}px">
                {@render ring(i - 1)}
                <div class="layer-foot">
                  <span class="foot-label"><strong>{l.name}</strong>{#if fd.shortAbout(l.about)}<span>{fd.shortAbout(l.about)}</span>{/if}</span>
                  <span class="chips end">{#each l.entries as e (whoKey(e.subject_type, e.subject_id))}
                    <button class="chip" class:faint={l.style === 'faint' || l.style === 'plain'} onclick={() => open(e)}><Face who={whoOf(e)} size={24} {dark} faint={l.style === 'faint' || l.style === 'plain'} /><span>{whoOf(e)?.name}</span>{#if took(e)}<small>{took(e)}</small>{/if}</button>
                  {/each}</span>
                  <button class="icon-btn add" aria-label="Add someone to {l.name}" onclick={() => onswitch(frontId, l.id)}><Icon name="plus" size={18} /></button>
                </div>
              </div>
            {/if}
          {/snippet}
          {@render ring(rings.length - 1)}
        {/if}
        {#each scene.separate.filter((l) => showEmpty || l.entries.length) as l (l.id)}
          {@const faces = l.entries.map(whoOf).filter((w): w is Who => !!w)}
          {@const isOpen = openSeparate.includes(l.id)}
          <div class="separate">
            <button class="sep-row" aria-expanded={isOpen} onclick={() => (openSeparate = isOpen ? openSeparate.filter((x) => x !== l.id) : [...openSeparate, l.id])}>
              <Icon name="layers" size={18} />
              <span class="info"><span class="sep-title"><strong>{l.name}</strong> ({faces.length}){faces.length ? ` · ${faces.map((w) => w.name).join(', ')}` : ''}</span>{#if l.about}<span>{l.about}</span>{/if}</span>
              <span class="stack">{#each faces.slice(0, 3) as w (whoKey(w.type, w.id))}<Face who={w} size={24} {dark} />{/each}</span>
              <span class="turn" class:down={isOpen}><Icon name="chevron-right" size={18} /></span>
            </button>
            {#if isOpen}<div class="chips pad">{#each l.entries as e (whoKey(e.subject_type, e.subject_id))}<button class="chip" onclick={() => open(e)}><Face who={whoOf(e)} size={24} {dark} /><span>{whoOf(e)?.name}</span></button>{/each}</div>{/if}
          </div>
        {/each}
        {#if scene.unlisted.length}<p class="none">Other: {scene.unlisted.map((e) => whoOf(e)?.name).filter(Boolean).join(', ')}</p>{/if}
      </section>

      {#if scene.awayShown && scene.away.length}
        {@const away = scene.away.map((id) => who.get(whoKey('member', id))).filter((w): w is Who => !!w)}
        <button class="away" aria-expanded={awayShown} onclick={() => (awayOpen = !awayShown)}>
          <Icon name="eye-off" size={18} /><span class="info">Away · {away.length} member{away.length === 1 ? '' : 's'}</span>
          {#if !awayShown}<span class="stack muted">{#each away.slice(0, 3) as w (w.id)}<span class="mute-face">{w.glyph.slice(0, 2)}</span>{/each}</span>{/if}
          <span class="turn" class:down={awayShown}><Icon name="chevron-right" size={18} /></span>
        </button>
        {#if awayShown}<div class="chips">{#each away.slice(0, 200) as w (w.id)}<button class="chip faint" onclick={() => (placing = w)}><Face who={w} size={24} {dark} faint /><span>{w.name}</span></button>{/each}</div>{/if}
      {/if}

      {#if view.showOthers && configured && tabs.length > 1}
        <div class="others-head"><span>Other fronts</span><a href="#/fronts">Manage</a></div>
        {#each tabs.filter((f) => f.id !== frontId) as f (f.id)}
          <button class="other" onclick={() => (frontingUi.front = f.id)}>
            <span class="round"><Icon name={fd.icon(f.id, f.name, sync.accountId, display)} size={18} /></span>
            <span class="info"><strong>{f.name}</strong><span>{otherSummary(f)}</span></span>
            <span class="stack">{#each f.entries.slice(0, 3) as e (whoKey(e.subject_type, e.subject_id))}<Face who={whoOf(e)} size={24} {dark} />{/each}</span>
            <Icon name="chevron-right" size={18} />
          </button>
        {/each}
      {/if}
    {/if}

    <button class="fab" onclick={() => onswitch(frontId)}><Icon name="swap" size={20} />Switch</button>
  <details class="device-tools"><summary>Devices and data</summary><LinkDevice />
  <section class="data-links" aria-label="Data">
    <h2>Data</h2>
    <a href="#/settings/trash">Trash · deleted items</a>
    <a href="#/settings/tokens">API tokens · overlays</a>
  </section></details>
</div>

{#if placing}<PlaceSheet {projection} {dark} front={frontId} who={placing} since={since.get(placing.id) ?? null} onclose={() => (placing = null)} onstate={() => { stating = placing; placing = null; }} />{/if}
{#if stating}<MemberState {projection} {dark} who={stating} onclose={() => (stating = null)} />{/if}
{#if rowMenu}
  <MenuSheet title={rowMenu.name} onclose={() => (rowMenu = null)}>
    {#if rowMenu.type === 'member'}<MenuRow icon="smile" label="Set state" onclick={() => { stating = rowMenu; rowMenu = null; }} />{/if}
    <MenuRow icon="swap" label="Move to another layer" onclick={() => { placing = rowMenu; rowMenu = null; }} />
    <MenuRow icon="delete" danger label="Remove from {selected?.name ?? 'Body'}" onclick={() => { if (rowMenu) removeFrom(rowMenu); rowMenu = null; }} />
  </MenuSheet>
{/if}

<style>
  .home { display: grid; gap: 10px; max-width: 720px; padding-bottom: 96px; }
  .tabs { display: flex; gap: 8px; overflow-x: auto; align-items: center; padding-bottom: 12px; margin-bottom: 2px; border-bottom: 1px solid var(--line); }
  .tabs button { display: flex; align-items: center; gap: 6px; padding: 7px 12px 7px 10px; min-height: 34px; border-radius: 18px; border: 1px solid transparent; background: var(--surface-2); color: var(--ink); font: inherit; font-size: 13px; font-weight: 500; cursor: pointer; white-space: nowrap; }
  .tabs button :global(.icon) { color: var(--ink-3); }
  .tabs .count { font-size: 12px; font-weight: 600; color: var(--ink-3); }
  .tabs button.on { background: var(--accent-soft); border-color: var(--accent); color: var(--accent); font-weight: 600; }
  .tabs button.on :global(.icon), .tabs button.on .count { color: var(--accent); }
  /* on a phone the tabs share the app bar's panel, as in Figma F3.1 */
  @media (max-width: 1023px) { .tabs { margin: -16px -16px 2px; padding: 2px 16px 12px; background: var(--surface); } }
  .add-front { width: 32px; height: 32px; flex: none; display: grid; place-items: center; border: 1px dashed var(--line); border-radius: 50%; color: var(--ink-3); }
  .follow-state { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; justify-content: space-between; padding: 6px 12px; border-radius: 12px; background: var(--surface); color: var(--ink-2); font-size: 13px; }
  .link { background: none; border: 0; color: var(--accent); font: inherit; font-weight: 600; cursor: pointer; min-height: 40px; }
  .card { background: var(--surface); border-radius: 22px; padding: 12px; display: grid; gap: 10px; }
  .card-head { display: flex; align-items: center; gap: 8px; padding: 2px 0 0 4px; min-width: 0; }
  .card-head > :global(.icon) { color: var(--accent); }
  .card-head strong { font-size: 16px; font-weight: 600; }
  .since { flex: 1; min-width: 0; color: var(--ink-3); font-size: 12px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .note { margin: 0 4px; color: var(--ink-2); font-size: 13px; }
  .menu-wrap { position: relative; }
  .menu { position: absolute; right: 0; top: 100%; z-index: 5; display: grid; min-width: 190px; padding: 6px; background: var(--surface-2); border-radius: 12px; box-shadow: var(--shadow-pop); }
  .menu > * { text-align: left; background: none; border: 0; padding: 10px 12px; border-radius: 8px; color: var(--ink); font: inherit; font-size: 14px; text-decoration: none; cursor: pointer; }
  .menu > *:hover { background: var(--surface-3, var(--surface)); }
  .icon-btn { width: 32px; height: 32px; flex: none; display: grid; place-items: center; background: none; border: 0; border-radius: 50%; color: var(--ink-3); cursor: pointer; }
  .icon-btn.add { color: var(--l-label); }
  .layer { border: 1.5px var(--l-dash) var(--l-border); background: var(--l-fill); border-radius: var(--r, 18px); padding-bottom: 10px; display: grid; gap: 6px; }
  .layer > .layer, .layer > .inner { margin: -1.5px -1.5px 0; }
  .layer.inner { border-radius: 16px; padding: 10px 8px 8px; gap: 6px; }
  .layer-label { display: flex; align-items: center; gap: 6px; padding: 0 0 2px 4px; min-width: 0; }
  .layer-label strong { color: var(--l-label); font-size: 12px; font-weight: 700; }
  .layer-label span { flex: 1; min-width: 0; color: var(--ink-3); font-size: 11px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .row { display: flex; align-items: center; width: 100%; border-radius: 12px; background: var(--surface); color: var(--ink); }
  .row.simple { background: var(--accent-soft); }
  .row .main { flex: 1; min-width: 0; display: flex; align-items: center; gap: 10px; padding: 8px 4px 8px 8px; border: 0; border-radius: 12px; background: none; color: inherit; font: inherit; cursor: pointer; text-align: left; }
  .info { flex: 1; min-width: 0; display: grid; gap: 1px; text-align: left; }
  .info strong { font-size: 15px; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .info > span { color: var(--ink-3); font-size: 12px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .took { color: var(--ink-3); font-size: 13px; font-weight: 600; }
  .none { margin: 0; padding: 8px; color: var(--ink-2); font-size: 13px; }
  .layer-foot { display: flex; align-items: center; gap: 8px; padding: 2px 4px 0 10px; }
  .foot-label { display: grid; }
  .foot-label strong { color: var(--l-label); font-size: 12px; font-weight: 700; line-height: 16px; }
  .foot-label span { color: var(--ink-3); font-size: 11px; line-height: 14px; }
  .chips { display: flex; flex-wrap: wrap; gap: 6px; }
  .chips.end { flex: 1; justify-content: flex-end; }
  .chips.pad { padding: 0 12px 10px; }
  .chip { display: inline-flex; align-items: center; gap: 6px; padding: 3px 10px 3px 3px; border-radius: 16px; border: 0; background: var(--surface-2); color: var(--ink); font: inherit; font-size: 13px; font-weight: 500; cursor: pointer; }
  .chip.faint { color: var(--ink-3); }
  .chip small { color: var(--ink-3); font-size: 12px; }
  .tag { font-size: 10px; font-weight: 700; padding: 1px 7px; border-radius: 8px; background: var(--l-tag-fill); color: var(--l-tag-ink); border: 1px var(--l-dash) var(--l-tag-border); }
  .separate { border-radius: 16px; background: var(--line); }
  .sep-row { display: flex; align-items: center; gap: 10px; width: 100%; padding: 10px 12px 10px 14px; background: none; border: 0; color: var(--ink); font: inherit; cursor: pointer; text-align: left; }
  .sep-title { color: var(--ink) !important; font-size: 14px !important; font-weight: 500; }
  .sep-title strong { font-size: 14px; font-weight: 700; }
  .stack { display: flex; }
  .stack > :global(*) + :global(*) { margin-left: -6px; }
  .mute-face { width: 24px; height: 24px; display: grid; place-items: center; border-radius: 50%; background: var(--line); color: var(--ink-2); font-size: 11px; font-weight: 700; }
  .away, .other { display: flex; align-items: center; gap: 10px; width: 100%; padding: 10px 12px 10px 14px; border: 0; border-radius: 16px; background: var(--surface); color: var(--ink-3); font: inherit; cursor: pointer; text-align: left; }
  .away .info { font-size: 14px; font-weight: 500; }
  .other { gap: 12px; padding: 10px 12px; color: var(--ink); }
  .round { width: 36px; height: 36px; flex: none; display: grid; place-items: center; border-radius: 50%; background: var(--surface-2); color: var(--ink-2); }
  .others-head { display: flex; justify-content: space-between; padding: 6px 4px 0; font-size: 13px; font-weight: 600; color: var(--ink-3); }
  .others-head a { color: var(--accent); text-decoration: none; }
  .rest { display: flex; align-items: center; gap: 8px; width: 100%; padding: 6px 4px; background: none; border: 0; border-radius: 12px; color: var(--ink-3); font: inherit; font-size: 13px; cursor: pointer; text-align: left; }
  .rest-text { flex: 1; min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .turn { display: grid; }
  .turn.down { transform: rotate(90deg); }
  .chev { display: grid; transform: rotate(90deg); }
  .chev.up { transform: rotate(-90deg); }
  .fab { position: fixed; right: max(16px, calc(50vw - 360px)); bottom: calc(92px + env(safe-area-inset-bottom)); z-index: 15; display: flex; align-items: center; gap: 8px; padding: 14px 20px 14px 16px; border: 0; border-radius: 999px; background: var(--accent); color: var(--on-accent); font: inherit; font-size: 15px; font-weight: 600; box-shadow: 0 4px 14px rgba(0, 0, 0, 0.35); cursor: pointer; }
  @media (min-width: 1024px) { .fab { bottom: 24px; right: 32px; } }
  .device-tools { margin-top: 16px; border-top: 1px solid var(--line); color: var(--ink-3); padding-block: 16px; }
  .device-tools summary { cursor: pointer; font-size: var(--fs-sm); padding-block: 8px; }
  .data-links { display: grid; gap: var(--s-1); justify-items: start; }
  .data-links a { color: var(--accent); text-decoration: none; font-size: var(--fs-sm); }
  .data-links h2 { font-size: var(--fs-sm); font-weight: 600; color: var(--ink-2); }
</style>
