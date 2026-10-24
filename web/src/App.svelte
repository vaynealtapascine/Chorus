<script lang="ts">
  import { router } from './lib/router.svelte';
  import { sync, type Projection, type Status } from './lib/sync/client';
  import Chat from './lib/ui/Chat.svelte';
  import History from './lib/ui/History.svelte';
  import Home from './lib/ui/Home.svelte';
  import MemberEditor from './lib/ui/MemberEditor.svelte';
  import Members from './lib/ui/Members.svelte';
  import Onboarding from './lib/ui/Onboarding.svelte';
  import People from './lib/ui/People.svelte';
  import DataPage from './lib/ui/DataPage.svelte';
  import Settings from './lib/ui/Settings.svelte';
  import Stage from './lib/ui/Stage.svelte';
  import Search from './lib/ui/Search.svelte';
  import Insights from './lib/ui/Insights.svelte';
  import Journal from './lib/ui/Journal.svelte';
  import Profile from './lib/ui/Profile.svelte';
  import PostStage from './lib/ui/PostStage.svelte';
  import More from './lib/ui/More.svelte';
  import Switcher from './lib/ui/Switcher.svelte';
  import Trash from './lib/ui/Trash.svelte';
  import ThisComputer from './lib/ui/ThisComputer.svelte';
  import UndoToast from './lib/ui/UndoToast.svelte';
  import { selfMember, members, groups } from './lib/data';
  import { core } from './lib/core';
  import { appearance } from './lib/appearance.svelte';
  import Icon from './lib/ui/Icon.svelte';
  import AvatarImage from './lib/ui/AvatarImage.svelte';

  let switching = $state(false);
  addEventListener('keydown', (e: KeyboardEvent) => {
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's' && sync.device && !person) {
      e.preventDefault();
      switching = true;
    }
  });

  const dark = $derived(appearance.dark);

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

  const statusLabel: Record<Status, string> = {
    live: 'Live',
    connecting: 'Connecting…',
    offline: 'Offline · saved on this device',
    'no-device': '',
  };
  // a person account has one member, themself, and no front to switch (D-003)
  const person = $derived(!!projection && !!selfMember(projection));
  const allTabs = [
    { path: '/members', name: 'members', label: 'Members', icon: 'members' },
    { path: '/', name: 'home', label: 'Fronting', icon: 'front' },
    { path: '/chat', name: 'chat', label: 'Chat', icon: 'chat' },
    { path: '/people', name: 'people', label: 'Friends', icon: 'friends' },
    { path: '/more', name: 'more', label: 'More', icon: 'more' },
  ];
  const tabs = $derived(person
    ? [{ path: '/journal', name: 'journal', label: 'Journal', icon: 'journal' }, ...allTabs.filter((t) => !['members', 'home'].includes(t.name))]
    : allTabs);
  const active = $derived(['member', 'profile'].includes(router.route.name) ? 'members' :
    router.route.name === 'stage' ? 'chat' : ['history'].includes(router.route.name) ? 'home' :
    ['insights', 'data', 'trash', 'settings', 'computer', 'search', 'post-stage'].includes(router.route.name) ? 'more' : router.route.name);
  const account = $derived(projection?.rows.account?.[sync.accountId]?.fields);
  const system = $derived(projection?.rows.system?.[sync.accountId]?.fields);
  const accountName = $derived(String(system?.name ?? account?.display_name ?? 'Your system'));
  const current = $derived(projection?.fronts[sync.accountId]?.current ?? []);
  const subjects = $derived(new Map([
    ...members(projection ?? { rows: {}, sets: {}, fronts: {}, opaque: 0 }).map((m) => [m.id, { name: m.display_name ?? m.name, color: m.color, glyph: m.sigils[0] ?? m.name[0], blob: m.avatar_blob }] as const),
    ...groups(projection ?? { rows: {}, sets: {}, fronts: {}, opaque: 0 }).map((g) => [g.id, { name: g.name, color: g.color ?? '#42A397', glyph: g.name[0], blob: g.avatar_blob }] as const),
  ]));
  const frontNames = $derived(current.filter((e) => e.level === 'front').map((e) => subjects.get(e.subject_id)?.name).filter(Boolean).join(' & ') || 'No one fronting');
  const routeTitle = $derived(({ home: person ? 'Home' : 'Fronting', members: 'Members', member: 'Edit member', profile: 'Profile', journal: 'Journal', chat: 'Chat', people: 'Friends', history: 'Front history', insights: 'Insights', data: 'Your data', settings: 'Settings', search: 'Search', trash: 'Trash', more: 'More', computer: 'This computer' } as Record<string, string>)[router.route.name] ?? 'Chorus');
</script>

