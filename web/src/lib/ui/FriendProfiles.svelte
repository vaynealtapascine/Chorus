<script lang="ts">
  // A friend's members and their profiles (D-101): only what that account shares with us, as the
  // server decides; fetched on demand, never synced or kept. Nothing about fronting is here. A
  // profile looks like our own members' (ProfileHeader), with the posts we may read (owner, 2026-11-06).
  import { core } from '../core';
  import { apiFetch } from '../http';
  import { roleColor } from '../member-list';
  import { apiBase } from '../sync/device';
  import { sync } from '../sync/client';
  import AvatarImage from './AvatarImage.svelte';
  import MemberBanner from './MemberBanner.svelte';
  import RichText from './RichText.svelte';
  import Icon from './Icon.svelte';
  import ProfileHeader from './ProfileHeader.svelte';
  import FriendPostCard from './FriendPostCard.svelte';
  import type { FriendPost } from '../friends-feed';
  import { members } from '../data';
  import { canWriteAs } from '../posts';
  import { bodyAuthors } from '../front-view';
  import type { Projection } from '../sync/client';

  let { accountId, memberId, dark, projection }: { accountId: string; memberId?: string; dark: boolean; projection: Projection } = $props();
  // who reacts and replies from here: as in the Journal, whoever fronts, else the first who may write
  const speaker = $derived(bodyAuthors(projection, sync.accountId)[0] ?? members(projection).find((m) => canWriteAs(projection, m.id, sync.accountId))?.id ?? null);
  const speakerName = $derived.by(() => { const m = members(projection).find((x) => x.id === speaker); return m?.display_name ?? m?.name ?? ''; });
  let tab = $state<'posts' | 'about'>('posts');
  // their posts and entries we may read, through the same audience check as everywhere (posts::readable_sql)
  let posts = $state<FriendPost[] | null>(null);

  interface Card { id: string; name: string; display_name: string | null; pronouns: string | null; color: string | null; sigil: string | null; avatar_blob: string | null;
    /** D-126: the handle we may see, and the handles they claim. `name` is the full name only when it's shared with us. */
    handle?: string | null; handles?: string[] }
  /** A row of their member list: the banner and role pills they share with us, if any. */
  interface ListCard extends Card { banner_blob?: string | null; roles?: string[] }
  interface Field { id: string; name: string; type: string; value: unknown }
  interface Full extends Card { description: string | null; description_entities: never[]; birthday: string | null; banner_blob: string | null; fields: Field[]; relationships?: {id: string; type: string; target: Card; note?: string}[]; post_alerts?: 'none' | 'requested' | 'active' }

  let owner = $state<{ name: string; avatar_blob: string | null } | null>(null);
  let list = $state<ListCard[] | null>(null);
  let profile = $state<Full | null>(null);
  let error = $state('');
  let missing = $state(false);

  // ask to hear when this member posts; their system says yes or no (D-106)
  let alertsBusy = $state(false);
  async function toggleAlerts() {
    if (!profile || !memberId) return;
    alertsBusy = true;
    try {
      const on = (profile.post_alerts ?? 'none') === 'none';
      const r = await apiFetch(`${apiBase()}/accounts/${encodeURIComponent(accountId)}/members/${encodeURIComponent(memberId)}/alerts`, {
        method: on ? 'PUT' : 'DELETE', headers: { authorization: `Bearer ${sync.device?.session ?? ''}` },
      });
      const j = await r.json().catch(() => ({}));
      if (!r.ok) throw new Error(j?.error?.message ?? `HTTP ${r.status}`);
      profile = { ...profile, post_alerts: j.post_alerts };
    } catch (e) {
      error = e instanceof Error ? e.message : String(e);
    } finally {
      alertsBusy = false;
    }
  }

  async function get(path: string) {
    const r = await apiFetch(`${apiBase()}${path}`, { headers: { authorization: `Bearer ${sync.device?.session ?? ''}` } });
    if (r.status === 404) return null;
    const j = await r.json().catch(() => ({}));
    if (!r.ok) throw new Error(j?.error?.message ?? `HTTP ${r.status}`);
    return j;
  }

  $effect(() => {
    const account = accountId;
    let cancelled = false;
    void get('/follows').then((j) => {
      const f = [...(j?.following ?? []), ...(j?.followers ?? [])].find((x: { account: { id: string } }) => x.account.id === account);
      if (!cancelled && f) owner = { name: f.account.display_name ?? (f.account.handle ? `@${f.account.handle}` : 'Friend'), avatar_blob: f.account.avatar_blob ?? null };
    }).catch(() => {});
    return () => { cancelled = true; };
  });

  $effect(() => {
    const account = accountId;
    const member = memberId;
    let cancelled = false;
    error = '';
    missing = false;
    const load = member
      ? get(`/accounts/${encodeURIComponent(account)}/members/${encodeURIComponent(member)}`).then((j) => { if (!cancelled) { profile = j; missing = !j; } })
      : get(`/accounts/${encodeURIComponent(account)}/members`).then((j) => { if (!cancelled) list = j?.items ?? []; });
    load.catch((e) => { if (!cancelled) error = e instanceof Error ? e.message : String(e); });
    return () => { cancelled = true; };
  });

  $effect(() => {
    const account = accountId;
    const member = memberId;
    posts = null;
    if (!member) return;
    let cancelled = false;
    void get(`/posts?account=${encodeURIComponent(account)}&author=${encodeURIComponent(member)}&limit=50`)
      .then((j) => { if (!cancelled) posts = ((j?.items ?? []) as FriendPost[]).filter((p) => !p.reply_to && !p.repost_of); })
      .catch(() => { if (!cancelled) posts = []; });
    return () => { cancelled = true; };
  });

  const shownName = (m: Card) => m.display_name ?? m.name;
  const tone = (c: string | null) => (c ? core.adaptColor(c, dark) : null);
  const glyph = (m: Card) => m.sigil ?? shownName(m)[0] ?? '?';
  function safeLink(v: unknown): string | null {
    if (typeof v !== 'string') return null;
    try { const u = new URL(v); return u.protocol === 'https:' || u.protocol === 'http:' ? u.href : null; } catch { return null; }
  }
  const birthday = (b: string) => (b.startsWith('--') ? new Date(`2000${b.slice(1)}T00:00`).toLocaleDateString(undefined, { day: 'numeric', month: 'long' }) : new Date(`${b}T00:00`).toLocaleDateString(undefined, { dateStyle: 'long' }));
