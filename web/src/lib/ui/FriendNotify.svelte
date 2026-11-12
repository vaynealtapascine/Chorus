<script lang="ts">
  // F7.12: what we hear from one friend, our own prefs (NOTIFICATIONS §4) that can only make it
  // quieter than they allow: their switches (who, which layers, switch-outs), how they arrive, quiet
  // hours, a mute, and post alerts per member (D-106). Each change saves as it's made.
  import SectionLabel from './kit/SectionLabel.svelte';
  import ValueRow from './kit/ValueRow.svelte';
  import Switch from './kit/Switch.svelte';
  import Chip from './kit/Chip.svelte';
  import '../design/friends.css';
  import { onMount } from 'svelte';
  import { api, friends } from '../friends-state.svelte';
  import {
    CLOCK, DELIVERIES, delivery, digestTime, forSaving, isSystem, mutedUntil, personName, prefEnabled, prefLevels, quiet, quietHold,
    subjects, switchOuts, withLevel, withQuiet, withSubject, type Prefs,
  } from '../friends-screens';
  import Icon from './Icon.svelte';
  import Sheet from './Sheet.svelte';

  let { followId }: { followId: string } = $props();
  onMount(() => { if (!friends.loaded) void friends.load(); });
  const f = $derived(friends.following.find((x) => x.id === followId && x.status === 'active'));
  const name = $derived(f ? personName(f.account) : 'Friend');
  let prefs = $state<Prefs>({});
  let loadedFor = '';
  $effect(() => { if (f && loadedFor !== f.id) { prefs = { ...(f.prefs ?? {}) }; loadedFor = f.id; } });
  interface Card { id: string; name: string; display_name: string | null }
  let members = $state<Card[]>([]);
  let alerts = $state<Record<string, string>>({});
  let error = $state('');
  $effect(() => {
    if (!f) return;
    const account = f.account.id;
    if (!isSystem(f.account)) { void api(`/accounts/${encodeURIComponent(account)}/alerts`).then((j) => (alerts = { person: j.post_alerts }), () => {}); return; }
    void api(`/accounts/${encodeURIComponent(account)}/members`).then(async (j) => {
      members = j.items ?? [];
      const next: Record<string, string> = {};
      await Promise.all(members.map(async (m) => {
        try { next[m.id] = (await api(`/accounts/${encodeURIComponent(account)}/members/${encodeURIComponent(m.id)}`)).post_alerts ?? 'none'; } catch { /* not shared */ }
      }));
      alerts = next;
    }, () => {});
  });
  const shown = (m: Card) => m.display_name || m.name || 'Someone';
  async function save(next: Prefs) {
    const before = prefs;
    prefs = next;
    try { await api(`/follows/${followId}/prefs`, { method: 'PUT', body: JSON.stringify(forSaving(next)) }); error = ''; void friends.load(); }
    catch (e) { prefs = before; error = e instanceof Error ? e.message : 'Couldn’t save that.'; }
  }
  async function setAlert(key: string, on: boolean) {
    if (!f) return;
    const path = key === 'person' ? `/accounts/${encodeURIComponent(f.account.id)}/alerts` : `/accounts/${encodeURIComponent(f.account.id)}/members/${encodeURIComponent(key)}/alerts`;
    try { alerts = { ...alerts, [key]: (await api(path, { method: on ? 'PUT' : 'DELETE' })).post_alerts }; error = ''; }
    catch (e) { error = e instanceof Error ? e.message : 'Couldn’t change post alerts.'; }
  }
  const now = Date.now();
  let quietOpen = $state(false);
  let muteOpen = $state(false);
  let digestOpen = $state(false);
  let qOn = $state(false); let qFrom = $state('23:00'); let qTo = $state('08:00'); let qHold = $state(true);
  let digestAt = $state('20:00');
  function openQuiet() { const q = quiet(prefs); qOn = !!q; qFrom = q?.[0] ?? '23:00'; qTo = q?.[1] ?? '08:00'; qHold = quietHold(prefs); quietOpen = true; }
  const HOUR = 3_600_000;
  const on = $derived(prefEnabled(prefs));
</script>

