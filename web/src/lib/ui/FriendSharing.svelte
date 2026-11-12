<script lang="ts">
  // F7.9: what one follower sees of us right now (their own view, GET /follows/{id}/view), how much
  // they hear (the follow override), their buckets, what else we share with them, who they hear
  // about (each member's announce list) and the rest just for them. Every change saves as it's made.
  import '../design/friends.css';
  import { onMount } from 'svelte';
  import { bucketAssignments, buckets, members, selfMember } from '../data';
  import { accountDefault, everyoneElsePreset, presetCeiling, presetOf } from '../follow-presets';
  import { api, friends } from '../friends-state.svelte';
  import {
    announcedTo, friendsSince, hearsAbout, nowLine, personName, PRESET_IDS, presetAbout, presetName, sinceLine, type FollowerView,
  } from '../friends-screens';
  import { shortAgo } from '../journal-presentation';
  import { sync, type Projection } from '../sync/client';
  import BucketGlyph from './BucketGlyph.svelte';
  import CeilingDetails from './CeilingDetails.svelte';
  import FriendFace from './FriendFace.svelte';
  import Icon from './Icon.svelte';
  import Sheet from './Sheet.svelte';

  let { followId, projection, dark }: { followId: string; projection: Projection; dark: boolean } = $props();
  onMount(() => { if (!friends.loaded) void friends.load(); });
  const f = $derived(friends.followers.find((x) => x.id === followId && x.status === 'active'));
  const name = $derived(f ? personName(f.account) : 'Follower');
  const person = $derived(!!selfMember(projection));
  const ownName = $derived((projection.rows.system?.[sync.accountId]?.fields.name as string | undefined) || 'you');
  const override = $derived((projection.rows.follow?.[followId]?.fields.ceiling ?? {}) as Record<string, unknown>);
  const theirs = $derived(f ? buckets(projection).filter((b) => bucketAssignments(projection).get(b.id)?.has(f.account.id)) : []);
  const now = Date.now();
  let seen = $state<FollowerView | null | undefined>(undefined);
  $effect(() => {
    void JSON.stringify(override); void theirs.length;
    const id = followId;
    void api(`/follows/${encodeURIComponent(id)}/view`).then((v) => (seen = v), () => (seen = null));
  });
  const setCeiling = (ceiling: Record<string, unknown>) => sync.create('follow.set_ceiling', sync.accountScope, followId, { ceiling });
  const assign = (bucket: string, on: boolean) => f && sync.create(on ? 'bucket.assign' : 'bucket.unassign', sync.accountScope, bucket, { follower_account_id: f.account.id });
  function inheritedBool(key: string, fallback: boolean): boolean {
    const d = accountDefault(projection);
    const base = Object.hasOwn(d, key) ? d[key] === true : fallback;
    return theirs.length ? theirs.some((b) => (Object.hasOwn(b.ceiling, key) ? b.ceiling[key] === true : base)) : base;
  }
  const effective = (key: string, fallback: boolean) => (Object.hasOwn(override, key) ? override[key] === true : inheritedBool(key, fallback));
  const choice = $derived(presetOf(override));
  const bucketsSay = $derived(theirs[0] ? `${theirs[0].name}: ${presetName(((c) => (c === 'inherit' ? everyoneElsePreset(projection) : c))(presetOf(theirs[0].ceiling)))}` : `Everyone else: ${presetName(everyoneElsePreset(projection))}`);
  const hearing = $derived(members(projection).filter((m) => !m.archived && !m.is_self).map((m) => ({ m, on: announcedTo(projection.rows.member?.[m.id]?.fields.notify_policy, new Set(theirs.map((b) => b.id))) })));
  const beneath = $derived({ ...accountDefault(projection), ...(theirs[0]?.ceiling ?? {}) });
  let adding = $state(false);
  let hears = $state(false);
  let justFor = $state(false);
  let removing = $state(false);
  function setAnnounce(member: string, value: unknown) {
    const raw = projection.rows.member?.[member]?.fields.notify_policy;
    const policy = (typeof raw === 'string' ? JSON.parse(raw) : raw ?? {}) as Record<string, unknown>;
    sync.create('member.set', sync.accountScope, member, { notify_policy: { ...policy, announce: value } });
  }
</script>

