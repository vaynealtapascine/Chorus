<script lang="ts">
  // "Full export" (D-068, API.md §4, Figma F9.2): the server builds a zip of this account's
  // history, tables and files in the background; this polls it every 2 s while the page is open.
  import { onMount } from 'svelte';
  import { apiBase } from '../sync/device';
  import { apiFetch } from '../http';
  import { sync } from '../sync/client';
  import Icon from './Icon.svelte';

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
  const percent = $derived(job && job.total ? Math.round((job.done / job.total) * 100) : 0);

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

<section class="bundle" aria-label="Full export">
  <div class="head">
    <span class="tile"><Icon name="folder" size={20} /></span>
    <div class="titles">
      <strong>Full export</strong>
      <span>Everything, with photos and files{job?.status === 'done' ? ` · ${mb(job.bytes)}` : ''}</span>
    </div>
    {#if running}
      <button class="pill" onclick={stop}>Cancel</button>
    {:else if job?.status === 'done' && job.result_url}
      <a class="pill go" href={`${location.origin}${job.result_url}`} download={job.file_name ?? 'chorus-export.zip'}>Download</a>
    {:else}
      <button class="pill" onclick={start} disabled={sync.status !== 'live'}>Export</button>
    {/if}
  </div>
  {#if running && job}
    <div class="bar" role="progressbar" aria-valuemin="0" aria-valuemax="100" aria-valuenow={percent}><span style="width: {percent}%"></span></div>
    <div class="foot">
      <span class="phase">{job.status === 'queued' ? 'Waiting' : `${PHASE[job.phase ?? 'ops']}… ${percent}%`}</span>
      <span>{mb(job.bytes)}</span>
    </div>
  {:else if job?.status === 'done' && job.result_url}
    <div class="foot">
      <button class="link" onclick={stop}>Delete it now</button>
      {#if job.expires_at}<span>Until {new Date(job.expires_at).toLocaleString()}</span>{/if}
    </div>
  {:else if job?.status === 'failed'}
    <p class="error" role="alert">The export didn't finish: {job.error}</p>
  {/if}
  {#if error}<p class="error" role="alert">{error}</p>{/if}
</section>

<style>
  .bundle { display: grid; gap: 10px; padding: 14px; border-radius: 16px; background: var(--surface-2); }
  .head { display: flex; align-items: center; gap: 12px; }
  .tile { display: grid; place-items: center; width: 40px; height: 40px; border-radius: 12px; background: var(--accent-soft); color: var(--accent); flex: none; }
  .titles { flex: 1; min-width: 0; display: grid; }
  .titles strong { font-size: 15px; }
  .titles span { color: var(--ink-3); font-size: 13px; }
  .pill { display: inline-flex; align-items: center; min-height: 36px; padding: 0 16px; border: 0; border-radius: 999px; background: var(--surface-3, var(--surface)); color: var(--ink); font: inherit; font-size: 14px; font-weight: 600; cursor: pointer; text-decoration: none; }
  .pill.go { background: var(--accent); color: var(--on-accent); }
  .pill:disabled { color: var(--ink-3); cursor: default; }
  .bar { height: 6px; border-radius: 3px; background: var(--surface-3, var(--surface)); overflow: hidden; }
  .bar span { display: block; height: 100%; background: var(--accent); transition: width var(--t-base) var(--ease); }
  .foot { display: flex; justify-content: space-between; gap: 12px; color: var(--ink-3); font-size: 12px; }
  .phase { color: var(--accent); font-weight: 600; }
  .link { border: 0; background: none; padding: 0; color: var(--accent); font: inherit; cursor: pointer; }
  .error { margin: 0; color: var(--danger); font-size: 13px; }
</style>
