<script lang="ts">
  // Chorus Home (D-130, Figma F0.1-F0.8; Android HomeScreen.kt): who is fronting with Switch and Log a
  // past one, Quick switch, chats waiting, friends right now, Today and new posts from friends; a
  // person gets a composer row, friends, chats and posts, and a first day of Welcome and Try. Each
  // reader chooses and orders the sections (`home.sections`, shared with Android, F0.3).
  import { channels, latestMessage, members, selfMember, spaces, unread, type ChannelRow, type SpaceRow } from '../data';
  import { chatPrefs, chatTime, discColor, groupsInto, previewOf, privateVisible, stillArchived } from '../chat-list';
  import { chatMembers } from '../subsystems';
  import { bodyAuthors, bodyPresence, frontLayers, frontView } from '../front-view';
  import { doSwitch, doFrontSwitch, type Level } from '../front.svelte';
  import * as fd from '../fronting-display';
  import { ownMembers, whoKey, whoMap, type Who } from '../fronting-subjects';
  import * as H from '../home-dashboard';
  import { homeUi } from '../home-ui.svelte';
  import { friends as friendsState, api } from '../friends-state.svelte';
  import { activity, nowLine, personName, sinceLine, type FriendRow } from '../friends-screens';
  import { friendsFeed, type FriendPosts } from '../friends-feed';
  import { shortAgo } from '../journal-presentation';
  import { posts as ownPosts } from '../posts';
  import { memberHandles } from '../handles';
  import { listSpaces, spaceTitle, type SpaceInfo } from '../spaces';
  import { sync, type Projection } from '../sync/client';
  import AvatarImage from './AvatarImage.svelte';
  import Face from './Face.svelte';
  import Icon from './Icon.svelte';
  import Sheet from './Sheet.svelte';
  import LogPastSwitch from './LogPastSwitch.svelte';
  import PlaceSheet from './PlaceSheet.svelte';
  import HomeSections from './HomeSections.svelte';
  import SectionLabel from './kit/SectionLabel.svelte';
  import { core } from '../core';

  let { projection, dark, onswitch }: { projection: Projection; dark: boolean; onswitch: (front?: string, addTo?: string) => void } = $props();
  const accountId = $derived(sync.accountId);
  const self = $derived(selfMember(projection));
  const person = $derived(!!self);
  const who = $derived(whoMap(projection));
  const own = $derived(ownMembers(projection));
  const reader = $derived(person ? self?.id : bodyAuthors(projection, accountId)[0] ?? own[0]?.id);
  const readerKey = $derived(person ? null : reader ?? null);
  const stored = $derived(H.storedSections(projection.rows.pref, accountId));
  const sections = $derived(H.forReader(stored.byReader, stored.accountWide, readerKey, person));
  const shown = $derived(H.shown(sections));
  let error = $state('');

  let now = $state(Date.now());
  $effect(() => { const t = setInterval(() => (now = Date.now()), 30_000); return () => clearInterval(t); });
  const time = (t: number) => new Date(t).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });

  // ---- fronting (Body, as Fronting shows it)
  const configured = $derived(!!projection.front_systems?.[accountId]);
  const display = $derived(fd.displayPrefs(projection.rows.pref, accountId));
  const layers = $derived(frontLayers(projection, accountId));
  const view = $derived(frontView(projection, accountId));
  const scene = $derived(fd.scene(layers, view.entries, display, accountId, own.map((m) => m.id)));
  const here = $derived(scene.nested.flatMap((l) => l.entries.map((e) => ({ e, l }))));
  const since = $derived.by(() => {
    void projection.front_systems;
    const out = new Map<string, number>();
    const intervals = configured ? sync.frontHistory(accountId)?.effective.intervals ?? [] : projection.fronts[accountId]?.intervals ?? [];
    for (const i of intervals) if (i.end_at === null) out.set(i.subject_id, Math.min(out.get(i.subject_id) ?? Infinity, i.start_at));
    return out;
  });
  const frontSince = $derived(since.size ? Math.min(...since.values()) : null);
  const named = $derived.by(() => { void projection.front_systems; return configured ? sync.frontHistory(accountId) : null; });
  let logging = $state(false);
  let placing = $state<Who | null>(null);

  // ---- quick switch: one tap replaces the front with that member
  let width = $state(720);
  const fit = $derived(Math.max(2, Math.floor((width + 6) / 62)) - 1);
  const recents = $derived.by(() => {
    const out: string[] = [];
    const rows = configured ? named?.saved.switches ?? [] : projection.fronts[accountId]?.switches ?? [];
    for (const s of [...rows].reverse()) for (const e of s.resulting_front) if (e.subject_type === 'member' && !out.includes(e.subject_id)) out.push(e.subject_id);
    return out;
  });
  const quick = $derived(H.quickSwitch(own.map((m) => m.id), [], recents, fit).map((id) => who.get(whoKey('member', id))).filter((w): w is Who => !!w));
  function tap(w: Who) {
    const label = `Switched Body to ${w.name}`;
    try {
      if (configured) doFrontSwitch(accountId, [{ subject_type: 'member', subject_id: w.id, layer_id: layers[0]?.id ?? 'front', is_primary: true }], label);
      else doSwitch([{ subject_type: 'member', subject_id: w.id, level: (layers[0]?.id ?? 'front') as Level, is_primary: true }], label);
      error = '';
    } catch (e) { error = e instanceof Error ? e.message : String(e); }
  }

  // ---- waiting for you: unread chats, as the chat list reads them
  let directory = $state(new Map<string, SpaceInfo>());
  $effect(() => {
    if (!sync.device?.session) return;
    let live = true;
    listSpaces().then((l) => { if (live) directory = new Map(l.map((s) => [s.id, s])); }).catch(() => {});
    return () => { live = false; };
  });
  interface Chat { channel: ChannelRow; space: SpaceRow | undefined; title: string; context: string | null; at: number; unread: number; muted: boolean; archived: boolean; who: string | null; text: string }
  const chats = $derived.by((): Chat[] => {
    const all = channels(projection).filter((c) => !c.archived && c.kind !== 'thread');
    const prefs = projection.rows.pref ?? {};
    const organize = chatPrefs(prefs, accountId);
    const switched = bodyPresence(projection, accountId);
    const inside = chatMembers(projection, accountId);
    const mine = new Set(members(projection).map((m) => m.id));
    const ss = spaces(projection);
    const names = new Map(members(projection).map((m) => [m.id, m.display_name ?? m.name]));
    const out: Chat[] = [];
    for (const c of all) {
      const space = ss.find((s) => s.id === c.space_id);
      if (!privateVisible(c, space, switched, null, organize.hidePrivate, (m) => mine.has(m), switched, inside)) continue;
      const latest = latestMessage(projection, c.id);
      const cleared = organize.hidden.get(c.id);
      if (cleared !== undefined && (latest?.occurred_at ?? 0) <= cleared) continue;
      const siblings = all.filter((o) => o.space_id === c.space_id).length;
      const title = c.kind === 'member_dm' ? c.name : space?.kind === 'dm' ? spaceTitle(space, directory.get(space.id), accountId)
        : space?.kind === 'shared' ? (groupsInto(space, siblings) ? c.name : space.name) : c.name;
      const notify = (prefs[`${accountId}||notify_channel:${c.id}`] ?? prefs[`||notify_channel:${c.id}`])?.fields.value;
      const p = previewOf(latest, (id) => names.get(id) ?? '', c.kind !== 'member_dm');
      out.push({
        channel: c, space, title, at: latest?.occurred_at ?? 0, unread: unread(projection, c.id, accountId), muted: notify === 'none',
        archived: stillArchived(organize.archived.get(c.id), latest?.occurred_at), who: p.who, text: p.text,
        context: space?.kind === 'shared' && groupsInto(space, siblings) ? space.name : null,
      });
    }
    return out;
  });
  const waiting = $derived(H.waiting(chats, (c) => c.unread, (c) => c.muted, (c) => c.archived, (c) => c.at));

  // ---- friends right now and new from friends
  $effect(() => {
    if (!sync.device?.session) return;
    void friendsState.load();
    const t = setInterval(() => void friendsState.load(), 120_000);
    return () => clearInterval(t);
  });
  const friendCards = $derived(H.friends(friendsState.rows, now));
  const followed = $derived(friendsState.following.filter((f) => f.status === 'active'));
  let feedSources = $state<FriendPosts[]>([]);
  $effect(() => {
    const ids = followed.map((f) => f.account.id).slice(0, 8);
    if (!ids.length) { feedSources = []; return; }
    let live = true;
    Promise.all(ids.map(async (account): Promise<FriendPosts> => {
      try { const items = (await api(`/posts?account=${encodeURIComponent(account)}&limit=3`)).items ?? []; return { account, posts: items }; }
      catch { return { account, posts: [] }; }
    })).then((s) => { if (live) feedSources = s; });
    return () => { live = false; };
  });
  const feed = $derived(friendsFeed(feedSources).slice(0, 3));
  const accountOf = (id: string) => friendsState.rows.find((r) => r.account.id === id)?.account;

  // ---- today
  const dayStart = $derived.by(() => { void Math.floor(now / 60_000); const d = new Date(); d.setHours(0, 0, 0, 0); return d.getTime(); });
  const todayEvents = $derived.by(() => {
    const name = (type: string, id: string) => who.get(whoKey(type, id))?.name;
    const rows = configured ? named?.saved.switches ?? [] : projection.fronts[accountId]?.switches ?? [];
    const switches = rows.map((s) => ({ id: s.id, occurred_at: s.occurred_at, retracted: s.retracted,
      resulting_front: s.resulting_front.map((e) => { const x = e as { layer_id?: string; level?: string; subject_type: string; subject_id: string; is_primary: boolean };
        return { level: x.layer_id ?? x.level ?? 'front', is_primary: x.is_primary, name: name(x.subject_type, x.subject_id) }; }) }));
    const ps = ownPosts(projection).filter((p) => !p.deleted).map((p) => ({ id: p.id, occurred_at: p.occurred_at, reply_to: p.reply_to, repost_of: p.repost_of,
      title: p.title, text: p.text, author: p.authors.map((a) => who.get(whoKey('member', a))?.name).find(Boolean) ?? null }));
    return H.today(switches, ps, dayStart, layers.map((l) => ({ id: l.id, name: l.name })));
  });

  // ---- a person's first day
  const posts = $derived(ownPosts(projection).filter((p) => !p.deleted));
  const firstDay = $derived(person && friendsState.loaded ? H.firstDay(followed.length + friendsState.following.filter((f) => f.status === 'requested').length, posts.length, self) : null);
  const handle = $derived(self ? memberHandles(projection, accountId).get(self.id)?.shown : undefined);
  let following = $state(false);
  let target = $state('');
  async function follow(e: Event) {
    e.preventDefault();
    try { await api('/follows', { method: 'POST', body: JSON.stringify({ target: target.trim() }) }); target = ''; following = false; error = ''; void friendsState.load(); }
    catch (x) { error = x instanceof Error ? x.message : 'Couldn’t send the request.'; }
  }
  async function share() {
    if (!handle) return;
    const text = `@${handle} on ${location.host}`;
    try { if (navigator.share) await navigator.share({ text }); else await navigator.clipboard.writeText(text); } catch { /* cancelled */ }
  }

  // ---- sections
  function saveSections(next: H.HomeSection[]) {
    sync.create('pref.set', sync.accountScope, null, { device: '', key: H.key(readerKey), value: H.value(next) });
  }
  const readerName = $derived(person ? null : who.get(whoKey('member', reader ?? ''))?.name ?? null);

  const circle = (c: string | null | undefined) => core.adaptColor(c ?? '#A09184', dark);
  const friendAvatar = (r: FriendRow) => personName(r.account).replace('@', '')[0]?.toUpperCase() ?? '?';
  const followedOn = (at: number) => new Date(at).toDateString() === new Date(now).toDateString() ? 'Followed today' : `Followed ${new Date(at).toLocaleDateString([], { month: 'short', day: 'numeric' })}`;
  const excerpt = (t: string) => (t.length > 160 ? `${t.slice(0, 159).trimEnd()}…` : t);
