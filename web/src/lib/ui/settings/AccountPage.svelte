<script lang="ts">
  import FormCard from '../kit/FormCard.svelte';
  import PageHeader from '../kit/PageHeader.svelte';
  import ValueRow from '../kit/ValueRow.svelte';
  import { selfMember } from '../../data';
  import type { Projection } from '../../sync/client';

  let { projection }: { projection: Projection } = $props();
  const self = $derived(selfMember(projection));
</script>

<section class="page">
  <PageHeader title="Account" />
  <FormCard>
    {#if self}<ValueRow icon="user" label="My profile" href="#/profile/{self.id}" />{/if}
    <ValueRow icon="users" label="Devices" href="#/settings/devices" />
    <ValueRow icon="sliders" label="Advanced" href="#/settings/advanced" />
  </FormCard>
</section>

<style>
  .page { display: grid; gap: var(--s-3); max-width: 720px; align-content: start; }
</style>
