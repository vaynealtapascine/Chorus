<script lang="ts">
  // Exports (Figma F9.2): the change log, the tables, a database copy, and the full export with files.
  import Button from '../kit/Button.svelte';
  import ExportBundle from '../ExportBundle.svelte';
  import Icon from '../Icon.svelte';
  import FormNote from '../kit/FormNote.svelte';
  import MenuRow from '../kit/MenuRow.svelte';
  import MenuSheet from '../kit/MenuSheet.svelte';
  import PageHeader from '../kit/PageHeader.svelte';
  import { apiBase } from '../../sync/device';
  import { apiFetch } from '../../http';
  import { sync } from '../../sync/client';

  const CSV_EXPORTS = ['members', 'groups', 'switches', 'front_intervals', 'front_daily', 'messages', 'posts'];
  let error = $state('');
  let tables = $state(false);

  async function download(path: string, filename: string) {
    error = '';
    try {
      const response = await apiFetch(`${apiBase()}${path}`, { headers: { authorization: `Bearer ${sync.device?.session ?? ''}` } });
      if (!response.ok) throw new Error(`Export failed (HTTP ${response.status})`);
      const url = URL.createObjectURL(await response.blob());
      const link = document.createElement('a');
      link.href = url;
      link.download = filename;
      link.click();
      setTimeout(() => URL.revokeObjectURL(url), 60_000);
    } catch (err) {
      error = err instanceof Error ? err.message : String(err);
    }
  }
  const rows = [
    { icon: 'doc', title: 'Change log', sub: 'JSON lines · every change, for scripts', go: () => download('/exports/ops.jsonl', 'ops.jsonl') },
    { icon: 'journal', title: 'Tables', sub: 'CSV · one file per view', go: () => (tables = true) },
    { icon: 'archive', title: 'Database copy', sub: 'SQLite · open it with any SQL tool', go: () => download('/exports/account.sqlite', 'account.sqlite') },
  ];
</script>

<section class="page">
  <PageHeader title="Your data" sub="Download everything, in formats other tools can read" />
  <div class="card" role="group" aria-label="Exports">
    <h2><span class="tile"><Icon name="forward" size={18} /></span>Exports</h2>
    {#each rows as r (r.title)}
      <div class="row">
        <span class="tile"><Icon name={r.icon} size={20} /></span>
        <div class="titles"><strong>{r.title}</strong><span>{r.sub}</span></div>
        <Button size="sm" class="pill" onclick={r.go}>Export</Button>
      </div>
    {/each}
  </div>
  <ExportBundle />
  <FormNote icon="lock" text="Exports hold only what this account can read. Nothing from friends’ private parts is included." />
  {#if error}<p class="error" role="alert">{error}</p>{/if}
</section>

{#if tables}
  <MenuSheet title="Tables" onclose={() => (tables = false)}>
    {#each CSV_EXPORTS as table (table)}
      <MenuRow label={table.replaceAll('_', ' ')} onclick={() => { tables = false; void download(`/exports/csv/${table}`, `${table}.csv`); }} />
    {/each}
  </MenuSheet>
{/if}

<style>
  .page { display: grid; gap: var(--s-3); max-width: 720px; align-content: start; }
  .card { display: grid; gap: 14px; padding: 14px; border-radius: var(--r-md); background: var(--surface-2); }
  h2 { display: flex; align-items: center; gap: 10px; margin: 0; font-size: var(--fs-base); }
  .row { display: flex; align-items: center; gap: var(--s-3); }
  .tile { display: grid; place-items: center; width: 40px; height: 40px; border-radius: var(--r-row); background: var(--accent-soft); color: var(--accent); flex: none; }
  h2 .tile { width: 32px; height: 32px; border-radius: var(--r-sm); }
  .titles { flex: 1; min-width: 0; display: grid; }
  .titles strong { font-size: var(--fs-md); }
  .titles span { color: var(--ink-3); font-size: var(--fs-sm); }
  .error { margin: 0; color: var(--danger); }
</style>
