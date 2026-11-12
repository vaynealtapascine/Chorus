<script lang="ts">
  // One friend (UI20): a system (F7.2, and F7.5 as a person sees it) or a person (F7.3). Kind and
  // our bucket pills, Message / Notifying / Chats (a person: Tell me about posts), who's around now
  // as they share it, the members they share, and what's between us. A person shows their posts,
  // journal and about instead of fronting. Only what the server's filtered reads allow.
  import '../design/friends.css';
  import { onMount } from 'svelte';
  import { BUCKET_SWATCHES } from '../bucket-controls';
  import { bucketAssignments, buckets, members, selfMember } from '../data';
  import { everyoneElsePreset, presetOf } from '../follow-presets';
  import { api, friends } from '../friends-state.svelte';
  import {
    accountColor, connectedSince, follows, friendsSince, isSystem, levelWord, mutedUntil, personName, prefEnabled, prefsSummary,
    presetName, sinceLine, timeNote, waiting,
  } from '../friends-screens';
  import type { FriendPost } from '../friends-feed';
  import { bodyAuthors } from '../front-view';
  import { canWriteAs } from '../posts';
  import { listSpaces, openDm, type SpaceInfo } from '../spaces';
  import { sync, type Projection } from '../sync/client';
  import BucketGlyph from './BucketGlyph.svelte';
  import FriendFace from './FriendFace.svelte';
  import FriendPostCard from './FriendPostCard.svelte';
  import Icon from './Icon.svelte';
  import EmptyState from './kit/EmptyState.svelte';
  import Sheet from './Sheet.svelte';

  let { accountId, projection, dark }: { accountId: string; projection: Projection; dark: boolean } = $props();
  const viewerPerson = $derived(!!selfMember(projection));
  const r = $derived(friends.row(accountId));
  const name = $derived(r ? personName(r.account) : 'Friend');
  const now = $derived((void friends.rows, Date.now()));
  interface Card { id: string; name: string; display_name: string | null; pronouns: string | null; color: string | null; sigil: string | null; avatar_blob: string | null; roles?: string[]; description?: string | null }
  let shared = $state<Card[] | null>(null);
  let spaces = $state<SpaceInfo[]>([]);
  let about = $state<(Card & { birthday?: string | null; fields?: { id: string; name: string; type: string; value: unknown }[] }) | null>(null);
  let posts = $state<FriendPost[] | null>(null);
  let personAlerts = $state<string | null>(null);
  let tab = $state<'posts' | 'journal' | 'about'>('posts');
  let menu = $state(false);
  let chats = $state(false);
  let confirm = $state<'unfollow' | 'remove' | null>(null);
  let notice = $state('');
  const own = $derived(members(projection).filter((m) => canWriteAs(projection, m.id, sync.accountId)));
  const speaker = $derived(bodyAuthors(projection, sync.accountId)[0] ?? own[0]?.id ?? null);
  const speakerName = $derived.by(() => { const m = own.find((x) => x.id === speaker); return m?.display_name ?? m?.name ?? ''; });
  const theirBuckets = $derived(buckets(projection).filter((b) => bucketAssignments(projection).get(b.id)?.has(accountId)));
  const shownMember = (m: Card) => m.display_name || m.name || 'Someone';

  onMount(() => { if (!friends.loaded) void friends.load(); });
  $effect(() => {
    const id = accountId;
    shared = null; posts = null; about = null; personAlerts = null;
    void api(`/accounts/${encodeURIComponent(id)}/members`).then((j) => (shared = j.items ?? []), () => (shared = []));
    void listSpaces().then((all) => (spaces = all.filter((s) => s.kind === 'shared' && s.accounts.some((a) => a.id === id))), () => {});
  });
  // a person: their posts, their own member's about, post alerts by account
  $effect(() => {
    if (!r || isSystem(r.account) || (!follows(r) && r.follower?.status !== 'active')) return;
    const id = accountId;
    void api(`/posts?account=${encodeURIComponent(id)}&limit=50`).then((j) => (posts = (j.items as FriendPost[]).filter((p) => !p.reply_to && !(p as { repost_of?: string | null }).repost_of)), () => (posts = []));
    void api(`/accounts/${encodeURIComponent(id)}/alerts`).then((j) => (personAlerts = j.post_alerts), () => {});
  });
  $effect(() => {
    const self = r && !isSystem(r.account) ? shared?.[0] : null;
    if (!self) return;
    void api(`/accounts/${encodeURIComponent(accountId)}/members/${encodeURIComponent(self.id)}`).then((j) => (about = j), () => {});
  });

  async function act(failure: string, f: () => Promise<unknown> | unknown) {
    notice = '';
    try { await f(); await friends.load(); } catch (e) { notice = e instanceof Error ? e.message : failure; }
  }
  const message = () => act('Couldn’t open the chat.', async () => { location.hash = `#/chat/space:${await openDm(accountId)}`; });
  const followBack = () => act('Couldn’t send the request.', () => api('/follows', { method: 'POST', body: JSON.stringify({ target: accountId }) }));
  const togglePersonAlerts = () => act('Couldn’t change post alerts.', async () => {
    const j = await api(`/accounts/${encodeURIComponent(accountId)}/alerts`, { method: (personAlerts ?? 'none') === 'none' ? 'PUT' : 'DELETE' });
    personAlerts = j.post_alerts;
  });
  function share() {
    const ceiling = r?.follower ? (projection.rows.follow?.[r.follower.id]?.fields.ceiling ?? {}) as Record<string, unknown> : {};
    const choice = presetOf(ceiling);
    const fromBucket = theirBuckets.map((b) => presetOf(b.ceiling)).find((c) => c !== 'inherit');
    const how = choice === 'inherit' ? presetName(fromBucket ?? everyoneElsePreset(projection)) : presetName(choice);
    return [theirBuckets.map((b) => b.name).join(', '), viewerPerson ? '' : how].filter(Boolean).join(' · ') || 'In no bucket';
  }
  const spaceName = (s: SpaceInfo) => s.name || s.accounts.map((a) => a.display_name ?? `@${a.handle}`).join(' + ');
  const color = $derived(r ? accountColor(r.account.id, BUCKET_SWATCHES) : '#A09184');
  const self = $derived(r && !isSystem(r.account) ? shared?.[0] ?? null : null);
