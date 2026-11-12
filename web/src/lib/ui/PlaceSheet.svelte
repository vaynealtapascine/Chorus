<script lang="ts">
  // Where one member is (Figma F3.3; Android PlaceSheet): their place in this front as nested
  // choices, Away, the separately listed layers, the other fronts they're also in, and their
  // state (F3.10, `member_status:`), which only the system's own devices see.
  import { place } from '../front.svelte';
  import { frontLayers, frontList } from '../front-view';
  import * as fd from '../fronting-display';
  import type { Who } from '../fronting-subjects';
  import { sync, type Projection } from '../sync/client';
  import Face from './Face.svelte';
  import Icon from './Icon.svelte';

  let { projection, dark, front, who, since, onclose, onstate }: {
    projection: Projection; dark: boolean; front: string; who: Who; since: number | null; onclose: () => void; onstate: () => void;
  } = $props();
  const configured = $derived(!!projection.front_systems?.[sync.accountId]);
  const display = $derived(fd.displayPrefs(projection.rows.pref, sync.accountId));
  const fronts = $derived(fd.ordered(frontList(projection, sync.accountId), sync.accountId, display));
  const here = $derived(fronts.find((f) => f.id === front));
  const scene = $derived(fd.scene(frontLayers(projection, sync.accountId), here?.entries ?? [], display, front, []));
  const nested = $derived(scene.all.filter((l) => !l.separate));
  const entry = $derived(here?.entries.find((e) => e.subject_type === who.type && e.subject_id === who.id));
  let choice = $state<string | null | undefined>(undefined);
  const chosen = $derived(choice === undefined ? entry?.layer_id ?? null : choice);
  const layerName = (id: string | null) => (id ? scene.all.find((l) => l.id === id)?.name ?? id : 'Away');
  const states = $derived(fd.customStates(projection.rows.custom_state));
  const current = $derived(fd.memberStatuses(projection.rows.pref, sync.accountId).get(who.id) ?? null);
  const status = $derived(current && fd.statusShowing(current, entry ? since : null) ? fd.statusLine(current, states) : null);
  const time = (t: number) => new Date(t).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
  const frontName = $derived(here?.name ?? 'Body');

  function save() {
    if (chosen === (entry?.layer_id ?? null)) return onclose();
    place([{ type: who.type, id: who.id, layer: chosen, present: !!entry }],
      chosen === null ? `${who.name} left ${frontName}` : `${who.name} is ${layerName(chosen).toLowerCase()} in ${frontName}`, configured ? front : null,
      here?.entries ?? [], nested[0]?.id);
    onclose();
  }
  function remove() {
    place([{ type: who.type, id: who.id, layer: null, present: true }], `${who.name} left ${frontName}`, configured ? front : null, here?.entries ?? [], nested[0]?.id);
    onclose();
  }
  function toggleOther(f: (typeof fronts)[number], on: boolean) {
    const present = f.entries.some((e) => e.subject_type === who.type && e.subject_id === who.id);
    place([{ type: who.type, id: who.id, layer: on ? nested[0]?.id ?? 'front' : null, present }], on ? `${who.name} is in ${f.name}` : `${who.name} left ${f.name}`, f.id, f.entries, nested[0]?.id);
  }
</script>

