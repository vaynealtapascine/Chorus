<script lang="ts">
  // Chorus Home: *This computer* only shows in the browser on the PC Chorus runs on (home.ts)
  import Icon from './Icon.svelte';
  import { homeStatus } from '../home';
  let home = $state(false);
  homeStatus().then((s) => (home = !!s));
</script>

<section class="more">
  <h1 class="display">More</h1>
  <nav aria-label="More pages">
    {#each [
      { path: 'members', title: 'Members', description: 'Your members and profiles', icon: 'members' },
      { path: 'notifications', title: 'Notifications', description: 'Mentions, replies and friends’ news', icon: 'bell' },
      { path: 'history', title: 'Front history', description: 'Look back, amend or log a switch', icon: 'history' },
      { path: 'insights', title: 'Fronting insights', description: 'History, patterns and exports', icon: 'layers' },
      { path: 'people', title: 'Friends', description: 'Who’s around, requests and sharing', icon: 'friends' },
      { path: 'settings', title: 'Settings', description: 'Appearance, notifications and preferences', icon: 'settings' },
      { path: 'search', title: 'Search', description: 'Messages, posts and switches on this device', icon: 'search' },
      { path: 'data', title: 'Your data', description: 'Import, export, API tokens and devices', icon: 'lock' },
      { path: 'trash', title: 'Trash', description: 'Find and restore deleted items', icon: 'history' },
    ] as item (item.path)}
      <a href="#/{item.path}"><span class="destination-icon"><Icon name={item.icon} /></span><span class="text"><strong>{item.title}</strong><span>{item.description}</span></span><Icon name="chevron" size={16} /></a>
    {/each}
    {#if home}<a href="#/computer"><span class="destination-icon"><Icon name="settings" /></span><span class="text"><strong>This computer</strong><span>Chorus Home and backups</span></span><Icon name="chevron" size={16} /></a>{/if}
  </nav>
</section>
<style>
  .more { display: grid; gap: 20px; max-width: 720px; margin: auto; }
  h1 { font-size: var(--fs-xl); }
  nav { display: grid; gap: 10px; }
  a { display: flex; gap: 14px; align-items: center; padding: 16px; min-height: 80px; background: var(--surface); color: var(--ink); text-decoration: none; border-radius: 12px; }
  a:hover { background: var(--surface-2); }
  .destination-icon { display: grid; place-items: center; width: 40px; height: 40px; flex: none; border-radius: 50%; color: var(--accent); background: var(--accent-soft); }
  .text { display: grid; gap: 4px; flex: 1; min-width: 0; }
  .text strong { font-size: var(--fs-md); }
  .text > span { color: var(--ink-3); font-size: var(--fs-xs); }
  a > :global(.icon) { color: var(--ink-3); }
</style>
