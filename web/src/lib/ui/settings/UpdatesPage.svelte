<script lang="ts">
  import FormCard from '../kit/FormCard.svelte';
  import InstallCard from '../kit/InstallCard.svelte';
  import PageHeader from '../kit/PageHeader.svelte';
  import ValueRow from '../kit/ValueRow.svelte';
  import { checkForUpdate, installUpdate, updates } from '../../updates.svelte';
</script>

<section class="page">
  <PageHeader title="Updates" sub={updates.ready ? 'A new version is ready' : 'Up to date'} />
  <FormCard>
    {#if updates.ready}
      <ValueRow icon="swap" label="Install the new version" onclick={installUpdate} />
    {:else}
      <ValueRow icon="history" label={updates.checking ? 'Checking…' : 'Check for updates'} disabled={updates.checking} onclick={checkForUpdate} />
    {/if}
  </FormCard>
  <InstallCard />
</section>

<style>
  .page { display: grid; gap: var(--s-3); max-width: 720px; align-content: start; }
</style>