<section class="fr-page">
  <header class="fr-bar">
    <a class="fr-icon ink" href={f ? `#/people/${encodeURIComponent(f.account.id)}` : '#/people'} aria-label="Back"><Icon name="back" /></a>
    <div class="fr-titles"><strong>Notifications from {name}</strong></div>
    <a class="fr-icon accent" href={f ? `#/people/${encodeURIComponent(f.account.id)}` : '#/people'} aria-label="Done"><Icon name="check" /></a>
  </header>
  {#if !f}<p class="fr-empty">{friends.loaded ? 'You don’t follow them any more.' : 'Loading…'}</p>
  {:else}
    {#if error}<p class="fr-error" role="alert">{error}</p>{/if}
    {#if isSystem(f.account)}
      <SectionLabel inset label="Switches" />
      <Switch checked={on} label="Tell me when they switch" sub="As often and as exactly as {name} allow" onchange={(next) => save({ ...prefs, enabled: next })} />
      {#if members.length}
        <p class="fr-label">About</p>
        <div class="fr-chips">
          {#each members as m (m.id)}
            {@const picked = subjects(prefs)}
            {@const chosen = !picked || picked.has(m.id)}
            <Chip aria-pressed={chosen} disabled={!on} onclick={() => save(withSubject(prefs, m.id, members.map((x) => x.id)))}>{#if chosen}<Icon name="check" size={14} />{/if}{shown(m)}</Chip>
          {/each}
        </div>
      {/if}
      <p class="fr-label">Layers</p>
      <div class="fr-chips">
        {#each [['front', 'Front'], ['cocon', 'Co-conscious'], ['present', 'Present']] as [id, label] (id)}
          {@const chosen = prefLevels(prefs).has(id)}
          <Chip aria-pressed={chosen} disabled={!on} onclick={() => save(withLevel(prefs, id))}>{#if chosen}<Icon name="check" size={14} />{/if}{label}</Chip>
        {/each}
      </div>
      <Switch checked={switchOuts(prefs)} disabled={!on} label="Switch-outs" sub="When someone stops fronting" onchange={(next) => save({ ...prefs, switch_outs: next })} />
      <SectionLabel inset label="How they arrive" />
      <div class="fr-seg" role="group" aria-label="How they arrive">
        {#each DELIVERIES as d (d)}
          <button aria-pressed={delivery(prefs) === d} onclick={() => {
            if (d === 'digest_daily' && delivery(prefs) === d) { digestAt = digestTime(prefs); digestOpen = true; } else void save({ ...prefs, delivery: d });
          }}>{d === 'each' ? 'Each one' : d === 'digest_hourly' ? 'Hourly' : `Daily at ${digestTime(prefs)}`}</button>
        {/each}
      </div>
      {#if delivery(prefs) === 'digest_daily'}<p class="fr-about">Choose it again to change the time.</p>{/if}
    {/if}
    <ValueRow label="Quiet hours" sub={quiet(prefs) ? `${quiet(prefs)![0]}–${quiet(prefs)![1]} · ${quietHold(prefs) ? 'hold until they end' : 'skip them'}` : 'Off'} onclick={openQuiet} />
    <ValueRow label="Mute for a while" sub={mutedUntil(prefs, now) ? `Until ${new Date(mutedUntil(prefs, now)!).toLocaleString([], { dateStyle: 'short', timeStyle: 'short' })}` : 'Off'} onclick={() => (muteOpen = true)} />
    <SectionLabel inset label="New posts" />
    {#if !isSystem(f.account)}
      {#if alerts.person}{@render alertRow(`${name}’s posts`, 'person', alerts.person)}{/if}
    {:else if !members.length}<p class="fr-line quiet pad">{name} haven’t shared any member profiles with you.</p>
    {:else}
      {#each members as m (m.id)}{#if alerts[m.id]}{@render alertRow(`${shown(m)}’s posts`, m.id, alerts[m.id])}{/if}{/each}
    {/if}
  {/if}
</section>

{#snippet alertRow(title: string, key: string, state: string)}
  {#if state === 'requested'}
    <button class="fr-link" onclick={() => setAlert(key, false)}><span class="fr-text"><strong>{title}</strong><span>Asked · waiting for an answer</span></span><span class="fr-value">Asked</span></button>
  {:else}
    <Switch checked={state === 'active'} label={title} sub={state === 'active' ? 'They said yes' : 'Ask to hear when they post'} onchange={(next) => setAlert(key, next)} />
  {/if}
{/snippet}

{#if quietOpen}
  <Sheet title="Quiet hours" onclose={() => (quietOpen = false)}>
    <Switch bind:checked={qOn} label="Quiet hours" />
    {#if qOn}
      <div class="times">
        <label class="fr-field"><span>FROM</span><input type="time" bind:value={qFrom} /></label>
        <label class="fr-field"><span>UNTIL</span><input type="time" bind:value={qTo} /></label>
      </div>
      <div class="fr-seg flush" role="group" aria-label="During quiet hours">
        <button aria-pressed={qHold} onclick={() => (qHold = true)}>Hold until morning</button>
        <button aria-pressed={!qHold} onclick={() => (qHold = false)}>Skip them</button>
      </div>
    {/if}
    {#snippet footer()}
      <button class="fr-wide" disabled={qOn && !(CLOCK.test(qFrom) && CLOCK.test(qTo))} onclick={() => { void save(withQuiet(prefs, qOn ? [qFrom, qTo] : null, qHold)); quietOpen = false; }}>Save</button>
    {/snippet}
  </Sheet>
{/if}
{#if muteOpen}
  <Sheet title="Mute {name}" subtitle="Their switches wait until then; nothing is lost" onclose={() => (muteOpen = false)}>
    <div class="menu">
      {#each [['Off', 0], ['For an hour', HOUR], ['For 8 hours', 8 * HOUR], ['For a day', 24 * HOUR], ['For a week', 7 * 24 * HOUR]] as [label, span] (label)}
        <button onclick={() => { void save({ ...prefs, mute_until: span ? Date.now() + (span as number) : null }); muteOpen = false; }}>{label}{#if !span && !mutedUntil(prefs, now)}<Icon name="check" size={18} />{/if}</button>
      {/each}
    </div>
  </Sheet>
{/if}
{#if digestOpen}
  <Sheet title="Daily summary at" subtitle="Your time" onclose={() => (digestOpen = false)}>
    <label class="fr-field"><span>TIME</span><input type="time" bind:value={digestAt} /></label>
    {#snippet footer()}
      <button class="fr-wide" disabled={!CLOCK.test(digestAt)} onclick={() => { void save({ ...prefs, digest_time: digestAt }); digestOpen = false; }}>Save</button>
    {/snippet}
  </Sheet>
{/if}

<style>
  .pad { padding: 0 var(--s-4); }
  .times { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
  .flush { margin: 0; }
  .menu { display: grid; gap: 2px; }
  .menu button { display: flex; align-items: center; justify-content: space-between; padding: var(--s-3); border: 0; border-radius: var(--r-row); background: none; color: var(--ink); font: inherit; font-size: var(--fs-base); text-align: left; cursor: pointer; }
  .menu button:hover { background: var(--surface-2); }
  .menu :global(.icon) { color: var(--accent); }
</style>
