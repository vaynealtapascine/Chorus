<script lang="ts">
  // Two devices switched at nearly the same time (Figma F3.6; Android FrontReviewPage): what each one recorded
  // and what really happened. The first open review of the front (`#/review/<front>`); an unconfigured
  // account's are the legacy ones (`#/review`). Nothing was dropped, so Later leaves it for another time.
  import { frontLayers } from '../front-view';
  import { deviceName, keepLabel, namedReviewMerge, together } from '../front-reviews';
  import { whoKey, whoMap } from '../fronting-subjects';
  import { router } from '../router.svelte';
  import { sync, type Projection } from '../sync/client';
  import Face from './Face.svelte';
  import FormCard from './kit/FormCard.svelte';
  import SectionLabel from './kit/SectionLabel.svelte';
  import Icon from './Icon.svelte';

  let { projection, dark, front }: { projection: Projection; dark: boolean; front?: string } = $props();
  type Side = { id: string; occurred_at: number; device_id: string | null; retracted: boolean; entries: { subject_type: string; subject_id: string; layer: string }[] };
  const system = $derived(projection.front_systems?.[sync.accountId]);
  const configured = $derived(!!system);
  const frontId = $derived(front ?? sync.accountId);
  const named = $derived.by(() => { void projection.front_systems; return system ? sync.frontHistory(frontId) : null; });
  const resolved = (id: string) => !!projection.rows.front_review?.[id]?.fields?.resolution;
  const open = $derived((configured ? named?.reviews ?? [] : projection.reviews?.[sync.accountId] ?? []).filter((r) => !resolved(r.id)));
  const r = $derived(open[0]);
  const byId = $derived(new Map<string, Side>(configured
    ? (named?.saved.switches ?? []).map((s) => [s.id, { id: s.id, occurred_at: s.occurred_at, device_id: s.device_id ?? null, retracted: !!s.retracted,
      entries: s.resulting_front.map((e) => ({ subject_type: e.subject_type, subject_id: e.subject_id, layer: e.layer_id })) }])
    : (projection.fronts[sync.accountId]?.switches ?? []).map((s) => [s.id, { id: s.id, occurred_at: s.occurred_at, device_id: s.device_id ?? null, retracted: !!s.retracted,
      entries: s.resulting_front.map((e) => ({ subject_type: e.subject_type, subject_id: e.subject_id, layer: e.level })) }])));
  const a = $derived(r ? byId.get(r.switch_a) : undefined);
  const b = $derived(r ? byId.get(r.switch_b) : undefined);
  const who = $derived(whoMap(projection));
  const layers = $derived(frontLayers(projection, sync.accountId));
  const devices = $derived(new Map(Object.entries(projection.rows.device ?? {}).map(([id, row]) => [id, String(row.fields.name ?? '')])));
  const device = (s?: Side) => deviceName(devices, s?.device_id, sync.device?.device_id);
  const name = (type: string, id: string) => who.get(whoKey(type, id))?.name ?? 'Someone';
  const label = (s?: Side) => s?.entries.map((e) => name(e.subject_type, e.subject_id)).join(' & ') || (s ? 'No one' : 'Someone');
  const layerOf = (s?: Side) => { const id = s?.entries[0]?.layer; return id ? layers.find((l) => l.id === id)?.name.toLowerCase() ?? null : null; };
  const time = (t: number) => new Date(t).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
  const story = (s?: Side) => [label(s), layerOf(s), s ? `from ${time(s.occurred_at)}` : null].filter(Boolean).join(' ');

  let pick = $state<'both' | 'a' | 'b'>('both');
  let error = $state('');
  $effect(() => { void r?.id; pick = 'both'; });
  const left = () => { if (history.length > 1) history.back(); else router.go('/fronting'); };
  // nothing (more) to check: back where this was opened from
  $effect(() => { if (!r && (!configured || named)) left(); });

  const resolve = (resolution: string) => sync.create('front.review_resolve', sync.accountScope, null, { review_id: r!.id, resolution });
  const retract = (id: string) => {
    if (configured) sync.create('front.change', sync.accountScope, frontId, { action: 'retract', target_op_id: id });
    else sync.create('front.retract', sync.accountScope, sync.newId(), { target_op_id: id });
  };
  function bothRight() {
    if (!a || !b || a.retracted || b.retracted) return resolve('keep_both');
    if (configured) {
      const changes = namedReviewMerge(named!.saved.switches, r!);
      if (!changes) return resolve('keep_both');
      for (const payload of changes) sync.create('front.change', sync.accountScope, frontId, payload);
      return resolve('merge');
    }
    const seen = new Set<string>();
    const entries = [...a.entries, ...b.entries].filter((e) => { const k = `${e.subject_type}:${e.subject_id}`; return seen.has(k) ? false : (seen.add(k), true); });
    // backdated and silent: a merge corrects the past, it doesn't switch anyone now
    sync.create('front.switch', sync.accountScope, sync.newId(), { entries: entries.map(({ layer, ...e }) => ({ ...e, level: layer })), notify: 'silent' },
      { userTime: Math.max(a.occurred_at, b.occurred_at) || undefined });
    resolve('merge');
  }
  function save() {
    if (!r) return;
    try {
      if (pick === 'both') bothRight();
      else { retract(pick === 'a' ? r.switch_b : r.switch_a); resolve(pick === 'a' ? 'keep_a' : 'keep_b'); }
      error = '';
    } catch (e) { error = e instanceof Error ? e.message : 'Couldn’t save. Try again.'; }
  }
