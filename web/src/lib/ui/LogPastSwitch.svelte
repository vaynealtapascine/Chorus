<script lang="ts">
  import { untrack } from 'svelte';
  // Log a past switch (Figma F3.5; Android LogPastSwitch.kt): who was there, on which layer, from
  // when until when. Written as history only: the stretch starts at From and, unless something
  // was already recorded in between, the earlier state comes back at Until, so who's fronting now
  // never changes.
  import { frontLayers, frontList } from '../front-view';
  import type { FrontPresence } from '../core/fronts';
  import type { NamedHistory } from '../front-history';
  import * as fd from '../fronting-display';
  import { ownMembers, whoKey, whoMap, type Who } from '../fronting-subjects';
  import { fuzzy, groups } from '../data';
  import { sync, type Projection } from '../sync/client';
  import Face from './Face.svelte';
  import Icon from './Icon.svelte';
  import FormCard from './kit/FormCard.svelte';
  import SectionLabel from './kit/SectionLabel.svelte';

  let { projection, dark, front: initial, named, onclose }: {
    projection: Projection; dark: boolean; front: string; named: NamedHistory | null; onclose: () => void;
  } = $props();
  const configured = $derived(!!projection.front_systems?.[sync.accountId]);
  const display = $derived(fd.displayPrefs(projection.rows.pref, sync.accountId));
  const fronts = $derived(fd.ordered(frontList(projection, sync.accountId), sync.accountId, display));
  let front = $state(untrack(() => initial));
  const history = $derived(configured ? (front === initial ? named : sync.frontHistory(front)) : null);
  const scene = $derived(fd.scene(frontLayers(projection, sync.accountId), [], display, front, []));
  const inner = $derived(scene.all.find((l) => !l.separate)?.id ?? 'front');
  const who = $derived(whoMap(projection));
  const local = (t: number) => new Date(t - new Date(t).getTimezoneOffset() * 60_000).toISOString().slice(0, 16);
  let fromText = $state(local(Date.now() - 2 * 3_600_000));
  let untilText = $state(local(Date.now() - 3_600_000));
  let people = $state<FrontPresence[]>([]);
  let note = $state('');
  let picking = $state(false);
  let query = $state('');
  let error = $state('');

  type Rec = { at: number; set: FrontPresence[] };
  const recorded = $derived<Rec[]>(configured
    ? (history?.saved.switches ?? []).filter((s) => !s.retracted).map((s) => ({ at: s.occurred_at, set: s.resulting_front }))
    : (projection.fronts[sync.accountId]?.switches ?? []).filter((s) => !s.retracted)
      .map((s) => ({ at: s.occurred_at, set: s.resulting_front.map(({ level, ...e }) => ({ ...e, layer_id: level }) as FrontPresence) })));
  const stateAt = (t: number) => recorded.filter((r) => r.at <= t).sort((a, b) => b.at - a.at)[0]?.set ?? [];
  const candidates = $derived.by((): Who[] => {
    const all = [...ownMembers(projection), ...groups(projection).filter((g) => g.kind === 'subsystem').map((g) => who.get(whoKey('group', g.id))).filter((w): w is Who => !!w),
      ...[...who.values()].filter((w) => w.type === 'state')].filter((w) => !people.some((e) => e.subject_type === w.type && e.subject_id === w.id));
    return query.trim() ? all.map((w) => ({ w, s: fuzzy(query.trim(), w.name) })).filter((x) => x.s !== null).sort((a, b) => a.s! - b.s!).map((x) => x.w) : all;
  });

  function save() {
    const from = new Date(fromText).getTime();
    const until = new Date(untilText).getTime();
    error = !people.length ? 'Add who was there.' : !(from < until) ? 'From has to be before Until.' : until > Date.now() ? "Until can't be in the future."
      : configured && !history ? "Still loading this front's history. Try again in a moment." : '';
    if (error) return;
    const firstInner = people.findIndex((e) => e.layer_id === inner);
    const entries = people.map((e, i) => ({ ...e, is_primary: i === firstInner }));
    const between = recorded.some((r) => r.at > from && r.at <= until);
    const restore = stateAt(until);
    if (configured) {
      sync.create('front.change', sync.accountScope, front, { action: 'switch', entries, ...(note.trim() ? { note: note.trim() } : {}), effective_at: from, history_only: true });
      if (!between) sync.create('front.change', sync.accountScope, front, { action: 'switch', entries: restore, effective_at: until, history_only: true });
    } else {
      const legacy = (set: FrontPresence[]) => set.map(({ layer_id, ...e }) => ({ ...e, level: layer_id }));
      sync.create('front.switch', sync.accountScope, sync.newId(), { entries: legacy(entries), notify: 'silent', ...(note.trim() ? { note: note.trim() } : {}) }, { userTime: from });
      if (!between) sync.create('front.switch', sync.accountScope, sync.newId(), { entries: legacy(restore), notify: 'silent' }, { userTime: until });
    }
    onclose();
  }
</script>