</script>

<div class="home" hidden={logging} bind:clientWidth={width}>
  {#if error}<p class="error" role="alert">{error}</p>{/if}

  {#if firstDay?.welcome}
    <section class="welcome" aria-label="Welcome">
      <h2>{self ? `Welcome, ${self.display_name ?? self.name}` : 'Welcome'}</h2>
      <p>Chorus is quieter with friends.</p>
      <div class="row-btns">
        <button class="primary" onclick={() => (following = true)}><Icon name="user-plus" size={18} />Find someone</button>
        {#if handle}<button class="neutral" onclick={share}><Icon name="share" size={18} />Share @{handle}</button>{/if}
      </div>
    </section>
  {/if}

  {#each shown as id (id)}
    {#if id === 'write'}
      {#if !firstDay?.welcome}
        <a class="card write" href="#/journal" aria-label="Write a post">
          <span class="face-sm"><Face who={self ? who.get(whoKey('member', self.id)) : undefined} size={32} {dark} /></span>
          <span class="ph">Write something{self ? `, ${self.display_name ?? self.name}` : ''}…</span>
          <Icon name="image" size={20} /><Icon name="poll" size={20} />
        </a>
      {/if}
    {:else if id === 'fronting'}
      <section class="card fronting" aria-label="Fronting now">
        <header>
          <strong>{configured ? scene.nested[0]?.name ?? 'Fronting' : 'Fronting'} now</strong>
          {#if frontSince}<span>since {time(frontSince)} · {fd.duration(now - frontSince)}</span>{/if}
        </header>
        {#if !here.length}<p class="none">No one fronting</p>
        {:else}
          <div class="presence">
            {#each here.slice(0, 12) as { e, l } (whoKey(e.subject_type, e.subject_id) + l.id)}
              {@const w = who.get(whoKey(e.subject_type, e.subject_id))}
              <button class="pf" data-style={l.style} style={fd.inkVars(l.style, l.color)} aria-label="{w?.name ?? 'Someone'}, {l.name}" onclick={() => w && (placing = w)}>
                <span class="ring"><Face who={w} size={50} {dark} /></span>
                <span class="nm">{w?.name ?? 'Someone'}</span>
              </button>
            {/each}
          </div>
        {/if}
        <div class="actions">
          <button class="primary" onclick={() => onswitch(accountId)}><Icon name="swap" size={18} />Switch</button>
          <button class="neutral" onclick={() => (logging = true)}><Icon name="history" size={18} />Log a past one</button>
        </div>
      </section>
    {:else if id === 'quick_switch'}
      <SectionLabel label="Quick switch" link="Edit" onlink={() => onswitch(accountId)} />
      <div class="quick">
        {#each quick as w (w.id)}
          <button class="qf" aria-label="Switch to {w.name}" onclick={() => tap(w)}><Face who={w} size={40} {dark} /><span>{w.name}</span></button>
        {/each}
        <button class="qf muted" aria-label="Switch…" onclick={() => onswitch(accountId)}><span class="all"><Icon name="users" size={20} /></span><span>Everyone</span></button>
      </div>
    {:else if id === 'waiting'}
      <SectionLabel label="Waiting for you" link={waiting.length ? 'Chat' : undefined} href="#/chat" />
      {#if !waiting.length}
        <p class="quiet"><Icon name="message" size={18} />Nothing waiting</p>
      {:else}
        <ul class="list">
          {#each waiting as c (c.channel.id)}
            <li><a href="#/chat/{c.channel.id}" aria-label="Open {c.title}">
              <span class="disc" style="background: {discColor(c.channel.id)}">{(c.title.match(/\p{L}|\p{N}/u)?.[0] ?? '#').toUpperCase()}</span>
              <span class="t"><strong>{c.title}</strong>{#if c.context}<small>{c.context}</small>{/if}<span class="pv">{c.who ? `${c.who}: ` : ''}{c.text}</span></span>
              <span class="r"><small>{chatTime(c.at, now)}</small><span class="badge">{c.unread > 99 ? '99+' : c.unread}</span></span>
            </a></li>
          {/each}
        </ul>
      {/if}
    {:else if id === 'friends'}
      {#if friendsState.loaded || friendCards.length}
        <SectionLabel label="Friends right now" link={friendCards.length ? 'Friends' : undefined} href="#/people" />
        {#if !friendCards.length}
          <p class="quiet"><Icon name="users" size={18} />Nobody yet</p>
        {:else}
          <div class="strip">
            {#each friendCards as { row, card } (row.account.id)}
              {@const entries = row.view?.entries ?? []}
              <a class="fcard" class:dim={card === 'quiet' || card === 'not_sharing'} href="#/people/{encodeURIComponent(row.account.id)}" aria-label="Open {personName(row.account)}">
                <span class="fh"><span class="av"><AvatarImage hash={row.account.avatar_blob} glyph={friendAvatar(row)} name={personName(row.account)} /></span><strong>{personName(row.account)}</strong></span>
                {#if card === 'around'}
                  <span class="stack">{#each entries.slice(0, 3) as m, i (`${m.name}:${i}`)}{@const c = circle(m.color)}<span class="mini" style="--fill: {c.ring}; --ink: {c.avatar_ink}"><AvatarImage hash={m.avatar_blob} glyph={m.glyph ?? m.name[0]} name={m.name} /></span>{/each}</span>
                  <span class="line">{nowLine(entries, 2)}</span>
                  {#if sinceLine(row.view, now)}<small>{sinceLine(row.view, now)}</small>{/if}
                {:else if card === 'not_yet'}
                  <span class="line soft">You’ll see who’s around after their next switch</span>
                  {#if row.following?.created_at}<small>{followedOn(row.following.created_at)}</small>{/if}
                {:else if card === 'posted'}
                  {#if row.latest}<span class="line">{activity(row.latest, shortAgo(row.latest.occurred_at, now))}</span>{/if}
                {:else if card === 'quiet'}<span class="line soft">Nobody’s around</span>
                {:else}<span class="line soft">Doesn’t share fronting</span>{/if}
              </a>
            {/each}
          </div>
        {/if}
      {/if}
    {:else if id === 'today'}
      <SectionLabel label="Today" link="History" href="#/history" />
      {#if !todayEvents.length}
        <p class="quiet"><Icon name="clock" size={18} />Nothing yet today</p>
      {:else}
        <ol class="today">
          {#each todayEvents.slice(0, 8) as e (e.id)}
            <li><time>{time(e.at)}</time><span class="dot {e.kind}"><Icon name={e.kind === 'switch' ? 'swap' : 'journal'} size={14} /></span><span class="tx">{e.text}</span></li>
          {/each}
        </ol>
      {/if}
    {:else if id === 'new_from_friends'}
      {#if feed.length}
        <SectionLabel label="New from friends" link="Journal" href="#/journal" />
        <ul class="list posts">
          {#each feed as { account, post } (post.id)}
            {@const a = accountOf(account)}
            <li><a href="#/journal/post/{encodeURIComponent(post.id)}">
              <span class="av lg"><AvatarImage hash={a?.avatar_blob} glyph={(a ? personName(a).replace('@', '')[0] : '?') ?? '?'} name={a ? personName(a) : ''} /></span>
              <span class="t"><span class="who"><strong>{post.author_cards?.[0]?.display_name ?? post.author_cards?.[0]?.name ?? (a ? personName(a) : 'Someone')}</strong> · {shortAgo(post.occurred_at, now)}</span>
                <span class="pv wrap">{excerpt(post.title ? `${post.title}. ${post.text}` : post.text)}</span></span>
            </a></li>
          {/each}
        </ul>
        <p class="caught"><span><Icon name="check" size={18} /></span><strong>You’re all caught up</strong></p>
      {/if}
    {/if}
  {/each}

  {#if firstDay && (firstDay.post || firstDay.profile)}
    <SectionLabel label="Try" />
    <div class="try">
      {#if firstDay.post}<a class="card" href="#/journal"><Icon name="journal" size={20} /><span><strong>Write your first post</strong><small>Friends can reply and react</small></span><Icon name="chevron" size={16} /></a>{/if}
      {#if firstDay.profile && self}<a class="card" href="#/profile/{self.id}"><Icon name="user" size={20} /><span><strong>Fill in your profile</strong><small>Pronouns, a picture, a few words</small></span><Icon name="chevron" size={16} /></a>{/if}
    </div>
  {/if}
</div>

{#if homeUi.sections}
  <HomeSections {sections} {person} readerName={readerName} onsave={saveSections} onclose={() => (homeUi.sections = false)} />
{/if}
{#if logging}
  <LogPastSwitch {projection} {dark} front={accountId} {named} onclose={() => (logging = false)} />
{/if}
{#if placing}<PlaceSheet {projection} {dark} front={accountId} who={placing} since={since.get(placing.id) ?? null} onclose={() => (placing = null)} />{/if}
{#if following}
  <Sheet title="Follow someone" onclose={() => (following = false)}>
    <form class="follow" onsubmit={follow}>
      <input bind:value={target} placeholder="@handle" autocomplete="off" aria-label="Their handle" />
      <button class="primary" disabled={!target.trim()}>Send request</button>
    </form>
  </Sheet>
{/if}

<style>
  .home { display: grid; gap: 4px; max-width: 720px; margin-inline: auto; align-content: start; }
  .error { color: var(--danger); margin: 0 0 8px; }
  .card { display: flex; background: var(--surface); border: 1px solid var(--line); border-radius: var(--r-lg); color: var(--ink); text-decoration: none; }
  .primary, .neutral { display: inline-flex; align-items: center; justify-content: center; gap: 6px; min-height: 44px; padding: 10px 14px; border: 0; border-radius: 14px; font: inherit; font-size: 15px; font-weight: 600; cursor: pointer; }
  .primary { background: var(--accent); color: var(--on-accent); }
  .primary:disabled { opacity: 0.5; cursor: default; }
  .neutral { background: var(--surface-2); color: var(--ink); }
  .fronting { flex-direction: column; gap: 12px; padding: 15px; border-radius: 22px; margin-top: 4px; }
  .fronting header { display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; }
  .fronting header strong { flex: 1; font-size: 15px; }
  .fronting header span { color: var(--ink-3); font-size: 12px; }
  .none { margin: 0; padding: 8px 0; color: var(--ink-3); }
  .presence { display: flex; gap: 14px; overflow-x: auto; padding-bottom: 2px; }
  .pf { display: grid; justify-items: center; gap: 4px; width: 64px; flex: none; border: 0; background: none; padding: 0; color: var(--ink-2); font: inherit; cursor: pointer; }
  .ring { display: grid; place-items: center; width: 64px; height: 64px; border-radius: 50%; border: 2px var(--l-dash, solid) var(--l-ring, var(--line)); box-sizing: border-box; }
  .pf[data-style='plain'] .ring { border-color: color-mix(in srgb, var(--ink-3) 45%, transparent); }
  .pf[data-style='faint'] .ring { opacity: 0.8; }
  .nm { font-size: 12px; max-width: 64px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .actions { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
  .quick { display: flex; gap: 6px; overflow-x: auto; padding: 2px 0; }
  .qf { display: grid; justify-items: center; gap: 4px; width: 56px; flex: none; padding: 4px 0; border: 0; border-radius: 12px; background: none; color: var(--ink-2); font: inherit; font-size: 11px; cursor: pointer; }
  .qf span:last-child { max-width: 56px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .qf.muted { color: var(--ink-3); }
  .qf:hover { background: var(--surface); }
  .all { display: grid; place-items: center; width: 40px; height: 40px; border-radius: 50%; background: var(--surface-2); color: var(--ink-2); }
  .quiet { display: flex; align-items: center; gap: 10px; margin: 2px 0; padding: 12px 14px; background: var(--surface); border: 1px solid var(--line); border-radius: var(--r-lg); color: var(--ink-3); font-size: 14px; }
  .list { list-style: none; margin: 0; padding: 0; }
  .list li + li { border-top: 1px solid var(--line); }
  .list a { display: flex; align-items: center; gap: 12px; min-height: 64px; padding: 8px 4px; color: var(--ink); text-decoration: none; border-radius: 12px; }
  .list a:hover { background: var(--surface); }
  .disc { display: grid; place-items: center; flex: none; width: 44px; height: 44px; border-radius: 50%; color: #1b1b1b; font-weight: 700; }
  .t { flex: 1; min-width: 0; display: grid; }
  .t strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .t small { color: var(--ink-3); font-size: 12px; }
  .pv { color: var(--ink-2); font-size: 14px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .pv.wrap { white-space: normal; display: -webkit-box; -webkit-line-clamp: 3; line-clamp: 3; -webkit-box-orient: vertical; }
  .r { display: grid; justify-items: end; gap: 4px; flex: none; }
  .r small { color: var(--ink-3); font-size: 12px; }
  .badge { min-width: 20px; height: 20px; padding: 0 6px; border-radius: 10px; background: var(--sun); color: var(--on-sun); font-size: 12px; font-weight: 700; line-height: 20px; text-align: center; box-sizing: border-box; }
  .strip { display: flex; gap: 10px; overflow-x: auto; padding: 2px 0; }
  .fcard { display: grid; align-content: start; gap: 8px; width: 164px; flex: none; padding: 12px; box-sizing: border-box; background: var(--surface); border: 1px solid var(--line); border-radius: var(--r-lg); color: var(--ink); text-decoration: none; }
  .fcard.dim strong { color: var(--ink-2); }
  .fh { display: flex; align-items: center; gap: 8px; min-width: 0; }
  .fh strong { font-size: 14px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .av { display: grid; place-items: center; width: 24px; height: 24px; flex: none; border-radius: 50%; overflow: hidden; background: var(--surface-2); color: var(--ink-2); font-size: 12px; }
  .av.lg { width: 40px; height: 40px; font-size: 16px; }
  .stack { display: flex; }
  .mini { display: grid; place-items: center; width: 32px; height: 32px; margin-left: -6px; border-radius: 50%; overflow: hidden; background: var(--fill); color: var(--ink); border: 2px solid var(--surface); box-sizing: border-box; font-size: 13px; font-weight: 700; }
  .mini:first-child { margin-left: 0; }
  .line { font-size: 13px; line-height: 16px; display: -webkit-box; -webkit-line-clamp: 2; line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
  .line.soft, .fcard small { color: var(--ink-3); }
  .fcard small { font-size: 11px; }
  .today { list-style: none; margin: 0; padding: 2px 0; display: grid; }
  .today li { display: grid; grid-template-columns: 56px 26px 1fr; gap: 12px; align-items: start; position: relative; }
  .today time { color: var(--ink-3); font-size: 12px; padding-top: 5px; white-space: nowrap; }
  .dot { display: grid; place-items: center; width: 26px; height: 26px; border-radius: 50%; }
  .dot.switch { background: color-mix(in srgb, var(--accent) 16%, transparent); color: var(--accent); }
  .dot.post { background: color-mix(in srgb, var(--danger) 16%, transparent); color: var(--danger); }
  .today li:not(:last-child) .dot::after { content: ''; position: absolute; left: 80px; top: 28px; bottom: 0; width: 2px; background: var(--line); }
  .tx { font-size: 14px; line-height: 19px; padding: 3px 0 12px; }
  .caught { display: grid; justify-items: center; gap: 6px; margin: 12px 0; }
  .caught span { display: grid; place-items: center; width: 36px; height: 36px; border-radius: 50%; background: var(--accent-soft); color: var(--accent); }
  .write { align-items: center; gap: 10px; min-height: 52px; padding: 0 12px; margin-top: 4px; color: var(--ink-3); }
  .write .ph { flex: 1; font-size: 15px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .welcome { display: grid; gap: 10px; padding: 18px; margin-top: 8px; border-radius: 22px; background: var(--accent-soft); }
  .welcome h2 { margin: 0; font-size: 20px; }
  .welcome p { margin: 0; color: var(--ink-2); }
  .row-btns { display: flex; gap: 8px; flex-wrap: wrap; }
  .try { display: grid; gap: 8px; }
  .try .card { align-items: center; gap: 12px; min-height: 56px; padding: 8px 14px; }
  .try .card > span { flex: 1; display: grid; }
  .try small { color: var(--ink-3); font-size: 12px; }
  .follow { display: grid; gap: 12px; }
  .follow input { padding: 12px; border: 1px solid var(--line); border-radius: 12px; background: var(--bg); color: var(--ink); font: inherit; }
</style>
