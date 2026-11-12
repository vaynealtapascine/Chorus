<script lang="ts">
  // The strip under the app bar while changes can't reach the server (F2.5 away, F10.3 asleep with
  // Retry). It waits a moment before showing, so a quick reconnect doesn't flash it.
  import { connection, connectionText, type Connection } from '../../notices';
  import { sync } from '../../sync/client';
  import { homeStatus } from '../../home';
  import Icon from '../Icon.svelte';

  let { status }: { status: string } = $props();

  let failures = $state(0);
  let network = $state(typeof navigator === 'undefined' ? true : navigator.onLine);
  let home = $state(false);
  let last = '';
  $effect(() => {
    const s = status;
    if (s === 'offline' && last !== 'offline') failures += 1;
    if (s === 'live') failures = 0;
    last = s;
  });
  $effect(() => {
    const on = () => (network = true);
    const off = () => (network = false);
    addEventListener('online', on);
    addEventListener('offline', off);
    return () => { removeEventListener('online', on); removeEventListener('offline', off); };
  });
  $effect(() => { homeStatus().then((h) => (home = !!h)); });

  const kind = $derived(connection(status, failures, network));
  let shown: Connection = $state('none');
  $effect(() => {
    const k = kind;
    if (k === 'none' || shown !== 'none') { shown = k; return; }
    const t = setTimeout(() => (shown = k), 1500);
    return () => clearTimeout(t);
  });
  const text = $derived(connectionText(shown, home));
</script>

{#if text}
  <div class="banner" class:asleep={shown === 'asleep'} role="status">
    <Icon name={shown === 'asleep' ? 'moon' : 'cloud-off'} size={18} />
    <span>{text}</span>
    {#if shown === 'asleep'}<button onclick={() => sync.reconnectNow()}>Retry</button>{/if}
  </div>
{/if}

<style>
  .banner { display: flex; align-items: center; gap: 10px; padding: 10px var(--s-4); font-size: var(--fs-sm); color: var(--ink);
    background: color-mix(in srgb, var(--sun) 18%, transparent); }
  .banner.asleep { background: color-mix(in srgb, var(--danger) 15%, transparent); }
  span { flex: 1; min-width: 0; }
  button { border: 0; background: none; font: inherit; font-weight: 600; color: var(--danger); cursor: pointer; padding: 6px 10px; min-height: 36px; }
</style>
