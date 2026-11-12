<script lang="ts">
  import { untrack } from 'svelte';
  import { router } from './lib/router.svelte';
  import { sync, type Projection, type Status } from './lib/sync/client';
  import Chat from './lib/ui/Chat.svelte';
  import History from './lib/ui/History.svelte';
  import FrontReview from './lib/ui/FrontReview.svelte';
  import HomeDashboard from './lib/ui/HomeDashboard.svelte';
  import Fronting from './lib/ui/Fronting.svelte';
  import { homeUi } from './lib/home-ui.svelte';
  import MemberEditor from './lib/ui/MemberEditor.svelte';
  import Members from './lib/ui/Members.svelte';
  import Onboarding from './lib/ui/Onboarding.svelte';
  import Setup from './lib/ui/Setup.svelte';
  import { setupOwed } from './lib/setup';
  import People from './lib/ui/People.svelte';
  import UpdateSheet from './lib/ui/UpdateSheet.svelte';
  import SettingsShell from './lib/ui/SettingsShell.svelte';
  import Stage from './lib/ui/Stage.svelte';
  import Search from './lib/ui/Search.svelte';
  import Insights from './lib/ui/Insights.svelte';
  import SystemBoard from './lib/ui/SystemBoard.svelte';
  import Journal from './lib/ui/Journal.svelte';
  import PostThread from './lib/ui/PostThread.svelte';
  import Profile from './lib/ui/Profile.svelte';
  import FriendProfiles from './lib/ui/FriendProfiles.svelte';
  import FriendPage from './lib/ui/FriendPage.svelte';
  import FriendSharing from './lib/ui/FriendSharing.svelte';
  import FriendNotify from './lib/ui/FriendNotify.svelte';
  import BucketPage from './lib/ui/BucketPage.svelte';
  import AdvancedSharing from './lib/ui/AdvancedSharing.svelte';
  import PostStage from './lib/ui/PostStage.svelte';
  import More from './lib/ui/More.svelte';
  import Notifications from './lib/ui/Notifications.svelte';
  import { inbox, refreshInbox } from './lib/inbox-state.svelte';
  import { readMarks, showsPref, unread as unreadCount } from './lib/inbox';
  import { bodyAuthors } from './lib/front-view';
  import Switcher from './lib/ui/Switcher.svelte';
  import Fronts from './lib/ui/Fronts.svelte';
  import LayersDisplay from './lib/ui/LayersDisplay.svelte';
  import { frontingUi } from './lib/fronting-ui.svelte';
  import { displayPrefs, front as fdFront } from './lib/fronting-display';
  import Trash from './lib/ui/Trash.svelte';
  import UndoToast from './lib/ui/UndoToast.svelte';
  import ConnectionBanner from './lib/ui/kit/ConnectionBanner.svelte';
  import SlowDownToast from './lib/ui/kit/SlowDownToast.svelte';
  import { selfMember, members, groups } from './lib/data';
  import { core } from './lib/core';
  import { appearance } from './lib/appearance.svelte';
  import Icon from './lib/ui/Icon.svelte';
  import AvatarImage from './lib/ui/AvatarImage.svelte';
  import { frontView } from './lib/front-view';
  import { messageById } from './lib/data';
  import { parseLink } from './lib/share-links';
  import { apiFetch } from './lib/http';
  import { apiBase } from './lib/sync/device';

  // a link to a message (D-109): its chat, at it, when this account has it; the chat applies its
  // own rules about what shows; anything else lands on the chat list
  $effect(() => {
    if (router.route.name !== 'message' || !router.route.id || !projection) return;
    const id = router.route.id;
    const local = messageById(projection, id);
    if (local) { location.replace(`#/chat/${local.channel_id}/${id}`); return; }
    void apiFetch(`${apiBase()}/messages/${encodeURIComponent(id)}`, { headers: { authorization: `Bearer ${sync.device?.session ?? ''}` } })
      .then((r) => (r.ok ? r.json() : null))
      .catch(() => null)
      .then((j) => { if (router.route.name === 'message') location.replace(j?.channel_id ? `#/chat/${j.channel_id}/${id}` : '#/chat'); });
  });
  // links to our posts and messages open here instead of in a new tab (D-109)
  function openOwnLink(e: MouseEvent) {
    if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
    const a = (e.target as Element | null)?.closest?.('a[href]') as HTMLAnchorElement | null;
    if (!a || (a.target !== '_blank' && new URL(a.href).pathname === '/')) return;
    const target = parseLink(a.href, location.origin);
    if (!target) return;
    e.preventDefault();
    router.go(target.kind === 'post' ? `/journal/post/${target.id}` : `/message/${target.id}`);
  }
  let switching = $state(false);
  // the desktop chat hides the sidebar behind its rail's menu button (Figma F5.21)
  let navOpen = $state(false);
  $effect(() => { void router.route; navOpen = false; });
  let switchFront = $state<string | undefined>(undefined);
  let switchAddTo = $state<string | undefined>(undefined);
  addEventListener('keydown', (e: KeyboardEvent) => {
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's' && sync.device && !person) {
      e.preventDefault();
      switching = true;
    }
  });

  const dark = $derived(appearance.dark);
  // a new system's setup (F1.2-F1.4) is owed until it's finished
  let setupDone = $state(0);
  const setupName = $derived.by(() => { void setupDone; return sync.device ? setupOwed.pending(sync.accountId) : null; });

  let status: Status = $state(sync.status);
  let storageFull = $state(sync.storageFull);
  // raw: the client hands us a new object per change (deltas, copy-on-write); no deep proxies
  let projection: Projection | null = $state.raw(sync.projection());
  $effect(() =>
    sync.subscribe(() => {
      status = sync.status;
      storageFull = sync.storageFull;
      projection = sync.projection();
    }),
  );

  // a person account has one member, themself, and no front to switch (D-003)
  const person = $derived(!!projection && !!selfMember(projection));
  // Figma F11: Notifications beside Fronting and Chat; on a phone-wide screen the bar keeps
  // Fronting · Notifications · Chat · Journal · More, and Members and Friends wait in More
  const allTabs = [
    { path: '/', name: 'home', label: 'Home', icon: 'home', wide: true },
    { path: '/fronting', name: 'fronting', label: 'Fronting', icon: 'front' },
    { path: '/notifications', name: 'notifications', label: 'Notifications', icon: 'bell' },
    { path: '/chat', name: 'chat', label: 'Chat', icon: 'chat' },
    { path: '/journal', name: 'journal', label: 'Journal', icon: 'journal' },
    { path: '/members', name: 'members', label: 'Members', icon: 'members', wide: true },
    { path: '/people', name: 'people', label: 'Friends', icon: 'friends', wide: true },
    { path: '/more', name: 'more', label: 'More', icon: 'more' },
  ];
  const tabs = $derived(person
    ? [allTabs[0], allTabs[4], allTabs[2], allTabs[3], { ...allTabs[6], wide: false }, allTabs[7]]
    : allTabs);
  // the Notifications badge: unread for whoever reads by default (D-127), checked every minute
  const ready = $derived(!!projection);
  $effect(() => {
    if (!ready || !sync.device?.session) return;
    const latest = () => untrack(() => projection);
    void refreshInbox(latest()!);
    const t = setInterval(() => { const p = latest(); if (p) void refreshInbox(p); }, 60_000);
    return () => clearInterval(t);
  });
  const inboxUnread = $derived.by(() => {
    if (!projection || inbox.account !== sync.accountId) return 0;
    const reader = selfMember(projection)?.id ?? bodyAuthors(projection, sync.accountId)[0] ?? members(projection)[0]?.id;
    const read = (reader && readMarks(projection.rows.pref, sync.accountId).get(reader)) || { until: 0, ids: [] };
    return unreadCount(inbox.items, read, showsPref(projection.rows.pref, sync.accountId));
  });
  const active = $derived(['member', 'profile'].includes(router.route.name) ? 'members' : ['post', 'board'].includes(router.route.name) ? 'journal' :
    router.route.name === 'stage' ? 'chat' : router.route.name === 'friend' ? 'people' : ['history', 'fronts', 'layers', 'review'].includes(router.route.name) ? 'fronting' :
    ['insights', 'data', 'trash', 'settings', 'computer', 'search', 'post-stage'].includes(router.route.name) ? 'more' : router.route.name);
  const account = $derived(projection?.rows.account?.[sync.accountId]?.fields);
  const system = $derived(projection?.rows.system?.[sync.accountId]?.fields);
  // a singlet is named by their self member, never "Your system"
  const self = $derived(projection ? selfMember(projection) : undefined);
  const accountName = $derived(String(system?.name ?? account?.display_name ?? (self ? self.display_name ?? self.name : 'Your system')));
  const bodyFront = $derived(frontView(projection, sync.accountId));
  const homeSimple = $derived(fdFront(displayPrefs(projection?.rows.pref, sync.accountId), frontingUi.front ?? sync.accountId).preset === 'simple');
  const current = $derived(bodyFront.entries);
  const subjects = $derived(new Map([
    ...members(projection ?? { rows: {}, sets: {}, fronts: {}, opaque: 0 }).map((m) => [m.id, { name: m.display_name ?? m.name, color: m.color, glyph: m.sigils[0] ?? m.name[0], blob: m.avatar_blob }] as const),
    ...groups(projection ?? { rows: {}, sets: {}, fronts: {}, opaque: 0 }).map((g) => [g.id, { name: g.name, color: g.color ?? '#42A397', glyph: g.name[0], blob: g.avatar_blob }] as const),
  ]));
  const frontNames = $derived.by(() => {
    if (bodyFront.configured) {
      if (bodyFront.deleted) return 'Deleted front';
      if (bodyFront.state === 'unavailable') return 'Source unavailable';
      const layers = projection?.front_systems?.[sync.accountId]?.system.layers;
      return current.map((e) => `${subjects.get(e.subject_id)?.name ?? 'Someone'} (${layers?.[e.layer_id]?.name ?? e.layer_id})`).join(' & ') || 'No one recorded';
    }
    return current.filter((e) => e.layer_id === 'front').map((e) => subjects.get(e.subject_id)?.name).filter(Boolean).join(' & ') || 'No one fronting';
  });
  const routeTitle = $derived(({ home: 'Home', fronting: 'Fronting', notifications: 'Notifications', members: 'Members', member: 'Edit member', profile: 'Profile', journal: 'Journal', board: 'System board', post: 'Post', chat: 'Chat', people: 'Friends', friend: 'Friends', history: 'Front history', review: 'Review', fronts: 'Fronts', layers: 'Layers & display', insights: 'Insights', data: 'Your data', settings: 'Settings', search: 'Search', trash: 'Trash', more: 'More', computer: 'This computer' } as Record<string, string>)[router.route.name] ?? 'Chorus');