<section class="fr-page">
  <header class="fr-bar">
    <a class="fr-icon ink" href={f ? `#/people/${encodeURIComponent(f.account.id)}` : '#/people'} aria-label="Back"><Icon name="back" /></a>
    <div class="fr-titles"><strong>Sharing with {name}</strong><span>Follows {ownName}{f && friendsSince(f.created_at, now) ? ` · ${friendsSince(f.created_at, now)?.replace('friends ', '')}` : ''}</span></div>
    <a class="fr-icon accent" href={f ? `#/people/${encodeURIComponent(f.account.id)}` : '#/people'} aria-label="Done"><Icon name="check" /></a>
  </header>
  {#if !f}<p class="fr-empty">{friends.loaded ? 'They don’t follow you any more.' : 'Loading…'}</p>
  {:else}
    {#if !person}
      <div class="fr-card accent seen">
        <strong class="seen-title"><Icon name="eye" size={18} />What {name} see right now</strong>
        {#if seen === undefined}<p class="fr-line">Loading…</p>
        {:else if !seen || seen.shared === false}<p class="fr-line ink">Nothing: you don’t share who’s here with them.</p>
        {:else if !seen.entries.length}<p class="fr-line ink">Nobody yet. They see your next switch as you share it.</p>
        {:else}
          <p class="who"><span class="fr-stack">{#each seen.entries.slice(0, 3) as e, i (`${e.name}:${i}`)}<FriendFace name={e.name} color={e.color ?? '#A09184'} glyph={e.glyph} blob={e.avatar_blob} size={32} {dark} />{/each}</span>
            <span class="fr-line ink">{nowLine(seen.entries)}</span></p>
          <p class="fr-small">{[sinceLine(seen, now), seen.revealed_at ? `told them ${shortAgo(seen.revealed_at)} ago` : null].filter(Boolean).join(' · ')}</p>
        {/if}
      </div>
      <h2 class="fr-section"><span>How much they hear</span></h2>
      <div class="fr-seg" role="group" aria-label="How much they hear">
        {#each PRESET_IDS as p (p)}<button aria-pressed={choice === p} onclick={() => setCeiling(presetCeiling(p, override))}>{presetName(p)}</button>{/each}
      </div>
      <p class="fr-about">{choice === 'inherit' ? `As their bucket says (${bucketsSay}). Pick one to set it just for them.` : choice === 'custom' ? `Set detail by detail under Just for ${name}.` : presetAbout(choice)}</p>
    {/if}
    <h2 class="fr-section"><span>Buckets</span></h2>
    <div class="fr-chips">
      {#each theirs as b (b.id)}<a class="fr-chip on" href="#/people/bucket/{encodeURIComponent(b.id)}"><BucketGlyph icon={b.icon} color={b.color} size={15} />{b.name}</a>{/each}
      {#if buckets(projection).some((b) => !theirs.includes(b))}<button class="fr-chip" onclick={() => (adding = true)}><Icon name="plus" size={14} />Add</button>{/if}
      {#if !buckets(projection).length}<span class="fr-line quiet">No buckets yet. Make one under Sharing.</span>{/if}
    </div>
    {#if !person}
      <h2 class="fr-section"><span>Also share</span></h2>
      {@render toggle('Who’s here now', 'On their Friends tab and our profile', effective('share_current_front', true), (on) => setCeiling({ ...override, share_current_front: on }))}
      {@render toggle('Front history', 'Last 30 days, times as shared', effective('share_history', false), (on) => setCeiling({ ...override, share_history: on }))}
      {@render toggle('Fronting stats', 'Each member’s share, whole days only', effective('share_stats', false), (on) => setCeiling({ ...override, share_stats: on }))}
      <button class="fr-link" onclick={() => (hears = true)}><span class="fr-text"><strong>Who they hear about</strong><span>{hearsAbout(hearing.map(({ m, on }) => [m.display_name ?? m.name, on]))}</span></span><span class="chev"><Icon name="chevron-right" size={18} /></span></button>
      <button class="fr-link" onclick={() => (justFor = true)}><Icon name="sliders" /><span class="fr-text"><strong>Just for {name}</strong><span>Delay, time shown, quiet hours, extra fronts</span></span><span class="chev"><Icon name="chevron-right" size={18} /></span></button>
    {/if}
    <button class="fr-danger" onclick={() => (removing = true)}>Remove follower</button>
  {/if}
</section>

{#snippet toggle(title: string, sub: string, on: boolean, set: (on: boolean) => void)}
  <button class="fr-toggle" role="switch" aria-checked={on} onclick={() => set(!on)}><span class="fr-text"><strong>{title}</strong><span>{sub}</span></span><span class="fr-switch"></span></button>
{/snippet}

{#if adding && f}
  <Sheet title="Add to a bucket" subtitle="They hear the most open of their buckets" onclose={() => (adding = false)}>
    <div class="menu">{#each buckets(projection).filter((b) => !theirs.includes(b)) as b (b.id)}
      <button onclick={() => { assign(b.id, true); adding = false; }}><BucketGlyph icon={b.icon} color={b.color} size={20} />{b.name}{#if !person}<small>{presetName(presetOf(b.ceiling))}</small>{/if}</button>
    {/each}</div>
  </Sheet>
{/if}
{#if hears}
  <Sheet title="Who {name} hear about" onclose={() => (hears = false)}>
    {#each hearing as { m, on } (m.id)}
      {@const raw = projection.rows.member?.[m.id]?.fields.notify_policy}
      {@const announce = ((typeof raw === 'string' ? JSON.parse(raw) : raw) as { announce?: unknown } | null)?.announce}
      <div class="member">
        <FriendFace name={m.display_name ?? m.name} color={m.color} glyph={m.sigils[0]} blob={m.avatar_blob} size={36} {dark} />
        <span class="fr-text"><strong>{m.display_name ?? m.name}</strong><span>{on ? `They hear when ${m.display_name ?? m.name} fronts` : 'Kept from them'}</span></span>
        <select aria-label="Who hears when {m.display_name ?? m.name} fronts" value={announce === 'nobody' ? 'nobody' : announce && typeof announce === 'object' ? 'buckets' : 'everyone'}
          onchange={(e) => setAnnounce(m.id, e.currentTarget.value === 'buckets' ? { buckets: theirs.map((b) => b.id) } : e.currentTarget.value)}>
          <option value="everyone">Every follower</option>
          {#if theirs.length || (announce && typeof announce === 'object')}<option value="buckets">Some buckets</option>{/if}
          <option value="nobody">Nobody</option>
        </select>
      </div>
    {/each}
  </Sheet>
{/if}
{#if justFor && f}
  <Sheet title="Just for {name}" onclose={() => (justFor = false)}>
    <div class="flush"><CeilingDetails {projection} ceiling={override} inherited={theirs.length ? 'their buckets' : 'Everyone else'} {beneath} onchange={setCeiling} /></div>
    {#if Object.keys(override).length}
      <button class="fr-wide neutral" onclick={() => setCeiling(Object.fromEntries(Object.entries(override).filter(([k]) => k === 'fronts' || k === 'layers')))}>Use their buckets for everything</button>
    {/if}
  </Sheet>
{/if}
{#if removing && f}
  <Sheet title="Remove {name} as a follower?" onclose={() => (removing = false)}>
    <p class="fr-line">They stop hearing your switches and seeing what you share with followers.</p>
    {#snippet footer()}
      <button class="fr-wide neutral" onclick={() => (removing = false)}>Keep</button>
      <button class="fr-wide remove" onclick={() => { sync.create('follow.end', sync.accountScope, followId, {}); removing = false; location.hash = '#/people'; setTimeout(() => void friends.load(), 300); }}>Remove</button>
    {/snippet}
  </Sheet>
{/if}

<style>
  .seen { margin-top: var(--s-3); }
  .seen-title { display: flex; align-items: center; gap: var(--s-2); color: var(--accent); font-size: 14px; }
  .seen p { margin: 0; }
  .who { display: flex; align-items: center; gap: 10px; }
  .chev { color: var(--ink-3); display: grid; }
  a.fr-chip { text-decoration: none; }
  .menu { display: grid; gap: 2px; }
  .menu button { display: flex; align-items: center; gap: 14px; padding: var(--s-3); border: 0; border-radius: var(--r-row); background: none; color: var(--ink); font: inherit; font-size: var(--fs-base); text-align: left; cursor: pointer; }
  .menu button:hover { background: var(--surface-2); }
  .menu small { margin-left: auto; color: var(--ink-3); font-size: var(--fs-sm); }
  .member { display: flex; align-items: center; gap: var(--s-3); padding: 6px var(--s-1); }
  .member .fr-text { flex: 1; display: grid; min-width: 0; }
  .member .fr-text span { color: var(--ink-3); font-size: var(--fs-sm); }
  .member select { font: inherit; font-size: var(--fs-sm); color: var(--ink); background: var(--surface-2); border: 0; border-radius: var(--r-sm); padding: 6px var(--s-2); }
  .flush { margin: 0 -16px; }
  .remove { background: var(--danger); }
</style>
