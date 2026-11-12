<script lang="ts">
  import LinkDevice from '../LinkDevice.svelte';
  import PageHeader from '../kit/PageHeader.svelte';
</script>

<section class="page">
  <PageHeader title="Devices" />
  <LinkDevice />
</section>

<style>
  .page { display: grid; gap: var(--s-3); max-width: 720px; align-content: start; }
</style>
