<script lang="ts">
  // Settings (Figma F9.2-F9.4, F10.6): a list of pages on the left and the page beside it on wide
  // screens; on a phone-wide one the list is a page of its own (Android's More > Settings).
  import { onMount } from 'svelte';
  import Icon from './Icon.svelte';
  import FormCard from './kit/FormCard.svelte';
  import SectionLabel from './kit/SectionLabel.svelte';
  import ValueRow from './kit/ValueRow.svelte';
  import Trash from './Trash.svelte';
  import ThisComputer from './ThisComputer.svelte';
  import SyncIssuesPage from './settings/SyncIssuesPage.svelte';
  import ThisDevice from './ThisDevice.svelte';
  import AccountPage from './settings/AccountPage.svelte';
  import AdvancedPage from './settings/AdvancedPage.svelte';
  import ApiTokensPage from './settings/ApiTokensPage.svelte';
  import AppearancePage from './settings/AppearancePage.svelte';
  import DevicesPage from './settings/DevicesPage.svelte';
  import NotificationsPage from './settings/NotificationsPage.svelte';
  import ServerHealthPage from './settings/ServerHealthPage.svelte';
  import SharingPage from './settings/SharingPage.svelte';
  import WebhooksPage from './settings/WebhooksPage.svelte';
  import YourDataPage from './settings/YourDataPage.svelte';
  import { homeStatus } from '../home';
  import { selfMember } from '../data';
  import { groupedPages, pageFor, visiblePages } from '../settings-pages';
  import { sync, type Projection } from '../sync/client';

  let { projection, page }: { projection: Projection; page?: string } = $props();
  let home = $state(false);
  onMount(() => { void homeStatus().then((s) => (home = !!s)); });
  const query = window.matchMedia('(min-width: 900px)');
  let wide = $state(query.matches);
  $effect(() => {
    const on = () => (wide = query.matches);
    query.addEventListener('change', on);
    return () => query.removeEventListener('change', on);
  });

  const issues = $derived(projection ? sync.syncIssues().length : 0);
  const pages = $derived(visiblePages({ person: !!selfMember(projection), admin: !!sync.device?.is_admin, home, issues }));
  const current = $derived(pageFor(page, pages));
  // on a narrow screen no page chosen means the list
  const showList = $derived(!wide && !page);
</script>

<div class="settings" class:wide>
  {#if wide}
    <nav class="list" aria-label="Settings">
      <h1>Settings</h1>
      {#each groupedPages(pages) as g (g.group)}
        <h2>{g.group}</h2>
        {#each g.pages as p (p.id)}
          <a href="#/settings/{p.id}" class:on={current === p.id} aria-current={current === p.id ? 'page' : undefined}><Icon name={p.icon} size={20} /><span>{p.label}</span></a>
        {/each}
      {/each}
    </nav>
  {:else if showList}
    <nav class="hub" aria-label="Settings">
      {#each groupedPages(pages) as g (g.group)}
        <SectionLabel label={g.group} />
        <FormCard>{#each g.pages as p (p.id)}<ValueRow icon={p.icon} label={p.label} href="#/settings/{p.id}" />{/each}</FormCard>
      {/each}
    </nav>
  {/if}
  {#if wide || !showList}
    <div class="body">
      {#if !wide}<a class="back" href="#/settings"><Icon name="back" size={18} />Settings</a>{/if}
      {#if current === 'appearance'}<AppearancePage {projection} />
      {:else if current === 'notifications'}<NotificationsPage {projection} />
      {:else if current === 'sharing'}<SharingPage {projection} />
      {:else if current === 'advanced'}<AdvancedPage {projection} />
      {:else if current === 'data'}<YourDataPage />
      {:else if current === 'tokens'}<ApiTokensPage />
      {:else if current === 'webhooks'}<WebhooksPage />
      {:else if current === 'trash'}<Trash {projection} />
      {:else if current === 'devices'}<DevicesPage />
      {:else if current === 'issues'}<SyncIssuesPage {projection} />
      {:else if current === 'device'}<ThisDevice />
      {:else if current === 'computer'}<ThisComputer />
      {:else if current === 'health'}<ServerHealthPage />
      {:else}<AccountPage {projection} />{/if}
    </div>
  {/if}
</div>

<style>
  .settings { display: grid; gap: 16px; }
  .settings.wide { grid-template-columns: 240px minmax(0, 1fr); gap: 32px; align-items: start; }
  .list { display: grid; gap: 2px; align-content: start; max-width: 560px; }
  .wide .list { position: sticky; top: 24px; }
  h1 { margin: 0 0 8px; font-size: 22px; }
  h2 { margin: 14px 4px 4px; color: var(--ink-3); font-size: 12px; font-weight: 500; }
  .list a { display: flex; align-items: center; gap: 12px; min-height: 44px; padding: 8px 12px; border-radius: 12px; color: var(--ink-2); text-decoration: none; font-size: 15px; }
  .list a span { flex: 1; }
  .list a.on { background: var(--surface-2); color: var(--ink); }
  .list a.on :global(.icon:first-child) { color: var(--accent); }
  .list a:hover { background: var(--surface); }
  .hub { display: grid; gap: 2px; max-width: 560px; }
  .body { min-width: 0; }
  .back { display: inline-flex; align-items: center; gap: 4px; margin-bottom: 8px; color: var(--accent); text-decoration: none; font-size: 14px; }
</style>
