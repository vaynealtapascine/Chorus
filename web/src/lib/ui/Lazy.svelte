<script lang="ts">
  // A screen most visits never open, fetched the first time it is shown, so the script that opens the
  // app stays inside SPEC §9's budget (D-156). main.ts fetches them all once the page is idle, and the
  // service worker keeps them, so the wait only shows on a cold first visit. A failed fetch is a new
  // version having replaced the files under an open page, or no network before they were saved.
  import type { Component } from 'svelte';
  import EmptyState from './kit/EmptyState.svelte';

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  let { load, ...rest }: { load: () => Promise<{ default: Component<any> }>; [prop: string]: unknown } = $props();
  const screen = $derived(load());
</script>

{#await screen then loaded}
  <loaded.default {...rest} />
{:catch}
  <EmptyState icon="cloud-off" label="Couldn't open this" action="Reload" onaction={() => location.reload()} />
{/await}