</script>

<section class="fr-page">
  <header class="fr-bar">
    <a class="fr-icon ink" href="#/people" aria-label="Back to Friends"><Icon name="back" /></a>
    <div class="fr-titles">
      <strong>{name}</strong>
      {#if r}<span>{isSystem(r.account) ? 'System' : 'Person'} · {!isSystem(r.account) && r.account.handle ? `@${r.account.handle}` : friendsSince(connectedSince(r), now) ?? (follows(r) ? 'you follow them' : waiting(r) ? 'request sent' : 'follows you')}</span>{/if}
    </div>
    {#if r}<button class="fr-icon ink" aria-label="More" aria-haspopup="dialog" onclick={() => (menu = true)}><Icon name="more-vert" /></button>{/if}
  </header>
  {#if !r}
    <p class="fr-empty">{friends.loaded ? 'You’re no longer connected to this account.' : 'Loading…'}</p>
  {:else}
    <div class="hero">
      <FriendFace {name} {color} blob={r.account.avatar_blob} size={88} {dark} />
      <div class="hero-text">
        <h1>{name}</h1>
        {#if r.account.handle || shared?.length || self?.pronouns}
          <span class="meta">{[r.account.handle ? `@${r.account.handle}` : null, isSystem(r.account) ? (shared?.length ? `${shared.length} ${shared.length === 1 ? 'member' : 'members'} visible` : null) : self?.pronouns].filter(Boolean).join(' · ')}</span>
        {/if}
        <span class="pills"><span class="fr-pill">{isSystem(r.account) ? 'System' : 'Person'}</span>
          {#each theirBuckets as b (b.id)}<span class="fr-pill accent"><BucketGlyph icon={b.icon} color={b.color} size={13} />{b.name}</span>{/each}</span>
      </div>
    </div>
    {#if about?.description}<p class="bio">{about.description}</p>{/if}
    <div class="fr-actions buttons">
      <button class="fr-btn primary" disabled={!follows(r) && r.follower?.status !== 'active'} onclick={message}><Icon name="message" size={16} />Message</button>
      {#if isSystem(r.account) && follows(r) && r.following}
        {@const on = prefEnabled(r.following.prefs ?? {}) && mutedUntil(r.following.prefs ?? {}, now) == null}
        <a class="fr-btn" class:soft={on} href="#/people/notify/{encodeURIComponent(r.following.id)}"><Icon name={on ? 'bell' : 'bell-off'} size={16} />{on ? 'Notifying' : 'Notifications off'}</a>
      {:else if follows(r) && personAlerts}
        <button class="fr-btn" class:soft={personAlerts !== 'none'} onclick={togglePersonAlerts}><Icon name="bell" size={16} />{personAlerts === 'active' ? 'Told about posts' : personAlerts === 'requested' ? 'Asked about posts' : 'Tell me about posts'}</button>
      {/if}
      {#if isSystem(r.account) && spaces.length}<button class="fr-btn" onclick={() => (chats = true)}><Icon name="users" size={16} />Chats</button>{/if}
      {#if !r.following}<button class="fr-btn" onclick={followBack}><Icon name="user-plus" size={16} />Follow back</button>{/if}
    </div>
    {#if notice}<p class="fr-error" role="alert">{notice}</p>{/if}
    {#if !r.following && r.follower?.status === 'active'}<p class="fr-note"><Icon name="user-plus" size={16} />{name} follows you. Once you follow back and they accept, you’ll see what they share with followers, and you’re friends.</p>{/if}

    {#if !isSystem(r.account)}
      <p class="fr-note"><Icon name="user" size={16} />{name} is one person, so there’s no fronting to show. You see their posts, journal and messages.</p>
      {@render between(r)}
      <div class="fr-tabs on-page" role="tablist" aria-label="{name}’s posts">
        {#each [['posts', 'Posts'], ['journal', 'Journal'], ['about', 'About']] as [id, label] (id)}
          <button role="tab" aria-selected={tab === id} onclick={() => (tab = id as typeof tab)}>{label}</button>
        {/each}
      </div>
      {#if tab === 'about'}
        <div class="about">
          {#if !about}<EmptyState icon="eye-off" label="Not shared with you" />
          {:else}
            {#if about.birthday}<div class="field"><span>Birthday</span>{about.birthday}</div>{/if}
            {#each about.fields ?? [] as f (f.id)}<div class="field"><span>{f.name}</span>{Array.isArray(f.value) ? f.value.join(', ') : typeof f.value === 'boolean' ? (f.value ? 'Yes' : 'No') : String(f.value ?? '')}</div>{/each}
            <p class="fr-small">You see what {name} chose to share with you.</p>
          {/if}
        </div>
      {:else}
        {@const list = posts?.filter((p) => (tab === 'journal') === (p.kind === 'entry'))}
        {#if !follows(r)}<p class="fr-empty">Follow {name} to see what they share with followers.</p>
        {:else if !list}<p class="fr-empty">Loading…</p>
        {:else if !list.length}<EmptyState icon={tab === 'journal' ? 'book' : 'journal'} label="Nothing yet" />
        {:else}<div class="posts">{#each list as post (post.id)}<FriendPostCard {post} account={name} {projection} {speaker} {speakerName} {dark} />{/each}</div>{/if}
      {/if}
    {:else}
      {#if follows(r)}
        {@const v = r.view}
        <div class="fr-card around">
          <div class="card-head"><strong>Around now</strong>{#if v?.entries?.length && sinceLine(v, now)}<span>{sinceLine(v, now)}</span>{/if}</div>
          {#if !v}<p class="fr-line quiet">Couldn’t load who’s around.</p>
          {:else if v.shared === false}<p class="fr-line"><Icon name="eye-off" size={16} /> {name} haven’t shared who’s around with you.</p>
          {:else if !v.entries.length}<p class="fr-line">{v.since == null ? `${name} share who’s around after their next switch.` : 'Nobody’s around right now.'}</p>
          {:else}
            <div class="faces">
              {#each v.entries as e, i (`${e.name}:${i}`)}
                <span class="face"><FriendFace name={e.name} color={e.color ?? '#A09184'} glyph={e.glyph} blob={e.avatar_blob} size={64} {dark} ring={e.level === 'front' ? 'solid' : 'dashed'} />
                  <span>{e.name}</span>{#if e.level !== 'front'}<small>{levelWord(e.level)}</small>{/if}</span>
              {/each}
            </div>
          {/if}
          {#each v?.fronts ?? [] as f (f.id)}<p class="fr-line">{f.name ?? 'Another front'}: {f.entries.map((e) => e.name).join(' & ') || 'nobody right now'}</p>{/each}
          {#if timeNote(name, v?.time)}<p class="fr-small"><Icon name="clock" size={14} />{timeNote(name, v?.time)}</p>{/if}
        </div>
        {#if v?.stats?.members?.some((m) => m.share_pct > 0)}<p class="fr-small stats">Most often, last {v.stats.days} days: {v.stats.members.filter((m) => m.share_pct > 0).map((m) => `${m.name} ${m.share_pct}%`).join(' · ')}</p>{/if}
        {#if v?.history && v.history.length > 1}
          <details class="history"><summary>Earlier fronts ({v.history.length - 1})</summary>
            {#each v.history.slice(1) as h, i (i)}<p class="fr-line quiet">{h.entries.map((e) => e.name).join(' & ') || 'Nobody shared'}</p>{/each}
          </details>
        {/if}
      {:else if waiting(r)}<p class="fr-note"><Icon name="clock" size={16} />Once they accept, you’ll see who’s around as they share it.</p>{/if}
      {#if shared?.length}
        <h2 class="fr-section"><span>Members you can see</span><span class="fr-aside">{shared.length}</span></h2>
        {#if viewerPerson}
          <ul class="fr-list">
            {#each shared as m (m.id)}
              <li><a class="fr-link" href="#/people/{encodeURIComponent(accountId)}/{encodeURIComponent(m.id)}">
                <FriendFace name={shownMember(m)} color={m.color ?? '#A09184'} glyph={m.sigil} blob={m.avatar_blob} size={40} {dark} />
                <span class="fr-text"><strong class="strong">{shownMember(m)}</strong>{#if m.pronouns || m.roles?.length}<span>{[m.pronouns, m.roles?.[0]?.toLowerCase()].filter(Boolean).join(' · ')}</span>{/if}</span>
                <span class="chev"><Icon name="chevron-right" size={18} /></span>
              </a></li>
            {/each}
          </ul>
        {:else}
          <div class="members">
            {#each shared as m (m.id)}
              <a class="member" href="#/people/{encodeURIComponent(accountId)}/{encodeURIComponent(m.id)}">
                <FriendFace name={shownMember(m)} color={m.color ?? '#A09184'} glyph={m.sigil} blob={m.avatar_blob} size={52} {dark} />
                <strong>{shownMember(m)}</strong>{#if m.pronouns}<small>{m.pronouns}</small>{/if}
              </a>
            {/each}
          </div>
        {/if}
      {:else if shared && follows(r)}<p class="fr-note"><Icon name="members" size={16} />{name} haven’t shared any member profiles with you yet.</p>{/if}
      {@render between(r)}
      {#if viewerPerson}<p class="fr-note"><Icon name="shield" size={16} />{name} choose what they share and when. You can make their notifications quieter, never louder.</p>{/if}
    {/if}
  {/if}
</section>

{#snippet between(r: NonNullable<typeof friends.rows[number]>)}
      {#if (r.follower?.status === 'active') || follows(r) || spaces.length}
    <h2 class="fr-section"><span>Between you</span></h2>
    {#if r.follower?.status === 'active'}
      <a class="fr-link" href="#/people/share/{encodeURIComponent(r.follower.id)}"><Icon name="shield" /><span class="fr-text"><strong>What you share with them</strong><span>{share()}</span></span><span class="chev"><Icon name="chevron-right" size={18} /></span></a>
    {/if}
    {#if follows(r) && r.following}
      <a class="fr-link" href="#/people/notify/{encodeURIComponent(r.following.id)}"><Icon name="bell" /><span class="fr-text"><strong>Notifications from them</strong><span>{prefsSummary(r.following.prefs ?? {}, new Map((shared ?? []).map((m) => [m.id, shownMember(m)])), now)}</span></span><span class="chev"><Icon name="chevron-right" size={18} /></span></a>
    {/if}
    {#if spaces.length}
      <button class="fr-link" onclick={() => (chats = true)}><Icon name="users" /><span class="fr-text"><strong>Shared spaces</strong><span>{spaces.map(spaceName).join(' · ')}</span></span><span class="chev"><Icon name="chevron-right" size={18} /></span></button>
    {/if}
  {/if}
{/snippet}

{#if menu && r}
  <Sheet title={name} subtitle={r.account.handle ? `@${r.account.handle}` : null} onclose={() => (menu = false)}>
    <div class="menu">
      {#if r.account.handle}<button onclick={() => { void navigator.clipboard?.writeText(`@${r.account.handle}`); menu = false; }}><Icon name="copy" />Copy @{r.account.handle}</button>{/if}
      {#if !r.following}<button onclick={() => { menu = false; void followBack(); }}><Icon name="user-plus" />Follow back</button>{/if}
      {#if r.following}<button class="danger" onclick={() => { menu = false; confirm = 'unfollow'; }}><Icon name="close" />{waiting(r) ? 'Cancel request' : 'Unfollow'}</button>{/if}
      {#if r.follower?.status === 'active'}<button class="danger" onclick={() => { menu = false; confirm = 'remove'; }}><Icon name="delete" />Remove follower</button>{/if}
    </div>
  </Sheet>
{/if}
{#if confirm && r}
  <Sheet title={confirm === 'remove' ? `Remove ${name} as a follower?` : waiting(r) ? 'Cancel your request?' : `Unfollow ${name}?`} onclose={() => (confirm = null)}>
    <p class="fr-line">{confirm === 'remove' ? 'They stop hearing your switches and seeing what you share with followers.' : waiting(r) ? '' : 'You stop hearing their switches and seeing what they share with followers. They aren’t told.'}</p>
    {#snippet footer()}
      <button class="fr-wide neutral" onclick={() => (confirm = null)}>Keep</button>
      <button class="fr-wide danger-fill" onclick={() => {
        const which = confirm; confirm = null;
        if (which === 'remove') void act('Couldn’t remove them.', () => sync.create('follow.end', sync.accountScope, r.follower!.id, {}));
        else void act('Couldn’t unfollow.', () => api(`/follows/${r.following!.id}`, { method: 'DELETE' }));
      }}>{confirm === 'remove' ? 'Remove' : waiting(r) ? 'Cancel request' : 'Unfollow'}</button>
    {/snippet}
  </Sheet>
{/if}
{#if chats}
  <Sheet title="Shared spaces" subtitle="Spaces you and {name} are both in" onclose={() => (chats = false)}>
    <div class="menu">{#each spaces as s (s.id)}<a href="#/chat/space:{s.id}"><Icon name="users" />{spaceName(s)}<small>{s.accounts.length} accounts</small></a>{/each}</div>
  </Sheet>
{/if}

<style>
  .hero { display: flex; align-items: center; gap: 16px; padding: 18px 16px 0; }
  .hero-text { flex: 1; min-width: 0; display: grid; gap: 4px; justify-items: start; }
  h1 { margin: 0; font-size: 24px; line-height: 30px; font-weight: 600; }
  .meta { color: var(--ink-3); font-size: 14px; }
  .pills { display: flex; flex-wrap: wrap; gap: 6px; }
  .bio { margin: 12px 16px 0; font-size: 15px; line-height: 21px; }
  .buttons { padding: 14px 16px; }
  .around { margin-top: 2px; }
  .card-head { display: flex; align-items: center; }
  .card-head strong { flex: 1; font-size: 16px; font-weight: 600; }
  .card-head span { color: var(--ink-3); font-size: 13px; }
  .faces { display: flex; gap: 14px; overflow-x: auto; }
  .face { display: grid; justify-items: center; gap: 6px; width: 72px; text-align: center; font-size: 13px; }
  .face small { color: var(--ink-3); font-size: 11px; margin-top: -4px; }
  .fr-card p { margin: 0; display: flex; align-items: center; gap: 6px; }
  .stats { padding: 8px 16px 0; }
  .history { margin: 8px 16px 0; color: var(--accent); font-size: 13px; }
  .history summary { cursor: pointer; padding: 6px 0; }
  .history p { margin: 2px 0; }
  .members { display: flex; gap: 14px; overflow-x: auto; padding: 0 16px; }
  .member { display: grid; justify-items: center; gap: 4px; width: 64px; color: var(--ink); text-decoration: none; text-align: center; }
  .member strong { font-size: 13px; font-weight: 500; max-width: 64px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .member small { color: var(--ink-3); font-size: 11px; }
  .strong { font-weight: 600 !important; }
  .chev { color: var(--ink-3); display: grid; }
  .about { display: grid; gap: 10px; padding: 16px; }
  .field { display: grid; gap: 2px; padding: 12px 14px; border-radius: 14px; background: var(--surface); }
  .field span { color: var(--ink-2); font-size: 12px; }
  .posts { display: grid; }
  .menu { display: grid; gap: 2px; }
  .menu button, .menu a { display: flex; align-items: center; gap: 14px; padding: 12px; border: 0; border-radius: 12px; background: none; color: var(--ink); font: inherit; font-size: 16px; text-align: left; text-decoration: none; cursor: pointer; }
  .menu button:hover, .menu a:hover { background: var(--surface-2); }
  .menu :global(.icon) { color: var(--ink-2); }
  .menu small { margin-left: auto; color: var(--ink-3); font-size: 13px; }
  .menu .danger, .menu .danger :global(.icon) { color: var(--danger); }
  .danger-fill { background: var(--danger); }
</style>
