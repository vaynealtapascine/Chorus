<script lang="ts">
  // Where one member is (Figma F3.3; Android PlaceSheet): their place in this front as nested
  // choices, Away, the separately listed layers, the other fronts they're also in, and their
  // state (F3.10, `member_status:`), which only the system's own devices see.
  import { place } from '../front.svelte';
  import { frontLayers, frontList } from '../front-view';
  import * as fd from '../fronting-display';
  import type { Who } from '../fronting-subjects';
  import { sync, type Projection } from '../sync/client';
  import Icon from './Icon.svelte';
  import Sheet from './Sheet.svelte';
  import FormCard from './kit/FormCard.svelte';
  import SectionLabel from './kit/SectionLabel.svelte';
  import Switch from './kit/Switch.svelte';
  import ValueRow from './kit/ValueRow.svelte';

  let { projection, front, who, since, onclose, onstate }: {
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

<Sheet title={who.name} subtitle="{layerName(entry?.layer_id ?? null).toLowerCase()} in {frontName}{entry && since ? ` · since ${time(since)}` : ''}" {onclose}>
  <SectionLabel label="Place in {frontName}" />
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
    <FormCard><ValueRow label="State" value={status} onclick={onstate} /></FormCard>
  {/if}
  {#if configured && fronts.length > 1}
    <SectionLabel label="Also present in" />
    <FormCard>
      {#each fronts.filter((f) => f.id !== front) as f (f.id)}
        {@const there = f.entries.find((e) => e.subject_type === who.type && e.subject_id === who.id)}
        <Switch label={f.name} checked={!!there} onchange={(on) => toggleOther(f, on)}
          sub={there ? layerName(there.layer_id) : f.state === 'following' ? `Follows ${f.sourceName ?? 'another front'} · changing it stops following` : 'Not here'} />
      {/each}
    </FormCard>
  {/if}
  {#snippet footer()}
    {#if entry}<button class="fr-wide neutral remove" onclick={remove}>Remove</button>{/if}
    <button class="fr-wide" onclick={save}>{chosen === (entry?.layer_id ?? null) ? (entry ? `Keep ${layerName(chosen).toLowerCase()}` : 'Keep away')
      : chosen === null ? 'Set away' : `Move to ${layerName(chosen).toLowerCase()}`}</button>
  {/snippet}
</Sheet>

{#snippet choiceLine(id: string | null, title: string, about: string, hidden = false)}
  <button class="choice" role="radio" aria-checked={chosen === id} onclick={() => (choice = id)}>
    <span class="text"><span class="t">{title}{#if hidden}<Icon name="eye-off" size={12} />{/if}</span>{#if about}<span class="a">{about}</span>{/if}</span>
    <span class="radio" class:on={chosen === id}></span>
  </button>
{/snippet}

<style>
  .picker { display: grid; gap: var(--s-2); }
  .box { display: grid; gap: var(--s-2); padding: 10px; border-radius: var(--r, 14px); border: 1.5px dashed color-mix(in oklab, var(--ink-3) 40%, transparent); }
  .box.first { background: var(--surface-2); border: 1px solid color-mix(in oklab, var(--accent) 50%, transparent); }
  .box.single { border: 1px solid var(--line); border-radius: var(--r-control); }
  .box.on { background: color-mix(in oklab, var(--accent) 10%, transparent); border: 2px solid var(--accent); }
  .choice { display: flex; align-items: center; gap: 10px; width: 100%; padding: 2px var(--s-1); border: 0; background: none; color: var(--ink); font: inherit; cursor: pointer; text-align: left; }
  .text { flex: 1; display: grid; }
  .t { display: flex; align-items: center; gap: var(--s-1); font-size: 14px; font-weight: 600; }
  .t :global(.icon) { color: var(--ink-3); }
  .box.on > .choice .t { color: var(--accent); }
  .a { color: var(--ink-3); font-size: var(--fs-xs); }
  .radio { width: 22px; height: 22px; flex: none; border-radius: 50%; border: 2px solid var(--line); display: grid; place-items: center; }
  .radio.on { border-color: var(--accent); }
  .radio.on::after { content: ''; width: 10px; height: 10px; border-radius: 50%; background: var(--accent); }
  .remove { flex: 0 0 auto; color: var(--danger); }
</style>
