<script lang="ts">
  import { sync, type Projection, type Status } from './lib/sync/client';
  import Home from './lib/ui/Home.svelte';
  import Onboarding from './lib/ui/Onboarding.svelte';

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
</script>

{#if status === 'no-device'}
  <Onboarding />
{:else if projection}
  <Home {projection} {status} {dark} />
{/if}
