<script lang="ts">
  // Friends (UI20, Figma F7, D-128): a system sees Friends · Requests · Sharing (F7.1, F7.7, F7.8),
  // a person Following · Followers · Requests (F7.4). A friend opens on their own page
  // (#/people/<account>); sharing with one friend, a bucket, Advanced sharing and notifications from
  // a friend are pages of their own (#/people/share|bucket|advanced|notify/…). Everything about other
  // accounts is the server's filtered view; accepting and buckets are ordinary ops.
  import '../design/friends.css';
  import { onMount } from 'svelte';
  import { BUCKET_SWATCHES } from '../bucket-controls';
  import { bucketAssignments, buckets, members, selfMember } from '../data';
  import { everyoneElsePreset, presetOf, startBucket } from '../follow-presets';
  import { api, friends, type Alert } from '../friends-state.svelte';
  import {
    accountColor, activity, around, connectedSince, follows, friendsSince, isSystem, mutual, nowLine, personName, presetName, sinceLine, waiting,
    type FollowRow, type FriendRow, type Person,
  } from '../friends-screens';
  import { bodyAuthors } from '../front-view';
  import { shortAgo } from '../journal-presentation';
  import { canWriteAs } from '../posts';
  import { openDm } from '../spaces';
  import { sync, type Projection } from '../sync/client';
  import AcceptSheet from './AcceptSheet.svelte';
  import FollowBackSheet from './FollowBackSheet.svelte';
  import BucketGlyph from './BucketGlyph.svelte';
  import FriendFace from './FriendFace.svelte';
  import Icon from './Icon.svelte';
  import EmptyState from './kit/EmptyState.svelte';
  import Sheet from './Sheet.svelte';

  let { projection, dark }: { projection: Projection; dark: boolean } = $props();
  const person = $derived(!!selfMember(projection));
  const tabs = $derived(person ? ['Following', 'Followers', 'Requests'] : ['Friends', 'Requests', 'Sharing']);
  const own = $derived(members(projection).filter((m) => canWriteAs(projection, m.id, sync.accountId)));
  const reader = $derived(selfMember(projection) ?? own.find((m) => m.id === bodyAuthors(projection, sync.accountId)[0]) ?? own[0]);
  const ownName = $derived((projection.rows.system?.[sync.accountId]?.fields.name as string | undefined)
    || ((h) => (h ? `@${h}` : ''))(projection.rows.account?.[sync.accountId]?.fields.handle as string | undefined) || (person ? 'you' : 'your system'));
  const ownBuckets = $derived(buckets(projection));
  const assignments = $derived(bucketAssignments(projection));

  onMount(() => {
    void friends.load();
    const t = setInterval(() => void friends.load(), 30_000);
    return () => clearInterval(t);
  });
  // reload when the synced follow rows change (a request arrived, another device accepted…)
  const followKey = $derived(JSON.stringify(projection.rows.follow ?? {}));
  $effect(() => {
    void followKey;
    const t = setTimeout(() => void friends.load(), 150);
    return () => clearTimeout(t);
  });

  let searching = $state(false);
  let query = $state('');
  let adding = $state(false);
  let handle = $state('');
  let accepting = $state<FollowRow | null>(null);
  let startOpen = $state(false);
  let notice = $state('');
  const now = $derived((void friends.rows, Date.now()));
  const shown = $derived(friends.rows.filter((r) => {
    const q = query.trim().toLowerCase();
    return !q || personName(r.account).toLowerCase().includes(q) || (r.account.handle ?? '').toLowerCase().includes(q);
  }));
  const asked = $derived(friends.alerts.filter((a) => a.status === 'requested'));
  const hearing = $derived(friends.alerts.filter((a) => a.status === 'active'));
  const ours = $derived(friends.following.filter((f) => f.status === 'requested'));
  const requestCount = $derived(friends.requests.length + asked.length);
  const memberName = (id: string) => { const m = members(projection).find((x) => x.id === id); return m?.display_name ?? m?.name ?? 'a member'; };
  const tab = $derived(Math.min(friends.tab, tabs.length - 1));

  async function act(failure: string, f: () => Promise<unknown> | unknown) {
    notice = '';
    try { await f(); await friends.load(); } catch (e) { notice = e instanceof Error ? e.message : failure; }
  }
  const message = (id: string) => act('Couldn’t open the chat.', async () => { location.hash = `#/chat/space:${await openDm(id)}`; });
  const open = (id: string) => (location.hash = `#/people/${encodeURIComponent(id)}`);
  function follow(e: SubmitEvent) {
    e.preventDefault();
    if (!handle.trim()) return;
    void act('Couldn’t send the request.', async () => { await api('/follows', { method: 'POST', body: JSON.stringify({ target: handle.trim() }) }); handle = ''; adding = false; });
  }
  const followBack = (id: string) => act('Couldn’t send the request.', () => api('/follows', { method: 'POST', body: JSON.stringify({ target: id }) }));
  const decline = (f: FollowRow) => act('Couldn’t decline.', () => sync.create('follow.end', sync.accountScope, f.id, {}));
  const cancel = (f: FollowRow) => act('Couldn’t cancel.', () => api(`/follows/${f.id}`, { method: 'DELETE' }));
  const decideAlert = (a: Alert, yes: boolean) => act('Couldn’t answer.', () => api(`/alerts/${encodeURIComponent(a.member_id)}/${encodeURIComponent(a.account.id)}`, { method: yes ? 'PUT' : 'DELETE' }));
  function accept(f: FollowRow) {
    if (person) void act('Couldn’t accept.', () => {
      sync.create('follow.accept', sync.accountScope, f.id, {});
      void friends.offerFollowBack(f.account.id, personName(f.account));
    });
    else accepting = f;
  }
  function chooseStart(id: string | null) {
    sync.create('pref.set', sync.accountScope, null, { device: '', key: 'follow.start_bucket', value: id ?? '' });
    startOpen = false;
  }
  const color = (p: Person) => accountColor(p.id, BUCKET_SWATCHES);
  const start = $derived(ownBuckets.find((b) => b.id === startBucket(projection)) ?? null);