<svelte:window onkeydown={(e) => e.key === 'Escape' && onclose()} />
<div class="scrim" onclick={onclose} role="presentation"></div>
<div class="sheet" role="dialog" aria-modal="true" aria-label="Place {who.name}">
  <div class="handle" aria-hidden="true"></div>
    <header><Face {who} size={52} {dark} />
      <div class="titles"><h2>{who.name}</h2><p>{layerName(entry?.layer_id ?? null).toLowerCase()} in {frontName}{entry && since ? ` · since ${time(since)}` : ''}</p></div>
      <button class="icon-btn" aria-label="Close" onclick={onclose}><Icon name="close" size={22} /></button></header>
    <p class="section">Place in {frontName}</p>
    <div class="picker" role="radiogroup" aria-label="Place in {frontName}">
      {#if nested.length}
        {#snippet box(i: number)}
          {@const l = nested[i]}
          <div class="box" class:first={i === 0} class:on={chosen === l.id} style="--r: {14 + 2 * i}px">
            {#if i > 0}{@render box(i - 1)}{/if}
            {@render choiceLine(l.id, l.name, (l.about ?? '').toLowerCase())}
          </div>
        {/snippet}
        {@render box(nested.length - 1)}
      {/if}
      <div class="box single" class:on={chosen === null}>{@render choiceLine(null, 'Away', `not fronting in ${frontName}`)}</div>
      {#each scene.all.filter((l) => l.separate) as l (l.id)}
        <div class="box single" class:on={chosen === l.id}>{@render choiceLine(l.id, l.name, l.about ?? 'listed separately', l.hidden)}</div>
      {/each}
    </div>
    {#if who.type === 'member'}
      <button class="setting" onclick={onstate}><span><strong>State</strong><span>{status ?? ''}</span></span><Icon name="chevron-right" size={18} /></button>
    {/if}
    {#if configured && fronts.length > 1}
      <p class="section">Also present in</p>
      {#each fronts.filter((f) => f.id !== front) as f (f.id)}
        {@const there = f.entries.find((e) => e.subject_type === who.type && e.subject_id === who.id)}
        <label class="setting toggle">
          <span><strong>{f.name}</strong><span>{there ? layerName(there.layer_id) : f.state === 'following' ? `Follows ${f.sourceName ?? 'another front'} · changing it stops following` : 'Not here'}</span></span>
          <input type="checkbox" role="switch" checked={!!there} onchange={(e) => toggleOther(f, e.currentTarget.checked)} />
        </label>
      {/each}
    {/if}
    <div class="buttons">
      {#if entry}<button class="outline danger" onclick={remove}>Remove</button>{/if}
      <button class="primary grow" onclick={save}>{chosen === (entry?.layer_id ?? null) ? (entry ? `Keep ${layerName(chosen).toLowerCase()}` : 'Keep away')
        : chosen === null ? 'Set away' : `Move to ${layerName(chosen).toLowerCase()}`}</button>
    </div>
</div>

{#snippet choiceLine(id: string | null, title: string, about: string, hidden = false)}
  <button class="choice" role="radio" aria-checked={chosen === id} onclick={() => (choice = id)}>
    <span class="text"><span class="t">{title}{#if hidden}<Icon name="eye-off" size={12} />{/if}</span>{#if about}<span class="a">{about}</span>{/if}</span>
    <span class="radio" class:on={chosen === id}></span>
  </button>
{/snippet}

<style>
  .scrim { position: fixed; inset: 0; z-index: 30; background: rgba(0, 0, 0, 0.55); }
  .sheet { position: fixed; z-index: 31; inset: auto 0 0 0; margin: 0 auto; max-width: 560px; max-height: 92vh; overflow: auto; background: var(--surface);
    border-radius: 20px 20px 0 0; box-shadow: var(--shadow-pop); padding-bottom: env(safe-area-inset-bottom); display: grid; align-content: start; }
  @media (min-width: 641px) { .sheet { inset: 6vh 0 auto 0; border-radius: 20px; } }
  .handle { width: 36px; height: 4px; border-radius: 2px; background: var(--line); margin: 8px auto 4px; }
  header { display: flex; align-items: center; gap: 12px; padding: 8px 8px 8px 20px; }
  .titles { flex: 1; min-width: 0; }
  h2 { margin: 0; font-size: 18px; line-height: 24px; font-weight: 700; }
  .titles p { margin: 0; color: var(--ink-3); font-size: 13px; }
  .icon-btn { width: 44px; height: 44px; display: grid; place-items: center; border: 0; background: none; color: var(--ink-2); border-radius: 50%; cursor: pointer; }
  .section { margin: 0; padding: 14px 20px 6px; color: var(--accent); font-size: 13px; font-weight: 600; }
  .picker { display: grid; gap: 8px; padding: 0 16px 4px; }
  .box { display: grid; gap: 8px; padding: 10px; border-radius: var(--r, 14px); border: 1.5px dashed color-mix(in oklab, var(--ink-3) 40%, transparent); }
  .box.first { background: var(--surface-2); border: 1px solid color-mix(in oklab, var(--accent) 50%, transparent); }
  .box.single { border: 1px solid var(--line); border-radius: 14px; }
  .box.on { background: color-mix(in oklab, var(--accent) 10%, transparent); border: 2px solid var(--accent); }
  .choice { display: flex; align-items: center; gap: 10px; width: 100%; padding: 2px 4px; border: 0; background: none; color: var(--ink); font: inherit; cursor: pointer; text-align: left; }
  .text { flex: 1; display: grid; }
  .t { display: flex; align-items: center; gap: 4px; font-size: 14px; font-weight: 600; }
  .t :global(.icon) { color: var(--ink-3); }
  .box.on > .choice .t { color: var(--accent); }
  .a { color: var(--ink-3); font-size: 12px; }
  .radio { width: 22px; height: 22px; flex: none; border-radius: 50%; border: 2px solid var(--line); display: grid; place-items: center; }
  .radio.on { border-color: var(--accent); }
  .radio.on::after { content: ''; width: 10px; height: 10px; border-radius: 50%; background: var(--accent); }
  .setting { display: flex; align-items: center; gap: 16px; padding: 10px 20px; border: 0; background: none; color: var(--ink); font: inherit; text-align: left; cursor: pointer; }
  .setting > span { flex: 1; display: grid; gap: 1px; }
  .setting strong { font-size: 15px; font-weight: 400; }
  .setting span span { color: var(--ink-3); font-size: 13px; }
  .setting :global(.icon) { color: var(--ink-3); }
  .toggle input { width: 40px; height: 24px; accent-color: var(--accent); }
  .buttons { display: flex; gap: 10px; padding: 10px 16px 20px; }
  .primary, .outline { padding: 12px 16px; border-radius: 14px; font: inherit; font-size: 15px; font-weight: 600; cursor: pointer; }
  .primary { border: 0; background: var(--accent); color: var(--on-accent); }
  .primary:disabled { background: var(--surface-3, var(--surface-2)); color: var(--ink-3); cursor: default; }
  .grow { flex: 1; }
  .outline { background: none; border: 1px solid var(--line); color: var(--ink-2); }
  .danger { color: var(--danger); }
</style>
