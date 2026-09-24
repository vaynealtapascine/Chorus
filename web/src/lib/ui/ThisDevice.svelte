<script lang="ts">
  // "Keep everything on this device" and "Sync everything now" (D-070, CLIENTS.md §4.3).
  import { onMount } from 'svelte';
  import { sync } from '../sync/client';
  import { fillFiles, installed, keepEverything, setKeepEverything, spaceUsed } from '../sync/keep';

  let keep = $state(false);
  let running = $state(false);
  let checked = $state<{ done: number; total: number } | null>(null);
  let files = $state<{ done: number; total: number; missing: number } | null>(null);
  let space = $state<{ usage: number; quota: number } | null>(null);
  let error = $state('');
  let finished = $state(false);

  const size = (bytes: number) =>
    bytes >= 1024 ** 3 ? `${(bytes / 1024 ** 3).toFixed(1)} GB` : `${Math.max(0.1, bytes / 1024 ** 2).toFixed(1)} MB`;
  const refreshSpace = async () => { space = await spaceUsed(); };

  onMount(() => {
    void keepEverything().then((on) => (keep = on));
    void refreshSpace();
  });

  async function toggle(on: boolean) {
    keep = on;
    await setKeepEverything(on);
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
</script>

<section class="card" aria-label="This device">
  <h2>This device</h2>
  <label class="check">
    <input type="checkbox" checked={keep} onchange={(e) => toggle(e.currentTarget.checked)} />
    Keep everything on this device
  </label>
  <span class="hint">
    All your history stays here either way; this also keeps photos, files (up to 20 MB each), avatars and emoji,
    so they show without the server. {installed() ? 'On by default in the installed app.' : 'Off by default in a browser tab.'}
  </span>
  <div class="row">
    <button class="primary" onclick={syncEverything} disabled={running || sync.status !== 'live'}>
      {running ? 'Syncing…' : 'Sync everything now'}
    </button>
    {#if sync.status !== 'live'}<span class="hint">Needs the server.</span>{/if}
  </div>
  {#if checked && checked.total}
    <span role="status">Checked {checked.done} of {checked.total} {checked.total === 1 ? 'place' : 'places'} with the server{files ? '' : running ? '…' : '.'}</span>
  {/if}
  {#if files}
    <span role="status">Files: {files.done} of {files.total}{files.missing ? ` (${files.missing} not available)` : ''}</span>
  {/if}
  {#if finished}<span role="status">Everything is on this device.</span>{/if}
  {#if error}<span class="error" role="alert">{error}</span>{/if}
  {#if space}<span class="hint">This app uses {size(space.usage)} on this device{space.quota ? ` of ${size(space.quota)} available` : ''}.</span>{/if}
</section>

<style>
  .card { display: grid; gap: var(--s-2); padding: var(--s-4); background: var(--surface); border: 1px solid var(--line); border-radius: var(--r-md); }
  h2 { margin: 0; }
  .check { display: flex; gap: var(--s-2); align-items: center; }
  .row { display: flex; gap: var(--s-3); align-items: center; }
  .hint { color: var(--ink-3); font-size: var(--fs-sm); margin: 0; }
  .error { color: var(--danger); }
  .primary { font: inherit; justify-self: start; background: var(--accent); color: var(--surface); border: 0; border-radius: var(--r-full); padding: var(--s-2) var(--s-4); cursor: pointer; }
  .primary:disabled { opacity: 0.5; cursor: default; }
</style>
