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
  import Switch from './kit/Switch.svelte';

  let { projection, dark, front, who, since, onclose }: {
    projection: Projection; dark: boolean; front: string; who: Who; since: number | null; onclose: () => void;
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
  // the state picker (F3.10; Android MemberStatePage): any of the common states, the system's own, own words, a note
  let stateView = $state(false);
  let picked = $state<string[]>([]);
  let offered = $state<string[]>([]);
  let note = $state('');
  let clear = $state(true);
  let typing = $state(false);
  let words = $state('');
  function openState() {
    picked = [...(current?.tags ?? [])]; offered = fd.statusChoices(states, current);
    note = current?.note ?? ''; clear = current?.clearOnLeave ?? true; typing = false; words = ''; stateView = true;
  }
  const ready = $derived(picked.length > 0 || !!note.trim());
  const toggleTag = (t: string) => (picked = picked.includes(t) ? picked.filter((x) => x !== t) : [...picked, t]);
  function addWords() {
    const t = words.trim().slice(0, fd.MAX_STATUS_TAG);
    if (t) { if (!offered.includes(t)) offered = [...offered, t]; if (!picked.includes(t)) picked = [...picked, t]; }
    words = ''; typing = false;
  }
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
  function setState(clearIt = false) {
    if (!clearIt && !ready) return;
    fd.setStatus(who.id, clearIt ? null : { ...current, tags: picked, note: note.trim() || null, at: Date.now(), clearOnLeave: clear }, states);
    stateView = false;
  }
</script>

<svelte:window onkeydown={(e) => e.key === 'Escape' && onclose()} />
<div class="scrim" onclick={onclose} role="presentation"></div>
<div class="sheet" role="dialog" aria-modal="true" aria-label={stateView ? `${who.name}'s state` : `Place ${who.name}`}>
  <div class="handle" aria-hidden="true"></div>
  {#if stateView}
    <header><button class="icon-btn" aria-label="Back" onclick={() => (stateView = false)}><Icon name="back" size={22} /></button>
      <div class="titles"><h2>{who.name}'s state</h2></div>
      <button class="icon-btn" class:accent={ready} aria-label="Set state" disabled={!ready} onclick={() => setState()}><Icon name="check" size={22} /></button></header>
    <div class="who"><Face {who} size={56} {dark} />
      <div class="titles"><strong>{who.name}</strong>
        {#if picked.length}<span class="picked">{#each picked as t (t)}<span>{fd.statusLabel(t, states)}</span>{/each}</span>{/if}</div></div>
    <p class="section">How are things?</p>
    <div class="states" role="group" aria-label="How are things?">
      {#each offered as t (t)}
        {@const on = picked.includes(t)}
        <button role="checkbox" aria-checked={on} class:on onclick={() => toggleTag(t)}>{#if on}<Icon name="check" size={14} />{/if}{fd.statusLabel(t, states)}</button>
      {/each}
      <button class="add" onclick={() => (typing = true)}><Icon name="plus" size={14} />Custom</button>
    </div>
    <!-- svelte-ignore a11y_autofocus -->
    {#if typing}<input class="own" bind:value={words} maxlength={fd.MAX_STATUS_TAG} placeholder="Custom" aria-label="Custom" autofocus
      onkeydown={(e) => { if (e.key === 'Enter') { e.preventDefault(); addWords(); } }} onblur={addWords} />{/if}
    <textarea class="own" bind:value={note} maxlength={fd.MAX_STATUS_NOTE} rows="2" placeholder="Note (optional)" aria-label="Note (optional)"></textarea>
    <p class="section">Options</p>
    <div class="opts"><Switch label="Clear when {who.name} stops fronting" bind:checked={clear} /></div>
    <div class="buttons">
      <button class="outline" disabled={!current} onclick={() => setState(true)}>Clear</button>
      <button class="primary grow" disabled={!ready} onclick={() => setState()}>Set state</button>
    </div>
  {:else}
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
      <button class="setting" onclick={openState}><span><strong>State</strong><span>{status ?? ''}</span></span><Icon name="chevron-right" size={18} /></button>
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
  {/if}
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
  .states { display: flex; flex-wrap: wrap; gap: 8px; padding: 12px 20px; }
  .states button { display: inline-flex; align-items: center; padding: 8px 14px; border-radius: 999px; border: 0; background: var(--surface-2); color: var(--ink); font: inherit; font-size: 14px; font-weight: 500; cursor: pointer; }
  .states button.on { background: var(--accent-soft); color: var(--accent); }
  .states button :global(.icon) { margin-right: 6px; }
  .states .add { color: var(--ink-2); }
  .opts { padding: 0 6px; }
  .who { display: flex; align-items: center; gap: 14px; padding: 4px 20px 0; }
  .who strong { font-size: 18px; font-weight: 600; }
  .picked { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 4px; }
  .picked span { padding: 3px 10px; border-radius: 999px; background: var(--accent-soft); color: var(--accent); font-size: 12px; font-weight: 500; }
  .icon-btn.accent { color: var(--accent); }
  .icon-btn:disabled { color: var(--ink-3); cursor: default; }
  .outline:disabled { opacity: 0.5; cursor: default; }
  textarea.own { margin-top: 10px; resize: vertical; }
  .own { margin: 0 20px; font: inherit; color: var(--ink); background: var(--bg); border: 1px solid var(--line); border-radius: 10px; padding: 12px 14px; }
</style>