{#if status === 'no-device'}
  <Onboarding />
{:else if projection}
  <div class="shell" class:chat-page={router.route.name === 'chat'}>
    <a class="skip" href="#main-content" onclick={(e) => { e.preventDefault(); document.getElementById('main-content')?.focus(); }}>Skip to content</a>
    <aside class="app-nav" aria-label="App navigation">
      <a class="identity" href="#/" title={accountName}>
        <span class="system-avatar">{accountName[0]?.toUpperCase()}</span>
        <span class="identity-text"><strong>{accountName}</strong><span>Chorus</span></span>
      </a>
      <nav aria-label="Sections">
        {#each tabs as t (t.name)}
          <a href="#{t.path}" class:on={active === t.name} aria-current={active === t.name ? 'page' : undefined}><Icon name={t.icon} /><span>{t.label}</span></a>
        {/each}
      </nav>
      <nav class="desktop-tools" aria-label="Your tools">
        {#if !person}<a href="#/journal" class:on={router.route.name === 'journal'} aria-current={router.route.name === 'journal' ? 'page' : undefined}><Icon name="journal" />Journal</a>{/if}
        <a href="#/search"><Icon name="search" />Search</a>
        <a href="#/settings"><Icon name="settings" />Settings</a>
      </nav>
      {#if !person}
        <button class="front-summary" onclick={() => (switching = true)} aria-label="Open switcher">
          <span class="front-summary-title"><Icon name="layers" size={16} />Fronting</span>
          <span class="front-stack">{#each current.slice(0, 4) as e (e.subject_id)}{@const m = subjects.get(e.subject_id)}{#if m}{@const c = core.adaptColor(m.color, dark)}<span class="mini-avatar" style="--member: {c.ring}; --member-ink: {c.avatar_ink}"><AvatarImage hash={m.blob} glyph={m.glyph} name={m.name} /></span>{/if}{/each}</span>
          <span class="front-summary-names">{frontNames}</span>
          <span class="shortcut">Switch · Ctrl/⌘ S</span>
        </button>
      {/if}
    </aside>
    <div class="workspace">
      <header class="app-bar">
        <div><strong>{routeTitle}</strong><span class="account-subtitle">{accountName}</span></div>
        <span class="status" data-status={status} role="status"><span class="status-dot"></span>{statusLabel[status]}</span>
        <a class="icon-button" href="#/search" aria-label="Search"><Icon name="search" /></a>
        {#if !person}<button class="icon-button mobile-switch" onclick={() => (switching = true)} aria-label="Open switcher"><Icon name="swap" /></button>{/if}
      </header>
    {#if storageFull}
      <p class="storage-full" role="alert">
        This browser's storage is full, so changes made here aren't saved on this device yet. They
        still sync while you're online: free some space, or keep this tab open until they have.
      </p>
    {/if}
    <main id="main-content" tabindex="-1">
      {#if person && (router.route.name === 'members' || router.route.name === 'history')}
        <Home {projection} {dark} onswitch={() => {}} />
      {:else if router.route.name === 'members'}
        <Members {projection} {dark} />
      {:else if router.route.name === 'member' && router.route.id}
        <MemberEditor {projection} id={router.route.id} {dark} />
      {:else if router.route.name === 'profile' && router.route.id}
        <Profile {projection} id={router.route.id} {dark} />
      {:else if router.route.name === 'journal'}
        <Journal {projection} {dark} />
      {:else if router.route.name === 'post-stage' && router.route.id}
        <PostStage {projection} id={router.route.id} />
      {:else if router.route.name === 'more'}
        <More />
      {:else if router.route.name === 'chat'}
        <Chat {projection} {dark} channelId={router.route.id} focusId={router.route.messageId} />
      {:else if router.route.name === 'search'}
        <Search {projection} />
      {:else if router.route.name === 'history'}
        <History {projection} {dark} />
      {:else if router.route.name === 'insights'}
        <Insights {projection} />
      {:else if router.route.name === 'stage' && router.route.id}
        <Stage {projection} {dark} channelId={router.route.id} />
      {:else if router.route.name === 'data'}
        <DataPage />
      {:else if router.route.name === 'computer'}
        <ThisComputer />
      {:else if router.route.name === 'settings'}
        <Settings {projection} />
      {:else if router.route.name === 'people'}
        <People {projection} />
      {:else if router.route.name === 'trash'}
        <Trash {projection} channelId={router.route.id} />
      {:else}
        <Home {projection} {dark} onswitch={() => (switching = true)} />
      {/if}
    </main>
    </div>
  </div>
  {#if switching}
    <Switcher {projection} {dark} onclose={() => (switching = false)} />
  {/if}
  <UndoToast />
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
  .status { margin-left: auto; display: flex; align-items: center; gap: 6px; font-size: var(--fs-xs); color: var(--ink-3); }
  .status-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--ink-3); flex: none; }
  .status[data-status='live'] .status-dot { background: var(--ok); }
  .mobile-switch { display: none; }
  main { padding: 24px; max-width: 1200px; margin-inline: auto; min-width: 0; outline: none; }
  .chat-page main { padding: 0; max-width: none; }
  .storage-full { margin: 0; padding: 12px 24px; font-size: var(--fs-sm); background: var(--accent-soft); }
  @media (min-width: 1600px) { .shell { grid-template-columns: minmax(240px, calc((100vw - 1320px) / 2)) minmax(0, 1fr); } .app-nav { align-items: stretch; padding-left: max(16px, calc((100vw - 1560px) / 2)); } }
  @media (max-width: 1023px) {
    .shell { display: block; padding-bottom: calc(72px + env(safe-area-inset-bottom)); }
    .app-nav { position: fixed; inset: auto 0 0; z-index: 10; height: auto; padding: 6px 8px calc(6px + env(safe-area-inset-bottom)); background: var(--surface); border: 0; border-top: 1px solid var(--line); display: block; }
    .identity, .desktop-tools, .front-summary { display: none; }
    nav { display: flex; justify-content: space-around; gap: 0; }
    nav a { flex: 1; flex-direction: column; gap: 4px; padding: 6px 2px; font-size: 11px; border-radius: 12px; min-width: 0; min-height: 56px; }
    nav a.on { background: transparent; color: var(--accent); }
    .app-bar { min-height: 72px; padding: 10px 16px; gap: 4px; }
    .app-bar .status { font-size: 0; gap: 0; }
    .app-bar .status-dot { width: 7px; height: 7px; margin-right: 10px; }
    .mobile-switch { display: inline-grid; }
    main { padding: 16px; }
  }
  :global(body.capture) .app-nav, :global(body.capture) .app-bar, :global(body.capture .toast) { display: none !important; }
  :global(body.capture) .shell { display: block; padding: 0; }
</style>