<section class="page">
  <div class="bar">
    <button class="icon-btn" aria-label="Back" onclick={onclose}><Icon name="back" size={22} /></button>
    <div class="titles"><strong>Log a past switch</strong><span>{fronts.find((f) => f.id === front)?.name ?? 'Body'}</span></div>
    <button class="icon-btn accent" aria-label="Save to history" onclick={save}><Icon name="check" size={22} /></button>
  </div>
  <FormCard>
    <label class="row"><Icon name="target" size={20} /><span>Front</span>
      <select value={front} disabled={!configured} onchange={(e) => { front = e.currentTarget.value; people = []; }}>
        {#each fronts as f (f.id)}<option value={f.id}>{f.name}</option>{/each}
      </select></label>
    <label class="row"><Icon name="clock" size={20} /><span>From</span><input type="datetime-local" bind:value={fromText} max={local(Date.now())} /></label>
    <label class="row"><Icon name="clock" size={20} /><span>Until</span><input type="datetime-local" bind:value={untilText} max={local(Date.now())} /></label>
  </FormCard>
  <SectionLabel label="Who was there" />
  <FormCard>
    {#each people as e, i (whoKey(e.subject_type, e.subject_id))}
      {@const w = who.get(whoKey(e.subject_type, e.subject_id))}{@const l = scene.all.find((x) => x.id === e.layer_id)}
      <div class="person">
        <Face who={w} size={40} {dark} /><strong>{w?.name ?? 'Someone'}</strong>
        <select class="layer" style={l ? fd.inkVars(l.style, l.color) : ''} value={e.layer_id} aria-label="{w?.name ?? 'Someone'}'s layer"
          onchange={(ev) => (people = people.map((p, j) => (j === i ? { ...p, layer_id: ev.currentTarget.value } : p)))}>
          {#each scene.all as x (x.id)}<option value={x.id}>{x.name}</option>{/each}
        </select>
        <button class="icon-btn" aria-label="Remove {w?.name ?? 'someone'}" onclick={() => (people = people.filter((_, j) => j !== i))}><Icon name="close" size={18} /></button>
      </div>
    {/each}
    <button class="add" onclick={() => (picking = !picking)} aria-expanded={picking}><Icon name="plus" size={20} />Add member</button>
    {#if picking}
      <div class="picker">
        <!-- svelte-ignore a11y_autofocus -->
        <input bind:value={query} placeholder="Search" aria-label="Search members" autofocus />
        <div class="cands">{#each candidates.slice(0, 60) as w (whoKey(w.type, w.id))}
          <button onclick={() => { people = [...people, { subject_type: w.type, subject_id: w.id, layer_id: inner, is_primary: false }]; picking = false; query = ''; }}>
            <Face who={w} size={28} {dark} /><span>{w.name}</span>{#if w.type !== 'member'}<small>{w.type === 'group' ? 'subsystem' : 'state'}</small>{/if}
          </button>
        {/each}</div>
      </div>
    {/if}
  </FormCard>
  <SectionLabel label="Note" />
  <textarea bind:value={note} rows="2" placeholder="Quiet morning, woke up early." aria-label="Note"></textarea>
  {#if error}<p class="error" role="alert">{error}</p>{/if}
  <button class="fr-wide" onclick={save}>Save to history</button>
</section>

<style>
  .page { display: grid; gap: 10px; max-width: 720px; padding-bottom: 32px; }
  .bar { display: flex; align-items: center; gap: 4px; margin-left: -8px; }
  .icon-btn { width: 44px; height: 44px; display: grid; place-items: center; color: var(--ink); border: 0; background: none; border-radius: 50%; cursor: pointer; }
  .icon-btn.accent { color: var(--accent); }
  .titles { flex: 1; display: grid; padding-left: 4px; }
  /* the desktop app bar already names the page */
  @media (min-width: 1024px) { .titles { visibility: hidden; } }
  .titles strong { font-size: 17px; font-weight: 600; }
  .titles span { color: var(--ink-3); font-size: 12px; }
  .row { display: flex; align-items: center; gap: 12px; padding: 10px 16px; min-height: 52px; }
  .row :global(.icon) { color: var(--ink-2); }
  .row span { flex: 1; font-size: 15px; }
  .row select, .row input { font: inherit; color: var(--accent); font-weight: 500; background: none; border: 0; text-align: right; color-scheme: inherit; }
  .person { display: flex; align-items: center; gap: 12px; padding: 8px 4px 8px 12px; }
  .person strong { flex: 1; font-size: 16px; font-weight: 600; }
  .layer { font: inherit; font-size: 13px; font-weight: 500; color: var(--l-label, var(--ink)); background: var(--l-fill, transparent); border: 1px var(--l-dash, solid) var(--l-border, var(--line)); border-radius: 8px; padding: 6px 8px; }
  .add { display: flex; align-items: center; gap: 12px; padding: 14px 16px; border: 0; background: none; color: var(--accent); font: inherit; font-size: 15px; font-weight: 500; cursor: pointer; text-align: left; }
  .picker { display: grid; gap: 8px; padding: 0 12px 12px; }
  .picker input, textarea { font: inherit; color: var(--ink); background: var(--bg); border: 1px solid var(--line); border-radius: 10px; padding: 10px 12px; }
  textarea { background: var(--surface-2); border: 0; border-radius: 12px; padding: 14px; resize: vertical; }
  .cands { display: grid; max-height: 280px; overflow: auto; }
  .cands button { display: flex; align-items: center; gap: 12px; padding: 8px 4px; border: 0; background: none; color: var(--ink); font: inherit; cursor: pointer; text-align: left; border-radius: 10px; }
  .cands span { flex: 1; }
  .cands small { color: var(--ink-3); }
  .error { color: var(--danger); margin: 0; }
</style>