</script>

<section class="friend">
  <a class="back" href={memberId ? `#/people/${encodeURIComponent(accountId)}` : '#/people'}>‹ {memberId ? (owner?.name ?? 'Members') : 'Friends'}</a>
  {#if error}<p class="error" role="alert">{error}</p>{/if}

  {#if memberId}
    {#if missing}
      <p class="muted">This profile isn't shared with you.</p>
    {:else if profile}
      <ProfileHeader banner={profile.banner_blob} avatar={profile.avatar_blob} glyph={glyph(profile)} name={shownName(profile)} color={profile.color}
        handle={profile.handle} also={(profile.handles ?? []).filter((h) => h !== profile?.handle)}
        subtitle={[profile.display_name && profile.name !== profile.display_name ? profile.name : null, profile.pronouns, owner?.name].filter(Boolean).join(' · ')} {dark}>
        {#snippet actions()}
          {#if profile?.post_alerts}
            {@const state = profile.post_alerts}
            <button class="alerts" class:on={state === 'active'} disabled={alertsBusy} onclick={toggleAlerts}
              aria-pressed={state !== 'none'} title={state === 'requested' ? `${owner?.name ?? 'They'} will see your request. Tap to cancel it.` : undefined}>
              <Icon name="bell" size={18} />
              {state === 'active' ? 'Post alerts on' : state === 'requested' ? 'Requested' : 'Get post alerts'}
            </button>
          {/if}
        {/snippet}
        {#if profile.description}<p class="bio"><RichText text={profile.description} entities={profile.description_entities ?? []} /></p>{/if}
      </ProfileHeader>
      <nav class="tabs" aria-label="Profile sections">
        <button class:on={tab === 'posts'} aria-pressed={tab === 'posts'} onclick={() => (tab = 'posts')}>Posts</button>
        <button class:on={tab === 'about'} aria-pressed={tab === 'about'} onclick={() => (tab = 'about')}>About</button>
      </nav>
      {#if tab === 'posts'}
        {#if posts === null}<p class="muted">Loading…</p>
        {:else}
          <div class="posts">
            {#each posts as post (post.id)}<FriendPostCard {post} account={owner?.name ?? ''} {projection} {speaker} {speakerName} {dark} />
            {:else}<p class="muted">No posts shared with you yet.</p>{/each}
          </div>
        {/if}
      {:else}
      {#if profile.birthday || profile.fields.length}
        <dl class="fields">
          {#if profile.birthday}<div><dt>Birthday</dt><dd>{birthday(profile.birthday)}</dd></div>{/if}
          {#each profile.fields as f (f.id)}
            <div>
              <dt>{f.name}</dt>
              <dd>
                {#if Array.isArray(f.value)}
                  <span class="pills">{#each f.value.filter((x) => typeof x === 'string') as r (r)}<span class="pill" style="--pill: {roleColor(r)}">{r}</span>{/each}</span>
                {:else if f.type === 'boolean'}{f.value ? 'Yes' : 'No'}
                {:else if f.type === 'url' && safeLink(f.value)}<a href={safeLink(f.value)} target="_blank" rel="noopener noreferrer nofollow">{String(f.value).replace(/^https?:\/\//, '')}</a>
                {:else if f.type === 'color' && typeof f.value === 'string'}<span class="swatch" style="background: {/^#[0-9a-f]{6}$/i.test(f.value) ? f.value : 'transparent'}"></span> {f.value}
                {:else if f.type === 'long_text'}<span class="long">{String(f.value)}</span>
                {:else}{String(f.value)}{/if}
              </dd>
            </div>
          {/each}
        </dl>
      {/if}
      {#if profile.relationships?.length}<section aria-label="Relationships"><h2>Relationships</h2>{#each profile.relationships as link (link.id)}<p>{link.type} · {shownName(link.target)}{#if link.target.handle} <span class="muted">@{link.target.handle}</span>{/if}{#if link.note}<br />{link.note}{/if}</p>{/each}</section>{/if}
      {/if}
      <p class="hint">You see what {owner?.name ?? 'they'} chose to share with you.</p>
    {:else if !error}
      <p class="muted">Loading…</p>
    {/if}
  {:else}
    <h1>{owner?.name ?? 'Friend'}</h1>
    {#if list === null && !error}
      <p class="muted">Loading…</p>
    {:else if list && !list.length}
      <p class="muted">{owner?.name ?? 'They'} haven't shared any member profiles with you yet.</p>
    {:else if list}
      <ul class="cards">
        {#each list as m (m.id)}
          {@const t = tone(m.color)}
          <li>
            <a href="#/people/{encodeURIComponent(accountId)}/{encodeURIComponent(m.id)}" class:has-banner={!!m.banner_blob} style="--ring: {t?.ring ?? 'var(--line)'}">
              {#if m.banner_blob}<MemberBanner hash={m.banner_blob} />{/if}
              <span class="avatar small"><AvatarImage hash={m.avatar_blob} glyph={glyph(m)} name={shownName(m)} /></span>
              <span class="who">
                <span class="name-line"><strong style="color: {t?.name ?? 'inherit'}">{shownName(m)}</strong>{#if m.pronouns}<span class="muted">{m.pronouns}</span>{/if}</span>
                {#if m.handle}<span class="muted handle">@{m.handle}</span>{/if}
                {#if m.roles?.length}<span class="pills">{#each m.roles as r (r)}<span class="pill small" style="--pill: {roleColor(r)}">{r}</span>{/each}</span>{/if}
              </span>
            </a>
          </li>
        {/each}
      </ul>
    {/if}
  {/if}
</section>

<style>
  .friend { max-width: 760px; margin: 0 auto; display: grid; gap: var(--s-4); }
  .back { color: var(--ink-2); text-decoration: none; font-size: var(--fs-sm); }
  h1 { margin: 0; font-size: var(--fs-2xl); }
  .avatar.small { display: inline-grid; place-items: center; width: 44px; height: 44px; border-radius: 50%; overflow: hidden; flex: none; background: var(--surface-2); box-shadow: 0 0 0 2px var(--ring); font-size: 20px; }
  .tabs { display: flex; gap: var(--s-2); border-bottom: 1px solid var(--line); }
  .tabs button { border: 0; background: none; color: var(--ink-2); padding: var(--s-2) var(--s-3); cursor: pointer; font: inherit; }
  .tabs button.on { border-bottom: 2px solid var(--accent); color: var(--ink); font-weight: 600; }
  .posts { display: grid; gap: var(--s-3); }
  .alerts { display: inline-flex; align-items: center; gap: 6px; flex: none; padding: 8px 14px; min-height: 40px; border-radius: var(--r-full); border: 1px solid var(--line);
    background: var(--surface); color: var(--ink); font: inherit; font-size: var(--fs-sm); cursor: pointer; }
  .alerts.on { color: var(--accent); border-color: color-mix(in srgb, var(--accent) 50%, transparent); }
  .alerts:disabled { opacity: 0.6; cursor: default; }
  .bio { margin: 0; color: var(--ink); white-space: pre-wrap; }
  .fields { display: grid; gap: var(--s-3); margin: 0; padding: var(--s-4); background: var(--surface); border: 1px solid var(--line); border-radius: var(--r-lg); }
  .fields div { display: grid; grid-template-columns: minmax(100px, 160px) 1fr; gap: var(--s-3); }
  dt { color: var(--ink-2); font-size: var(--fs-sm); } dd { margin: 0; overflow-wrap: anywhere; }
  .long { white-space: pre-wrap; }
  .pills { display: flex; flex-wrap: wrap; gap: 4px; }
  .pill { font-size: var(--fs-sm); font-weight: 600; padding: 1px 10px; border-radius: var(--r-full); background: color-mix(in srgb, var(--pill) 28%, var(--surface)); border: 1px solid color-mix(in srgb, var(--pill) 55%, transparent); }
  .swatch { display: inline-block; width: 14px; height: 14px; border-radius: 4px; vertical-align: -2px; border: 1px solid var(--line); }
  .cards { list-style: none; margin: 0; padding: 0; display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: var(--s-3); }
  .cards a { position: relative; isolation: isolate; display: flex; align-items: center; gap: var(--s-3); min-height: 76px; padding: var(--s-3) var(--s-4); background: var(--surface); border: 1px solid var(--line); border-radius: 14px; text-decoration: none; color: var(--ink); }
  .cards a :global(.banner) { border-radius: 0 13px 13px 0; z-index: -1; }
  .has-banner strong, .has-banner .muted { text-shadow: 0 0 6px var(--surface), 0 0 2px var(--surface); }
  .name-line { display: flex; align-items: baseline; gap: 8px; min-width: 0; }
  .handle { font-size: var(--fs-sm); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .pill.small { font-size: var(--fs-xs); line-height: 1.5; padding: 0 8px; white-space: nowrap; }
  .cards a:hover { border-color: var(--accent); }
  .who { display: grid; gap: 4px; min-width: 0; }
  .muted, .hint { color: var(--ink-3); margin: 0; }
  .hint { font-size: var(--fs-xs); }
  .error { color: var(--danger); margin: 0; }
  p { margin: 0; }
</style>
