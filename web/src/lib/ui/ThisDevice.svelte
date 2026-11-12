<script lang="ts">
  // On this device (Figma F9.6; D-070, CLIENTS.md §4.3): what is kept offline, sync now, free up space.
  import { onMount } from 'svelte';
  import { sync } from '../sync/client';
  import { blobCacheBytes, forgetBlobs } from '../sync/blobs';
  import { fillFiles, installed, keepEverything, setKeepEverything, spaceUsed } from '../sync/keep';
  import ConfirmSheet from './kit/ConfirmSheet.svelte';
  import FormCard from './kit/FormCard.svelte';
  import PageHeader from './kit/PageHeader.svelte';
  import SectionLabel from './kit/SectionLabel.svelte';
  import Switch from './kit/Switch.svelte';
  import ValueRow from './kit/ValueRow.svelte';

  let keep = $state(false);
  let running = $state(false);
  let checked = $state<{ done: number; total: number } | null>(null);
  let files = $state<{ done: number; total: number; missing: number } | null>(null);
  let space = $state<{ usage: number; quota: number } | null>(null);
  let downloaded = $state(0);
  let error = $state('');
  let finished = $state(false);
  let asking = $state(false);

  const size = (bytes: number) =>
    bytes >= 1024 ** 3 ? `${(bytes / 1024 ** 3).toFixed(1)} GB` : `${Math.max(0.1, bytes / 1024 ** 2).toFixed(1)} MB`;
  async function refreshSpace() {
    space = await spaceUsed();
    downloaded = await blobCacheBytes();
  }
  const other = $derived(space ? Math.max(0, space.usage - downloaded) : 0);
  const percent = (part: number) => (space?.quota ? Math.max(1, (part / space.quota) * 100) : 0);

  onMount(() => {
    void keepEverything().then((on) => (keep = on));
    void refreshSpace();
  });

  async function toggle(on: boolean) {
    keep = on;
    await setKeepEverything(on);
    sync.setKeepAll(on);
  }

  async function syncEverything() {
    running = true;
    error = '';
    finished = false;
    files = null;
    checked = { done: 0, total: 0 };
    try {
      await sync.recheckAll((done, total) => (checked = { done, total }));
      const p = sync.projection();
      if (keep && p) await fillFiles(p, sync.device, (progress) => (files = progress));
      finished = true;
    } catch (e) {
      error = e instanceof Error ? e.message : String(e);
    } finally {
      running = false;
      await refreshSpace();
    }
  }
  async function clear() {
    asking = false;
    await forgetBlobs();
    await refreshSpace();
  }
  const issues = sync.syncIssues().length;
</script>

<section class="page" aria-label="This device">
  <PageHeader title="This device" sub={installed() ? 'Installed app' : 'Browser'} />
  <SectionLabel label="Offline" />
  <FormCard>
    <Switch label="Keep everything on this device" sub="Read old chats and posts without a connection" checked={keep} onchange={toggle} />
    <ValueRow icon="swap" label={running ? 'Syncing…' : 'Sync everything now'} disabled={running || sync.status !== 'live'} onclick={syncEverything} />
  </FormCard>
  {#if checked && checked.total}
    <p class="note" role="status">Checked {checked.done} of {checked.total} {checked.total === 1 ? 'place' : 'places'} with the server{files ? '' : running ? '…' : '.'}</p>
  {/if}
  {#if files}<p class="note" role="status">Files: {files.done} of {files.total}{files.missing ? ` (${files.missing} not available)` : ''}</p>{/if}
  {#if finished}<p class="note" role="status">Everything is on this device.</p>{/if}
  {#if error}<p class="note error" role="alert">{error}</p>{/if}
  {#if space}
    <div class="usage">
      <div class="head"><strong>Space used</strong><span>{size(space.usage)}{space.quota ? ` of ${size(space.quota)}` : ''}</span></div>
      <div class="bar" aria-hidden="true">
        <span class="msg" style="width: {percent(other)}%"></span><span class="files" style="width: {percent(downloaded)}%"></span>
      </div>
      <p class="note">This app uses {size(space.usage)} on this device{space.quota ? ` of ${size(space.quota)} available` : ''}.</p>
    </div>
  {/if}
  <SectionLabel label="Free up space" />
  <FormCard>
    <ValueRow icon="delete" label="Clear downloaded files" value={downloaded ? `Frees about ${size(downloaded)}` : null} disabled={!downloaded} onclick={() => (asking = true)} />
  </FormCard>
  {#if issues}
    <FormCard><ValueRow icon="target" label="Sync issues" value={String(issues)} href="#/settings/issues" /></FormCard>
  {/if}
</section>

{#if asking}
  <ConfirmSheet title="Clear downloaded files?" message="Frees about {size(downloaded)}. Messages stay; files download again when you open them." confirm="Clear" onconfirm={clear} onclose={() => (asking = false)} />
{/if}

<style>
  .page { display: grid; gap: var(--s-3); max-width: 720px; align-content: start; }
  .note { margin: 0; color: var(--ink-3); font-size: var(--fs-sm); padding: 0 var(--s-1); }
  .note.error { color: var(--danger); }
  .usage { display: grid; gap: var(--s-2); padding: 14px; border-radius: var(--r-md); background: var(--surface-2); }
  .head { display: flex; justify-content: space-between; gap: var(--s-3); }
  .head span { color: var(--ink-3); font-size: var(--fs-sm); }
  .bar { display: flex; height: 8px; border-radius: 4px; background: var(--surface-3); overflow: hidden; }
  .msg { background: var(--accent); }
  .files { background: var(--sun); }
</style>