</script>

<svelte:document onclick={openOwnLink} />

<!-- signed out (D-153): onboarding, as for a browser with no device; the kept copy isn't shown -->
{#if status === 'no-device' || status === 'signed-out'}
  <Onboarding />
{:else if projection && setupName !== null}
  <Setup {projection} name={setupName} ondone={() => { setupOwed.done(sync.accountId); setupDone++; }} />
{:else if projection}
  <div class="shell" class:chat-page={router.route.name === 'chat'} class:nav-open={navOpen}>
    <a class="skip" href="#main-content" onclick={(e) => { e.preventDefault(); document.getElementById('main-content')?.focus(); }}>Skip to content</a>
    <aside class="app-nav" aria-label="App navigation">
      <a class="identity" href="#/" title={accountName}>
        <span class="system-avatar">{accountName[0]?.toUpperCase()}</span>
        <span class="identity-text"><strong>{accountName}</strong><span>Chorus</span></span>
      </a>
      <nav aria-label="Sections">
        {#each tabs as t (t.name)}
          <a href="#{t.path}" class:on={active === t.name} class:wide-only={'wide' in t && t.wide} aria-current={active === t.name ? 'page' : undefined}
            aria-label={t.name === 'notifications' && inboxUnread ? `Notifications, ${inboxUnread} unread` : undefined}><span class="tab-icon"><Icon name={t.icon} />{#if t.name === 'notifications' && inboxUnread}<span class="badge">{inboxUnread > 99 ? '99+' : inboxUnread}</span>{/if}</span><span>{t.label}</span></a>
        {/each}
      </nav>
      <nav class="desktop-tools" aria-label="Your tools">
        {#if self}<a href="#/profile/{self.id}"><Icon name="members" />My profile</a>{/if}
        <a href="#/search"><Icon name="search" />Search</a>
        <a href="#/settings"><Icon name="settings" />Settings</a>
      </nav>
      {#if !person}
        <button class="front-summary" onclick={() => (switching = true)} aria-label="Open switcher">
          <span class="front-summary-title"><Icon name="layers" size={16} />{bodyFront.configured ? bodyFront.name : 'Fronting'}</span>
          <span class="front-stack">{#each current.slice(0, 4) as e (e.subject_id)}{@const m = subjects.get(e.subject_id)}{#if m}{@const c = core.adaptColor(m.color, dark)}<span class="mini-avatar" style="--member: {c.ring}; --member-ink: {c.avatar_ink}"><AvatarImage hash={m.blob} glyph={m.glyph} name={m.name} /></span>{/if}{/each}</span>
          <span class="front-summary-names">{frontNames}</span>
          <span class="shortcut">Switch · Ctrl/⌘ S</span>
        </button>
      {/if}
    </aside>
    {#if navOpen}<button class="scrim" aria-label="Close navigation" onclick={() => (navOpen = false)}></button>{/if}
    <div class="workspace">
      <!-- Fronting's subpages carry their own back-arrow bars (Figma F3.4, F3.5, F3.7, F3.9), as Friends' pages do (F7) -->
      <header class="app-bar" class:subpage={(['fronts', 'layers', 'history', 'review'].includes(router.route.name) && !person) || router.route.name === 'friend' || router.route.name === 'people'}>
        <div><strong>{routeTitle}</strong><span class="account-subtitle">{accountName}{#if router.route.name === 'fronting' && !person}{' · '}{homeSimple ? 'simple view' : `${current.length} here now`}{:else if router.route.name === 'home'}{' · '}{new Date().toLocaleDateString([], { weekday: 'long', day: 'numeric', month: 'long' })}{/if}</span></div>
        {#if router.route.name === 'home'}
          <button class="sections-pill" onclick={() => (homeUi.sections = true)}><Icon name="sliders" size={18} />Sections</button>
          <a class="icon-button" href="#/search" aria-label="Search"><Icon name="search" /></a>
        {:else if router.route.name === 'fronting' && !person}
          <!-- Fronting's own bar (Figma F3.1): history and Layers & display for the shown front -->
          <a class="icon-button" href="#/history" aria-label="Front history"><Icon name="history" /></a>
          <a class="icon-button" href="#/layers/{frontingUi.front ?? sync.accountId}" aria-label="Layers and display"><Icon name="sliders" /></a>
        {:else}
        <a class="icon-button" href="#/search" aria-label="Search"><Icon name="search" /></a>
        {/if}
        {#if !person && !['home', 'fronting'].includes(router.route.name)}<button class="icon-button mobile-switch" onclick={() => (switching = true)} aria-label="Open switcher"><Icon name="swap" /></button>{/if}
      </header>
    <ConnectionBanner {status} />
    {#if storageFull}
      <p class="storage-full" role="alert">
        This browser's storage is full, so changes made here aren't saved on this device yet. They
        still sync while you're online: free some space, or keep this tab open until they have.
      </p>
    {/if}
    <main id="main-content" tabindex="-1">
      {#if person && ['fronting', 'members', 'history', 'fronts', 'layers', 'review'].includes(router.route.name)}
        <HomeDashboard {projection} {dark} onswitch={() => {}} />
      {:else if router.route.name === 'members'}
        <Members {projection} {dark} tab={router.route.id} />
      {:else if router.route.name === 'member' && router.route.id}
        <MemberEditor {projection} id={router.route.id} {dark} page={router.route.memberId} />
      {:else if router.route.name === 'friend' && router.route.id && router.route.memberId}
        <FriendProfiles accountId={router.route.id} memberId={router.route.memberId} {dark} {projection} />
      {:else if router.route.name === 'friend' && router.route.id}
        <FriendPage accountId={router.route.id} {dark} {projection} />
      {:else if router.route.name === 'profile' && router.route.id}
        <Profile {projection} id={router.route.id} {dark} />
      {:else if router.route.name === 'journal'}
        <Journal {projection} {dark} />
      {:else if router.route.name === 'board'}
        <SystemBoard {projection} {dark} />
      {:else if router.route.name === 'post' && router.route.id}
        <PostThread {projection} id={router.route.id} {dark} />
      {:else if router.route.name === 'post-stage' && router.route.id}
        <PostStage {projection} id={router.route.id} />
      {:else if router.route.name === 'more'}
        <More {projection} />
      {:else if router.route.name === 'notifications'}
        <Notifications {projection} {dark} />
      {:else if router.route.name === 'chat'}
        <Chat {projection} {dark} channelId={router.route.id} focusId={router.route.messageId}
          onswitch={() => (switching = true)} onmenu={() => (navOpen = true)} />
      {:else if router.route.name === 'search'}
        <Search {projection} {dark} initialScope={router.route.id === "saved" ? "saved" : router.route.id === "posts" ? "posts" : "messages"} />
      {:else if router.route.name === 'fronts'}
          <Fronts {projection} {dark} />
      {:else if router.route.name === 'layers'}
          <LayersDisplay {projection} front={router.route.id ?? sync.accountId} />
        {:else if router.route.name === 'history'}
        <History {projection} {dark} />
      {:else if router.route.name === 'review'}
        <FrontReview {projection} {dark} front={router.route.id || undefined} />
      {:else if router.route.name === 'insights'}
        <Insights {projection} />
      {:else if router.route.name === 'stage' && router.route.id}
        <Stage {projection} {dark} channelId={router.route.id} />
      {:else if router.route.name === 'settings'}
        <SettingsShell {projection} page={router.route.id} />
      {:else if router.route.name === 'people' && router.route.id === 'share' && router.route.memberId}
        <FriendSharing followId={router.route.memberId} {projection} {dark} />
      {:else if router.route.name === 'people' && router.route.id === 'notify' && router.route.memberId}
        <FriendNotify followId={router.route.memberId} />
      {:else if router.route.name === 'people' && router.route.id === 'bucket'}
        {#key router.route.memberId}<BucketPage bucketId={router.route.memberId === 'new' ? null : router.route.memberId ?? null} {projection} {dark} />{/key}
      {:else if router.route.name === 'people' && router.route.id === 'advanced'}
        <AdvancedSharing {projection} />
      {:else if router.route.name === 'people'}
        <People {projection} {dark} />
      {:else if router.route.name === 'trash'}
        <Trash {projection} channelId={router.route.id} />
      {:else if router.route.name === 'fronting'}
        <Fronting {projection} {dark} onswitch={(front?: string, addTo?: string) => { switchFront = front; switchAddTo = addTo; switching = true; }} />
      {:else}
        <HomeDashboard {projection} {dark} onswitch={(front?: string, addTo?: string) => { switchFront = front; switchAddTo = addTo; switching = true; }} />
      {/if}
    </main>
    </div>
  </div>
  {#if switching}
    <Switcher {projection} {dark} front={switchFront} addTo={switchAddTo} onclose={() => { switching = false; switchFront = undefined; switchAddTo = undefined; }} />
  {/if}
  <UndoToast />
  <SlowDownToast />
  <UpdateSheet />
{/if}

<style>
  .shell { min-height: 100dvh; display: grid; grid-template-columns: 240px minmax(0, 1fr); }
  .skip { position: fixed; top: -100px; left: 16px; z-index: 100; padding: 12px; background: var(--accent); color: var(--on-accent); border-radius: var(--r-sm); }
  .skip:focus { top: 12px; }
  .app-nav { position: sticky; top: 0; height: 100dvh; display: flex; flex-direction: column; padding: 20px 16px; border-right: 1px solid var(--line); background: var(--bg); gap: 20px; }
  .identity { display: flex; align-items: center; gap: 10px; color: var(--ink); text-decoration: none; padding: 0 8px; min-height: 48px; }
  .system-avatar { width: 40px; height: 40px; border-radius: 50%; background: var(--accent); color: var(--on-accent); display: grid; place-items: center; font-weight: 700; flex: none; }
  .identity-text { display: grid; min-width: 0; font-size: var(--fs-sm); }
  .identity-text strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .identity-text > span { color: var(--ink-3); font-size: var(--fs-xs); }
  nav { display: grid; gap: 4px; }
  nav a { display: flex; gap: 12px; align-items: center; padding: 12px; min-height: 48px; color: var(--ink-2); text-decoration: none; border-radius: var(--r-full); font-size: var(--fs-md); font-weight: 500; }
  nav a.on { background: var(--surface-2); color: var(--ink); font-weight: 700; }
  .tab-icon { position: relative; display: inline-grid; }
  .badge { position: absolute; top: -6px; right: -10px; min-width: 16px; height: 16px; padding: 0 4px; border-radius: 8px; background: var(--sun); color: var(--on-sun);
    font-size: 10px; font-weight: 700; line-height: 16px; text-align: center; }
  nav a.on :global(.icon) { color: var(--accent); }
  nav a:hover { background: var(--surface); }
  .desktop-tools { border-top: 1px solid var(--line); padding-top: 16px; }
  .front-summary { margin-top: auto; display: grid; gap: 8px; text-align: left; background: var(--surface); border: 0; border-radius: var(--r-md); padding: 12px; cursor: pointer; }
  .front-summary-title { display: flex; align-items: center; gap: 6px; color: var(--ink-2); font-size: var(--fs-xs); }
  .front-stack { display: flex; padding-left: 4px; }
  .mini-avatar { display: grid; place-items: center; width: 30px; height: 30px; background: var(--member); color: var(--member-ink); border: 2px solid var(--member); border-radius: 50%; overflow: hidden; margin-left: -4px; font-size: var(--fs-xs); }
  .front-summary-names { font-size: var(--fs-sm); overflow-wrap: anywhere; }
  .shortcut { color: var(--ink-3); font-size: var(--fs-xs); }
  .workspace { min-width: 0; }
  .app-bar { display: flex; align-items: center; gap: 12px; min-height: 76px; padding: 12px 24px; background: var(--surface); border-bottom: 1px solid var(--line); }
  .app-bar > div { min-width: 0; }
  .app-bar strong { display: block; font-size: 20px; line-height: 26px; }
  .account-subtitle { display: block; color: var(--ink-3); font-size: var(--fs-xs); }
  .mobile-switch { display: none; }
  .sections-pill { display: inline-flex; align-items: center; gap: 6px; min-height: 40px; padding: 0 14px; border: 0; border-radius: var(--r-full); background: var(--surface-2); color: var(--ink); font: inherit; font-size: var(--fs-sm); font-weight: 600; cursor: pointer; }
  @media (max-width: 1023px) { .app-bar.subpage { display: none; } }
  main { padding: 24px; max-width: 1200px; margin-inline: auto; min-width: 0; outline: none; }
  .chat-page main { padding: 0; max-width: none; }
  /* a page fades in when you go to it (owner, 2026-11-07) */
  main > :global(*) { animation: chorus-fade-in var(--t-fast) var(--ease); }
  .storage-full { margin: 0; padding: 12px 24px; font-size: var(--fs-sm); background: var(--accent-soft); }
  /* wide screens: the sidebar and the page sit together in the middle; the sidebar keeps its width
     and takes the left margin as padding (it used to shrink to ~100 px past 1800 px) */
  @media (min-width: 1600px) {
    .shell:not(.chat-page) { grid-template-columns: calc((100vw - 1440px) / 2 + 240px) minmax(0, 1fr); }
    .shell:not(.chat-page) .app-nav { padding-left: calc(100% - 224px); }
    .shell:not(.chat-page) main { margin-left: 0; }
  }
  .scrim { display: none; }
  @media (min-width: 1024px) {
    .shell.chat-page { grid-template-columns: minmax(0, 1fr); }
    .chat-page .app-nav { position: fixed; inset: 0 auto 0 0; z-index: 30; width: 280px; transform: translateX(-100%); visibility: hidden;
      transition: transform var(--t-base) var(--ease), visibility 0s linear var(--t-base); }
    .chat-page.nav-open .app-nav { transform: none; visibility: visible; transition: transform var(--t-base) var(--ease); box-shadow: var(--shadow-pop); }
    .chat-page .scrim { display: block; position: fixed; inset: 0; z-index: 29; border: 0; background: rgb(0 0 0 / .4); }
    .chat-page .app-bar { display: none; }
  }
  @media (max-width: 1023px) {
    .shell { display: block; padding-bottom: calc(72px + env(safe-area-inset-bottom)); }
    .app-nav { position: fixed; inset: auto 0 0; z-index: 10; height: auto; padding: 6px 8px calc(6px + env(safe-area-inset-bottom)); background: var(--surface); border: 0; border-top: 1px solid var(--line); display: block; }
    .identity, .desktop-tools, .front-summary { display: none; }
    nav { display: flex; justify-content: space-around; gap: 0; }
    nav a { flex: 1; flex-direction: column; gap: 4px; padding: 6px 2px; font-size: 11px; border-radius: 12px; min-width: 0; min-height: 56px; }
    nav a.on { background: transparent; color: var(--accent); }
    nav a.wide-only { display: none; }
    .app-bar { min-height: 72px; padding: 10px 16px; gap: 4px; }
    .mobile-switch { display: inline-grid; }
    main { padding: 16px; }
  }
  :global(body.capture) .app-nav, :global(body.capture) .app-bar, :global(body.capture .toast) { display: none !important; }
  :global(body.capture) .shell { display: block; padding: 0; }
</style>
