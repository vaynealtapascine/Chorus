<script lang="ts">
  import { router } from './lib/router.svelte';
  import { sync, type Projection, type Status } from './lib/sync/client';
  import History from './lib/ui/History.svelte';
  import Home from './lib/ui/Home.svelte';
  import MemberEditor from './lib/ui/MemberEditor.svelte';
  import Members from './lib/ui/Members.svelte';
  import Onboarding from './lib/ui/Onboarding.svelte';
  import Switcher from './lib/ui/Switcher.svelte';
  import UndoToast from './lib/ui/UndoToast.svelte';

  let switching = $state(false);
  addEventListener('keydown', (e: KeyboardEvent) => {
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's' && sync.device) {
      e.preventDefault();
      switching = true;
    }
  });

  const media = window.matchMedia('(prefers-color-scheme: dark)');
  let dark = $state(media.matches);
  media.addEventListener('change', (e) => (dark = e.matches));

  let status: Status = $state(sync.status);
  let projection: Projection | null = $state(sync.projection());
  $effect(() =>
    sync.subscribe(() => {
      status = sync.status;
      projection = sync.projection();
    }),
  );

  const statusLabel: Record<Status, string> = {
    live: 'Live',
    connecting: 'Connecting…',
    offline: 'Offline · saved on this device',
    'no-device': '',
  };
  const tabs = [
    { path: '/', name: 'home', label: 'Home' },
    { path: '/members', name: 'members', label: 'Members' },
    { path: '/history', name: 'history', label: 'History' },
  ];
  const active = $derived(router.route.name === 'member' ? 'members' : router.route.name);
</script>

{#if status === 'no-device'}
  <Onboarding />
{:else if projection}
  <div class="shell">
    <header>
      <a class="brand display" href="#/">Chorus</a>
      <nav aria-label="Sections">
        {#each tabs as t (t.name)}
          <a href="#{t.path}" class:on={active === t.name} aria-current={active === t.name ? 'page' : undefined}>{t.label}</a>
        {/each}
      </nav>
      <span class="status" data-status={status}>{statusLabel[status]}</span>
    </header>
    <main>
      {#if router.route.name === 'members'}
        <Members {projection} {dark} />
      {:else if router.route.name === 'member' && router.route.id}
        <MemberEditor {projection} id={router.route.id} {dark} />
      {:else if router.route.name === 'history'}
        <History {projection} {dark} />
      {:else}
        <Home {projection} {dark} onswitch={() => (switching = true)} />
      {/if}
    </main>
  </div>
  {#if switching}
    <Switcher {projection} {dark} onclose={() => (switching = false)} />
  {/if}
  <UndoToast />
{/if}

<style>
  .shell {
    max-width: 720px;
    margin: 0 auto;
    padding: var(--s-5) var(--s-4) calc(var(--s-12) + 56px);
  }
  header {
    display: flex;
    align-items: baseline;
    gap: var(--s-6);
    margin-bottom: var(--s-6);
  }
  .brand {
    font-size: var(--fs-2xl);
    color: var(--ink);
    text-decoration: none;
  }
  nav {
    display: flex;
    gap: var(--s-4);
  }
  nav a {
    color: var(--ink-2);
    text-decoration: none;
    font-weight: 500;
    padding-bottom: 2px;
    border-bottom: 2px solid transparent;
  }
  nav a.on {
    color: var(--ink);
    border-color: var(--accent);
  }
  .status {
    margin-left: auto;
    font-size: var(--fs-xs);
    color: var(--ink-3);
  }
  .status[data-status='live'] {
    color: var(--ok);
  }
  /* phones: bottom navigation */
  @media (max-width: 640px) {
    nav {
      position: fixed;
      inset: auto 0 0 0;
      justify-content: space-around;
      background: var(--surface);
      border-top: 1px solid var(--line);
      padding: var(--s-3) var(--s-4) calc(var(--s-3) + env(safe-area-inset-bottom));
      z-index: 10;
    }
    nav a {
      border: 0;
    }
    nav a.on {
      color: var(--accent);
    }
  }
</style>
