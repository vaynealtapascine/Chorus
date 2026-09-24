<script lang="ts">
  // "Prepare a full export" (D-068, API.md §4): the server builds a zip of this account's
  // history, tables and files in the background; this polls it every 2 s while the page is open.
  import { onMount } from 'svelte';
  import { apiBase } from '../sync/device';
  import { apiFetch } from '../http';
  import { sync } from '../sync/client';

  interface Job {
    id: string; status: 'queued' | 'running' | 'done' | 'failed' | 'cancelled' | 'expired';
    phase: 'ops' | 'csv' | 'blobs' | 'zip' | null; done: number; total: number; bytes: number;
    error: string | null; file_name: string | null; result_url: string | null; expires_at: number | null;
  }
  const PHASE = { ops: 'Your history', csv: 'Tables', blobs: 'Files', zip: 'Finishing' } as const;

  let job = $state<Job | null>(null);
  let error = $state('');
  let timer: ReturnType<typeof setTimeout> | null = null;
  const running = $derived(job?.status === 'queued' || job?.status === 'running');
  const mb = (bytes: number) => `${(bytes / 1024 / 1024).toFixed(1)} MB`;

  async function api(path: string, init: RequestInit = {}): Promise<Response> {
    return apiFetch(`${apiBase()}${path}`, {
      ...init,
      headers: { 'content-type': 'application/json', authorization: `Bearer ${sync.device?.session ?? ''}` },
    });
  }

  async function poll(id: string) {
    if (timer) clearTimeout(timer);
    try {
      const r = await api(`/jobs/${id}`);
      if (!r.ok) throw new Error(`HTTP ${r.status}`);
      job = await r.json();
      if (running) timer = setTimeout(() => poll(id), 2000);
    } catch (e) {
      error = e instanceof Error ? e.message : String(e);
      timer = setTimeout(() => poll(id), 5000);
    }
  }

  onMount(() => {
    void api('/exports/latest').then(async (r) => {
      if (r.status !== 200) return;
      job = await r.json();
      if (job && (job.status === 'queued' || job.status === 'running')) void poll(job.id);
    }).catch(() => {});
    return () => { if (timer) clearTimeout(timer); };
  });

  async function start() {
    error = '';
    const r = await api('/exports', { method: 'POST', body: JSON.stringify({ kind: 'full' }) });
    const body = await r.json().catch(() => ({}));
    if (!r.ok) { error = body?.error?.message ?? `HTTP ${r.status}`; return; }
    void poll(body.id);
  }

  async function stop() {
    if (!job) return;
    await api(`/jobs/${job.id}`, { method: 'DELETE' });
    await poll(job.id);
  }
</script>

<div class="bundle">
  <strong>Full export with files</strong>
  <span class="hint">Everything this account wrote — history, tables and your photos and files — in one zip, prepared on the server. It stays for a day.</span>
  {#if running && job}
    <span role="status">
      {job.status === 'queued' ? 'Waiting for another export to finish…' : `${PHASE[job.phase ?? 'ops']}: ${job.done} of ${job.total} · ${mb(job.bytes)}`}
    </span>
    <progress max={job.total || 1} value={job.done}></progress>
    <button class="ghost" onclick={stop}>Cancel</button>
  {:else}
    {#if job?.status === 'done' && job.result_url}
      <a class="download" href={`${location.origin}${job.result_url}`} download={job.file_name ?? 'chorus-export.zip'}>
        Download {job.file_name} ({mb(job.bytes)})
      </a>
      {#if job.expires_at}<span class="hint">Until {new Date(job.expires_at).toLocaleString()}.</span>{/if}
      <button class="ghost" onclick={stop}>Delete it now</button>
    {:else if job?.status === 'failed'}
      <span class="error" role="alert">The export didn't finish: {job.error}</span>
    {/if}
    <button class="ghost" onclick={start} disabled={sync.status !== 'live'}>
      {job?.status === 'done' ? 'Prepare a new one' : 'Prepare a full export'}
    </button>
  {/if}
  {#if error}<span class="error" role="alert">{error}</span>{/if}
</div>

<style>
  .bundle { display: grid; gap: var(--s-1); padding-top: var(--s-2); border-top: 1px solid var(--line); }
  .hint { color: var(--ink-3); font-size: var(--fs-sm); margin: 0; }
  .error { color: var(--danger); }
  .ghost { justify-self: start; background: none; border: 0; padding: 0; color: var(--accent); cursor: pointer; font: inherit; }
  .ghost:disabled { color: var(--ink-3); cursor: default; }
  .download { color: var(--accent); font-weight: 600; }
  progress { width: 100%; }
</style>
