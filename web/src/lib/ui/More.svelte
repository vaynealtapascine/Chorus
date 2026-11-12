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
      { path: 'members', title: 'Members', icon: 'members' },
      { path: 'notifications', title: 'Notifications', icon: 'bell' },
      { path: 'history', title: 'Front history', icon: 'history' },
      { path: 'insights', title: 'Fronting insights', icon: 'layers' },
      { path: 'people', title: 'Friends', icon: 'friends' },
      { path: 'settings', title: 'Settings', icon: 'settings' },
      { path: 'search', title: 'Search', icon: 'search' },
      { path: 'data', title: 'Your data', icon: 'lock' },
      { path: 'trash', title: 'Trash', icon: 'history' },
    ] as item (item.path)}
      <a href="#/{item.path}"><span class="destination-icon"><Icon name={item.icon} /></span><span class="text"><strong>{item.title}</strong></span><Icon name="chevron" size={16} /></a>
    {/each}
    {#if home}<a href="#/computer"><span class="destination-icon"><Icon name="settings" /></span><span class="text"><strong>This computer</strong></span><Icon name="chevron" size={16} /></a>{/if}
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
  a > :global(.icon) { color: var(--ink-3); }
</style>
