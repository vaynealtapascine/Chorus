<script lang="ts">
  // Install Chorus (Figma F10.8): a small dismissable card, shown only where the browser can install it.
  import Icon from '../Icon.svelte';
  import { dismissInstall, install, installKind } from '../../install-prompt.svelte';
  const kind = $derived(installKind());
</script>

{#if kind}
  <div class="install">
    <span class="tile"><Icon name="home" size={20} /></span>
    <div class="text"><strong>Install Chorus</strong>{#if kind === 'ios'}<span>Share, then Add to Home Screen</span>{/if}</div>
    {#if kind === 'prompt'}<button class="go" onclick={install}>Install</button>{/if}
    <button class="x" aria-label="Not now" onclick={dismissInstall}><Icon name="close" size={18} /></button>
  </div>
{/if}

<style>
  .install { display: flex; align-items: center; gap: var(--s-3); padding: 10px var(--s-2) 10px var(--s-3); border-radius: var(--r-md); background: var(--surface-2); }
  .tile { display: grid; place-items: center; width: 40px; height: 40px; border-radius: var(--r-row); background: var(--accent-soft); color: var(--accent); flex: none; }
  .text { flex: 1; min-width: 0; display: grid; }
  .text span { color: var(--ink-3); font-size: var(--fs-sm); }
  .go { min-height: 36px; padding: 0 var(--s-4); border: 0; border-radius: var(--r-full); background: var(--accent); color: var(--on-accent); font: inherit; font-size: 14px; font-weight: 600; cursor: pointer; }
  .x { display: grid; place-items: center; width: 36px; height: 36px; border: 0; border-radius: 50%; background: none; color: var(--ink-3); cursor: pointer; }
</style>