</script>

{#snippet choice(id: 'both' | 'a' | 'b', title: string, about: string, enabled: boolean)}
  <button class="choice" role="radio" aria-checked={pick === id} disabled={!enabled} onclick={() => (pick = id)}>
    <span class="radio" class:on={pick === id}></span>
    <span class="text"><strong>{title}</strong><span>{about}</span></span>
  </button>
{/snippet}

<section class="fr-page">
  <header class="fr-bar">
    <button class="fr-icon" aria-label="Back" onclick={left}><Icon name="back" /></button>
    <div class="fr-titles"><strong>Review</strong><span>{open.length} {open.length === 1 ? 'thing' : 'things'} to check</span></div>
  </header>
  {#if r}
    <div class="body">
      <div class="alert"><span class="disc" aria-hidden="true">!</span><strong>Two devices switched within a minute</strong></div>
      <div class="sides">
        {#each [a, b] as s, i (i)}
          {@const first = s?.entries[0]}
          <div class="side">
            <span class="dev">{[device(s), s ? time(s.occurred_at) : null].filter(Boolean).join(' · ')}</span>
            <span class="said">
              {#if first}<Face who={who.get(whoKey(first.subject_type, first.subject_id))} size={36} {dark} />{/if}
              <span class="t"><strong>{label(s)}</strong>{#if layerOf(s)}<span>{layerOf(s)}</span>{/if}</span>
            </span>
          </div>
        {/each}
      </div>
      <SectionLabel label="What happened?" />
      <FormCard label="What happened?">
        {@render choice('both', 'Both are right', together(label(a), label(b)), true)}
        {@render choice('a', keepLabel(device(a)), story(a), !!a && !!b)}
        {@render choice('b', keepLabel(device(b)), story(b), !!a && !!b)}
      </FormCard>
      {#if error}<p class="error" role="alert">{error}</p>{/if}
    </div>
    <div class="foot">
      <button class="fr-wide neutral later" onclick={left}>Later</button>
      <button class="fr-wide save" onclick={save}>Save to history</button>
    </div>
  {/if}
</section>

<style>
  .body { display: grid; gap: 12px; padding: 16px; }
  .alert { display: flex; align-items: center; gap: 12px; padding: 12px; border-radius: 16px; background: var(--surface); }
  .alert strong { font-size: 15px; font-weight: 600; line-height: 20px; }
  .disc { width: 40px; height: 40px; flex: none; display: grid; place-items: center; border-radius: 12px; background: color-mix(in srgb, var(--warn) 16%, transparent); color: var(--warn); font-weight: 700; font-size: 20px; }
  .sides { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
  .side { display: grid; gap: 8px; padding: 12px; border-radius: 16px; background: var(--surface); min-width: 0; }
  .dev { color: var(--ink-3); font-size: 12px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .said { display: flex; align-items: center; gap: 10px; min-width: 0; }
  .t { display: grid; min-width: 0; }
  .t strong { font-size: 15px; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .t span { color: var(--ink-3); font-size: 12px; }
  .choice { display: flex; align-items: center; gap: 14px; width: 100%; min-height: 56px; padding: 10px 12px; border: 0; background: none; color: var(--ink); font: inherit; text-align: left; cursor: pointer; }
  .choice:disabled { cursor: default; }
  .choice:disabled strong { color: var(--ink-3); }
  .radio { width: 24px; height: 24px; flex: none; border-radius: 50%; border: 2px solid var(--ink-3); display: grid; place-items: center; }
  .radio.on { border-color: var(--accent); }
  .radio.on::after { content: ''; width: 12px; height: 12px; border-radius: 50%; background: var(--accent); }
  .text { flex: 1; min-width: 0; display: grid; }
  .text strong { font-size: 15px; font-weight: 600; }
  .text span { color: var(--ink-3); font-size: 13px; line-height: 18px; }
  .error { margin: 0; color: var(--danger); font-size: 14px; }
  .foot { display: flex; gap: 10px; padding: 0 16px 16px; }
  .later { flex: 3; }
  .save { flex: 7; }
</style>