</script>

<section class="fr-page" aria-busy={!friends.loaded}>
  <header class="fr-head">
    {#if reader}<FriendFace name={reader.display_name ?? reader.name} color={reader.color} glyph={reader.sigils[0]} blob={reader.avatar_blob} size={36} {dark} />{/if}
    <div class="fr-titles">
      <h1>Friends</h1>
      <span>{person ? `${reader?.display_name ?? reader?.name ?? ownName} · just me` : `${ownName} · reading as ${reader?.display_name ?? reader?.name ?? 'nobody'}`}</span>
    </div>
    <button class="fr-icon ink" aria-label="Follow someone" onclick={() => (adding = true)}><Icon name="user-plus" /></button>
    <button class="fr-icon ink" aria-label={searching ? 'Close search' : 'Search friends'} onclick={() => { searching = !searching; query = ''; }}><Icon name={searching ? 'close' : 'search'} /></button>
  </header>
  <div class="fr-tabs" role="tablist" aria-label="Friends">
    {#each tabs as label, i (label)}
      <button role="tab" aria-selected={tab === i} onclick={() => (friends.tab = i)}>{label}{#if label === 'Requests' && requestCount}<span class="fr-count">{requestCount}</span>{/if}</button>
    {/each}
  </div>
  {#if searching}<div class="search"><input bind:value={query} placeholder="Search friends" aria-label="Search friends" /></div>{/if}
  {#if friends.error}<p class="fr-error" role="alert">{friends.error}</p>{/if}
  {#if notice}<p class="fr-error" role="alert">{notice}</p>{/if}
  {#if !friends.loaded && !friends.error}<p class="fr-empty">Loading…</p>{/if}

  {#if tabs[tab] === 'Friends'}
    {@const both = shown.filter(mutual)}
    {@const near = both.filter((r) => around(r, now))}
    {@const rest = both.filter((r) => !around(r, now))}
    {@const out = shown.filter((r) => r.following && !mutual(r))}
    {@const back = shown.filter((r) => !r.following)}
    {#if friends.loaded && !shown.length}{@render empty()}{/if}
    {#if near.length}
      <h2 class="fr-section"><span>Around right now</span><span class="fr-aside">as each friend shares it</span></h2>
      <ul class="fr-list">{#each near as r (r.account.id)}<li>{@render row(r, true)}</li>{/each}</ul>
    {/if}
    {#if rest.length}
      <h2 class="fr-section"><span>Not sharing who’s around</span></h2>
      <ul class="fr-list">{#each rest as r (r.account.id)}<li>{@render row(r, false)}</li>{/each}</ul>
    {/if}
    {#if out.length}
      <h2 class="fr-section"><span>You follow</span><span class="fr-aside">not following you back</span></h2>
      <ul class="fr-list">{#each out as r (r.account.id)}<li>{@render row(r, around(r, now))}</li>{/each}</ul>
    {/if}
    {#if back.length}
      <h2 class="fr-section"><span>Follow you</span><span class="fr-aside">follow back to see what they share</span></h2>
      <ul class="fr-list">{#each back as r (r.account.id)}<li>{@render row(r, false)}</li>{/each}</ul>
    {/if}
    {#if both.length}<p class="fr-note"><Icon name="clock" size={16} />Times are as each friend shares them. Some round to the quarter hour or show only “this morning”.</p>{/if}
  {:else if tabs[tab] === 'Following'}
    {@const followed = shown.filter((r) => r.following)}
    {#if friends.loaded && !followed.length}{@render empty()}{/if}
    {@render group('Systems', followed.filter((r) => isSystem(r.account)))}
    {@render group('People', followed.filter((r) => !isSystem(r.account)))}
  {:else if tabs[tab] === 'Followers'}
    {@const list = shown.filter((r) => r.follower)}
    {#if friends.loaded && !list.length}<EmptyState icon="friends" label="No followers yet" />{/if}
    <ul class="fr-list">
      {#each list as r (r.account.id)}
        {@const theirs = ownBuckets.filter((b) => assignments.get(b.id)?.has(r.account.id))}
        <li class="follower"><button class="fr-row" onclick={() => open(r.account.id)}>
          <FriendFace name={personName(r.account)} color={color(r.account)} blob={r.account.avatar_blob} size={40} {dark} />
          <span class="fr-body">
            <span class="fr-name"><strong>{personName(r.account)}</strong><span class="fr-pill">{isSystem(r.account) ? 'System' : 'Person'}</span></span>
            {#if theirs.length}<span class="fr-faces">{#each theirs.slice(0, 3) as b (b.id)}<span class="fr-pill accent"><BucketGlyph icon={b.icon} color={b.color} size={13} />{b.name}</span>{/each}</span>
            {:else}<span class="fr-line quiet">In no bucket</span>{/if}
          </span>
          <span class="chev"><Icon name="chevron-right" size={18} /></span>
        </button>{#if !r.following}<button class="fr-btn back" onclick={() => void followBack(r.account.id)}><Icon name="user-plus" size={16} />Follow back</button>{/if}</li>
      {/each}
    </ul>
    <h2 class="fr-section"><span>Buckets</span><span class="fr-aside">who sees which posts</span></h2>
    {@render bucketRows()}
  {:else if tabs[tab] === 'Requests'}
    {#if friends.loaded && !friends.requests.length && !asked.length && !ours.length && !hearing.length}<EmptyState icon="user-plus" label="No requests" />{/if}
    {#if friends.requests.length}
      <h2 class="fr-section"><span>Want to follow {ownName}</span></h2>
      <ul class="fr-list">
        {#each friends.requests as f (f.id)}
          <li>{@render request(f.account, person ? 'Would like to follow you.' : 'Would like to know when you switch.', [
            [person ? 'Accept' : 'Accept…', true, () => accept(f)], ['Decline', false, () => decline(f)]])}</li>
        {/each}
      </ul>
    {/if}
    {#if asked.length}
      <h2 class="fr-section"><span>Want to hear about posts</span></h2>
      <ul class="fr-list">
        {#each asked as a (`${a.member_id}:${a.account.id}`)}
          <li>{@render request(a.account, person ? 'Would like to hear when you post.' : `Would like to hear when ${memberName(a.member_id)} posts.`, [
            ['Allow', true, () => decideAlert(a, true)], ['Decline', false, () => decideAlert(a, false)]])}</li>
        {/each}
      </ul>
    {/if}
    {#if ours.length}
      <h2 class="fr-section"><span>You asked to follow</span></h2>
      <ul class="fr-list">{#each ours as f (f.id)}<li>{@render request(f.account, 'Waiting for them to accept.', [['Cancel request', false, () => cancel(f)]])}</li>{/each}</ul>
    {/if}
    {#if hearing.length}
      <h2 class="fr-section"><span>Hear about our posts</span></h2>
      <ul class="fr-list">
        {#each hearing as a (`${a.member_id}:${a.account.id}`)}
          <li>{@render request(a.account, person ? 'Hears when you post.' : `Hears when ${memberName(a.member_id)} posts.`, [['Stop', false, () => decideAlert(a, false)]])}</li>
        {/each}
      </ul>
    {/if}
  {:else}
    <p class="fr-note"><Icon name="shield" size={16} />Friends in the same bucket hear the same things. A friend in two buckets gets the more open one.</p>
    <h2 class="fr-section"><span>Buckets</span><span class="fr-aside">who · how much</span></h2>
    {@render bucketRows()}
    <h2 class="fr-section"><span>Everyone else</span></h2>
    <button class="fr-link" onclick={() => (startOpen = true)}>
      <Icon name="user-plus" />
      <span class="fr-text"><strong>New friends start in</strong><span>{start ? `${start.name} · ${presetName(presetOf(start.ceiling))}` : `No bucket · ${presetName(everyoneElsePreset(projection))}`}</span></span>
      <span class="chev"><Icon name="chevron-right" size={18} /></span>
    </button>
    <a class="fr-link" href="#/people/advanced">
      <Icon name="sliders" />
      <span class="fr-text"><strong>Advanced sharing</strong><span>Everyone else, front history, stats, extra fronts</span></span>
      <span class="chev"><Icon name="chevron-right" size={18} /></span>
    </a>
  {/if}
</section>

{#snippet group(title: string, list: FriendRow[])}
  {#if list.length}
    <h2 class="fr-section"><span>{title}</span><span class="fr-aside">{list.length}</span></h2>
    <ul class="fr-list">{#each list as r (r.account.id)}<li>{@render row(r, around(r, now) || (!isSystem(r.account) && follows(r)))}</li>{/each}</ul>
  {/if}
{/snippet}

{#snippet empty()}
  <EmptyState icon="friends" label="No friends yet" action="Follow" onaction={() => (adding = true)} />
{/snippet}

{#snippet row(r: FriendRow, bright: boolean)}
  {@const v = r.view}
  {@const entries = v?.entries ?? []}
  <div class="fr-row split" class:dim={!bright}>
    <a class="main" href="#/people/{encodeURIComponent(r.account.id)}">
    <FriendFace name={personName(r.account)} color={color(r.account)} blob={r.account.avatar_blob} size={44} {dark} />
    <span class="fr-body">
      <span class="fr-name"><strong>{personName(r.account)}</strong><span class="fr-pill">{isSystem(r.account) ? 'System' : 'Person'}</span></span>
      {#if waiting(r)}<span class="fr-line quiet">Waiting for them to accept</span>
      {:else if !r.following}<span class="fr-line quiet">Follows you · you don’t follow them</span>
      {:else if isSystem(r.account) && entries.length}
        <span class="fr-faces">
          <span class="fr-stack">{#each entries.slice(0, 3) as e, i (`${e.name}:${i}`)}<FriendFace name={e.name} color={e.color ?? '#A09184'} glyph={e.glyph} blob={e.avatar_blob} size={22} {dark} />{/each}</span>
          <span class="fr-line ink">{nowLine(entries)}</span>
        </span>
      {:else if isSystem(r.account) && v?.shared === false}<span class="fr-line quiet">Posts and chat only</span>
      {:else if isSystem(r.account) && v?.since == null}<span class="fr-line quiet">Shares who’s around after their next switch</span>
      {:else if isSystem(r.account)}<span class="fr-line quiet">Nobody’s around right now</span>
      {:else if r.latest && bright}<span class="fr-line">{activity(r.latest, shortAgo(r.latest.occurred_at))}</span>
      {:else if r.latest}<span class="fr-line quiet">Last posted {shortAgo(r.latest.occurred_at)} ago</span>
      {:else}<span class="fr-line quiet">Nothing posted yet</span>{/if}
      {#if r.following && !waiting(r)}
        {#if isSystem(r.account) && entries.length}{#if sinceLine(v, now)}<span class="fr-small"><Icon name="clock" size={13} />{sinceLine(v, now)}</span>{/if}
        {:else if isSystem(r.account) && v?.shared === false}<span class="fr-small"><Icon name="eye-off" size={13} />They haven’t shared fronting with you</span>
        {:else if isSystem(r.account)}{#if friendsSince(connectedSince(r), now)}<span class="fr-small"><Icon name="clock" size={13} />{friendsSince(connectedSince(r), now)}</span>{/if}
        {:else if bright}<span class="fr-small"><Icon name="user" size={13} />One person, so nothing to front</span>{/if}
      {/if}
    </span>
    </a>
    {#if !r.following}<button class="fr-btn back" onclick={() => void followBack(r.account.id)}><Icon name="user-plus" size={16} />Follow back</button>{/if}
    {#if follows(r) || r.follower}
      <button class="fr-icon" aria-label="Message {personName(r.account)}" onclick={() => void message(r.account.id)}><Icon name="message" size={20} /></button>
    {/if}
  </div>
{/snippet}

{#snippet request(account: Person, line: string, buttons: [string, boolean, () => void][])}
  <div class="fr-row request">
    <FriendFace name={personName(account)} color={color(account)} blob={account.avatar_blob} size={44} {dark} />
    <span class="fr-body">
      <span class="fr-name"><strong>{personName(account)}</strong>{#if account.kind}<span class="fr-pill">{isSystem(account) ? 'System' : 'Person'}</span>{/if}</span>
      <span class="fr-line">{line}</span>
      <span class="fr-actions">{#each buttons as [label, primary, run] (label)}<button class="fr-btn" class:primary onclick={run}>{label}</button>{/each}</span>
    </span>
  </div>
{/snippet}

{#snippet bucketRows()}
  <ul class="fr-list">
    {#each ownBuckets as b (b.id)}
      {@const inside = friends.activeFollowers.filter((f) => assignments.get(b.id)?.has(f.account.id))}
      <li><a class="fr-row bucket" href="#/people/bucket/{encodeURIComponent(b.id)}">
        <span class="fr-tile tile" style:--fr-tile={b.color ? `color-mix(in srgb, ${b.color} 22%, transparent)` : null}><BucketGlyph icon={b.icon} color={b.color} size={22} /></span>
        <span class="fr-body">
          <strong class="bucket-name">{b.name}</strong>
          {#if inside.length}
            <span class="fr-faces"><span class="fr-stack">{#each inside.slice(0, 3) as f (f.id)}<FriendFace name={personName(f.account)} color={color(f.account)} blob={f.account.avatar_blob} size={22} {dark} />{/each}</span>
              <span class="fr-line">{inside.map((f) => personName(f.account)).join(', ')}</span></span>
          {:else}<span class="fr-line quiet">Nobody yet</span>{/if}
        </span>
        {#if !person}{@const p = presetOf(b.ceiling)}<span class="fr-pill preset">{p === 'inherit' ? 'Everyone else’s' : presetName(p)}</span>{/if}
        <span class="chev"><Icon name="chevron-right" size={18} /></span>
      </a></li>
    {/each}
  </ul>
  <a class="fr-new" href="#/people/bucket/new"><Icon name="plus" size={18} />New bucket</a>
{/snippet}

{#if adding}
  <Sheet title="Follow someone" subtitle="They’ll get a request and choose what you see" onclose={() => (adding = false)}>
    <form class="follow" onsubmit={follow}>
      <label class="fr-field"><span>THEIR HANDLE</span><input bind:value={handle} placeholder="@handle" autocomplete="off" aria-label="Their handle" /></label>
      <p class="fr-note accent"><Icon name="shield" size={16} />{person ? 'Once they accept, you see what they share with followers.' : 'Once they accept, you see what they share with followers. Following them doesn’t share anything of yours.'}</p>
      <button class="fr-wide" disabled={!handle.trim()}>Send request</button>
    </form>
  </Sheet>
{/if}
{#if friends.followBack}<FollowBackSheet />{/if}
{#if accepting}
  {@const f = accepting}
  <AcceptSheet {projection} followId={f.id} accountId={f.account.id} name={personName(f.account)}
    onclose={() => { accepting = null; setTimeout(() => void friends.load(), 300); }}
    ondecline={() => { accepting = null; void decline(f); }} />
{/if}
{#if startOpen}
  <Sheet title="New friends start in" subtitle="Where accepting a request starts; you can still pick another" onclose={() => (startOpen = false)}>
    <div class="options">
      <button class:on={!start} onclick={() => chooseStart(null)}><span class="text"><strong>No bucket</strong><span>Everyone else · {presetName(everyoneElsePreset(projection))}</span></span>{#if !start}<Icon name="check" size={20} />{/if}</button>
      {#each ownBuckets as b (b.id)}
        <button class:on={start?.id === b.id} onclick={() => chooseStart(b.id)}>
          <BucketGlyph icon={b.icon} color={b.color} size={20} />
          <span class="text"><strong>{b.name}</strong><span>{presetName(presetOf(b.ceiling))}</span></span>{#if start?.id === b.id}<Icon name="check" size={20} />{/if}
        </button>
      {/each}
    </div>
  </Sheet>
{/if}

<style>
  .search { padding: 8px 16px; background: var(--surface); }
  .search input { width: 100%; box-sizing: border-box; border: 0; border-radius: 12px; background: color-mix(in srgb, var(--ink) 5%, transparent); color: var(--ink); font: inherit; padding: 10px 12px; }
  .chev { color: var(--ink-3); align-self: center; display: grid; }
  .split { cursor: default; padding: 0 8px 0 0; }
  .split .main { flex: 1; min-width: 0; display: flex; align-items: flex-start; gap: 14px; padding: 12px 0 12px 16px; color: inherit; text-decoration: none; }
  .split.dim .main :global(.fr-face) { opacity: .55; }
  .split > .fr-icon { margin-top: 10px; }
  /* Follow back goes under the text, so the name keeps its width on a phone */
  .split:has(> .back) { flex-wrap: wrap; }
  .split:has(> .back) .main { flex-basis: calc(100% - 60px); }
  .split > .back { order: 3; margin: -4px 0 12px 74px; }
  .follower { display: flex; flex-wrap: wrap; }
  .follower > .fr-row { flex: 1 1 100%; min-width: 0; }
  .follower > .back { margin: -4px 0 12px 70px; }
  .request { cursor: default; }
  .request:hover { background: none; }
  .bucket { align-items: center; text-decoration: none; }
  .bucket-name { font-size: 16px; font-weight: 600; }
  .tile { width: 44px; height: 44px; border-radius: 12px; }
  .preset { background: var(--surface-2); color: var(--ink); font-weight: 500; padding: 4px 10px; border-radius: 12px; }
  .follow { display: grid; gap: 12px; }
  .follow .fr-note { margin: 0; }
  .options { display: grid; gap: 2px; }
  .options button { display: flex; align-items: center; gap: 14px; padding: 10px 12px; border: 0; border-radius: 12px; background: none; color: var(--ink); font: inherit; text-align: left; cursor: pointer; }
  .options button.on { background: var(--accent-soft); }
  .options .text { flex: 1; display: grid; }
  .options .text span { color: var(--ink-3); font-size: 13px; }
  .options button :global(.icon) { color: var(--accent); }
</style>
