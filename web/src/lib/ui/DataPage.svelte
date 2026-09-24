<script lang="ts">
  // Your data for scripts, dashboards and stream overlays (API.md §2.3, §3, §6): personal API
  // tokens, read-only and limited to your own account. The secret is shown once.
  import { apiBase } from '../sync/device';
  import { sync } from '../sync/client';

  interface Token { id: string; name: string; scopes: string[]; created_at: number; last_used_at: number | null }

  const SCOPES = [
    { id: 'read:front', label: 'Who is fronting, switches and front history' },
    { id: 'read:members', label: 'Member list' },
    { id: 'read:messages', label: 'Search message history' },
    { id: 'stream', label: 'Live stream (overlays)' },
    { id: 'write:front', label: 'Log switches (NFC tags, Tasker, Home Assistant)' },
    { id: 'export', label: 'Download your op log and data exports' },
  ];
  const CSV_EXPORTS = ['members', 'groups', 'switches', 'front_intervals', 'front_daily', 'messages', 'posts'];

  let tokens = $state<Token[]>([]);
  let name = $state('');
  let chosen = $state<string[]>(['read:front']);
  let fresh = $state<{ token: string; scopes: string[] } | null>(null);
  let error = $state('');
  interface Health {
    db_bytes: number; wal_bytes: number; op_count: number; connected_devices: number;
    pending_notifications: number; last_backup: { at: number; size_bytes: number } | null;
    last_error: { source: string; at: number; message: string } | null;
    ntfy_configured: boolean; ntfy_reachable: boolean | null;
  }
  let health = $state<Health | null>(null);
  let healthError = $state('');
  const size = (bytes: number) => `${(bytes / 1024 / 1024).toFixed(1)} MB`;

  async function api(path: string, init: RequestInit = {}) {
    const r = await fetch(`${apiBase()}${path}`, {
      ...init,
      headers: { 'content-type': 'application/json', authorization: `Bearer ${sync.device?.session ?? ''}` },
    });
    const text = await r.text();
    const j = text ? JSON.parse(text) : {};
    if (!r.ok) throw new Error(j?.error?.message ?? `HTTP ${r.status}`);
    return j;
  }

  async function load() {
    try {
      tokens = (await api('/tokens')).items;
    } catch (e) {
      error = e instanceof Error ? e.message : String(e);
    }
  }
  load();

  async function loadHealth() {
    if (!sync.device?.is_admin) return;
    try {
      health = await api('/admin/health') as Health;
      healthError = '';
    } catch (e) {
      healthError = e instanceof Error ? e.message : String(e);
    }
  }
  loadHealth();

  async function create(e: SubmitEvent) {
    e.preventDefault();
    error = '';
    try {
      const t = await api('/tokens', { method: 'POST', body: JSON.stringify({ name: name.trim(), scopes: chosen }) });
      fresh = { token: t.token, scopes: t.scopes };
      name = '';
      await load();
    } catch (err) {
      error = err instanceof Error ? err.message : String(err);
    }
  }

  async function revoke(id: string) {
    await api(`/tokens/${id}`, { method: 'DELETE' });
    await load();
  }

  // Webhooks (API.md §7): POST signed events to your own URL on the tailnet.
  interface Hook { id: string; url: string; events: string[]; is_enabled: boolean; last_status: number | null; last_error: string | null }
  const EVENTS = [
    { id: 'front.switch', label: 'Front changes' },
    { id: 'member.created', label: 'New members' },
    { id: 'member.updated', label: 'Member edits' },
    { id: 'follow.requested', label: 'Follow requests' },
  ];
  let hooks = $state<Hook[]>([]);
  let hookUrl = $state('');
  let hookEvents = $state<string[]>(['front.switch']);
  let hookSecret = $state<string | null>(null);
  let hookNote = $state<Record<string, string>>({});

  async function loadHooks() {
    try {
      hooks = (await api('/webhooks')).items;
    } catch (e) {
      error = e instanceof Error ? e.message : String(e);
    }
  }
  loadHooks();

  async function addHook(e: SubmitEvent) {
    e.preventDefault();
    error = '';
    try {
      const h = await api('/webhooks', { method: 'POST', body: JSON.stringify({ url: hookUrl.trim(), events: hookEvents }) });
      hookSecret = h.secret;
      hookUrl = '';
      await loadHooks();
    } catch (err) {
      error = err instanceof Error ? err.message : String(err);
    }
  }

  async function testHook(id: string) {
    hookNote = { ...hookNote, [id]: 'Sending…' };
    const r = await api(`/webhooks/${id}/test`, { method: 'POST' });
    hookNote = { ...hookNote, [id]: r.ok ? `Delivered (HTTP ${r.status})` : `Failed: ${r.error}` };
    await loadHooks();
  }

  async function toggleHook(h: Hook) {
    await api(`/webhooks/${h.id}`, { method: 'PUT', body: JSON.stringify({ enabled: !h.is_enabled }) });
    await loadHooks();
  }

  async function removeHook(id: string) {
    await api(`/webhooks/${id}`, { method: 'DELETE' });
    await loadHooks();
  }

  const overlay = (t: string) => `${location.origin}/overlay/front?token=${t}`;
  const when = (t: number | null) => (t ? new Date(t).toLocaleString() : 'never');

  async function download(path: string, filename: string) {
    error = '';
    try {
      const response = await fetch(`${apiBase()}${path}`, {
        headers: { authorization: `Bearer ${sync.device?.session ?? ''}` },
      });
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
</script>

<section class="page">
  <a class="back" href="#/">‹ Home</a>
  <h1 class="display">Your data</h1>
  {#if sync.device?.is_admin}
    <section class="card" aria-label="Server health">
      <div class="row"><h2>Server health</h2><button class="ghost" onclick={loadHealth}>Refresh</button></div>
      {#if health}
        <span>Database {size(health.db_bytes)} · WAL {size(health.wal_bytes)} · {health.op_count.toLocaleString()} ops</span>
        <span>{health.connected_devices} connected devices · {health.pending_notifications} pending notifications</span>
        <span>Last backup: {health.last_backup ? `${when(health.last_backup.at)} · ${size(health.last_backup.size_bytes)}` : 'none yet'}</span>
        {#if health.ntfy_configured}<span>ntfy: {health.ntfy_reachable ? 'reachable' : 'unreachable'}</span>{/if}
        {#if health.last_error}<span class="error">Last {health.last_error.source} error: {health.last_error.message}</span>{/if}
      {/if}
      {#if healthError}<span class="error">Health unavailable: {healthError}</span>{/if}
    </section>
  {/if}
  <section class="card">
    <h2>Export your data</h2>
    <button class="ghost" onclick={() => download('/exports/ops.jsonl', 'ops.jsonl')}>Download op log (JSONL)</button>
    <button class="ghost" onclick={() => download('/exports/account.sqlite', 'account.sqlite')}>Download SQLite copy</button>
    <div class="actions">
      {#each CSV_EXPORTS as table (table)}
        <button class="ghost" onclick={() => download(`/exports/csv/${table}`, `${table}.csv`)}>{table.replaceAll('_', ' ')} CSV</button>
      {/each}
    </div>
  </section>
  <p class="hint">
    Tokens let your own scripts, spreadsheets, Grafana or a stream overlay read <em>your</em> front history,
    and an NFC tag or Tasker log a switch. They only ever reach your own account.
  </p>

  <form class="card" onsubmit={create}>
    <input bind:value={name} placeholder="What it's for (e.g. OBS overlay)" aria-label="Token name" required />
    {#each SCOPES as s (s.id)}
      <label class="check">
        <input type="checkbox" value={s.id} bind:group={chosen} />
        {s.label}
      </label>
    {/each}
    <button class="primary" disabled={!chosen.length}>Create token</button>
  </form>
  {#if error}<p class="error" role="alert">{error}</p>{/if}

  {#if fresh}
    <div class="card fresh">
      <strong>Copy it now — it won't be shown again:</strong>
      <code>{fresh.token}</code>
      {#if fresh.scopes.includes('stream') && fresh.scopes.includes('read:front')}
        <span>OBS browser source: <code>{overlay(fresh.token)}</code></span>
      {/if}
      <span class="hint">Scripts: <code>curl -H "Authorization: Bearer {fresh.token.slice(0, 12)}…" {apiBase()}/front</code></span>
      {#if fresh.scopes.includes('write:front')}
        <span class="hint">Switch: <code>POST {apiBase()}/front/switch {'{"entries": [{"member": "Kai"}]}'}</code> (empty entries = switch out)</span>
      {/if}
    </div>
  {/if}

  <h2>Tokens</h2>
  <ul>
    {#each tokens as t (t.id)}
      <li class="card row">
        <div>
          <strong>{t.name}</strong>
          <span class="hint">{t.scopes.join(', ')} · last used {when(t.last_used_at)}</span>
        </div>
        <button class="ghost" onclick={() => revoke(t.id)}>Revoke</button>
      </li>
    {:else}
      <li class="hint">No tokens yet.</li>
    {/each}
  </ul>

  <h2>Webhooks</h2>
  <p class="hint">Chorus posts a signed JSON event to your URL (Home Assistant, n8n, a script) when these happen.</p>
  <form class="card" onsubmit={addHook}>
    <input bind:value={hookUrl} type="url" placeholder="https://homeassistant.tailnet.ts.net/api/webhook/…" aria-label="Webhook URL" required />
    {#each EVENTS as ev (ev.id)}
      <label class="check">
        <input type="checkbox" value={ev.id} bind:group={hookEvents} />
        {ev.label}
      </label>
    {/each}
    <button class="primary" disabled={!hookEvents.length}>Add webhook</button>
  </form>
  {#if hookSecret}
    <div class="card fresh">
      <strong>Signing secret — copy it now, it won't be shown again:</strong>
      <code>{hookSecret}</code>
      <span class="hint">Check <code>Chorus-Signature: t=…,v1=…</code> = HMAC-SHA256(secret, t + "." + body).</span>
    </div>
  {/if}
  <ul>
    {#each hooks as h (h.id)}
      <li class="card">
        <div class="row">
          <div>
            <strong class="url">{h.url}</strong>
            <span class="hint">{h.events.join(', ')}{h.is_enabled ? '' : ' · off'}{h.last_status ? ` · last HTTP ${h.last_status}` : ''}</span>
          </div>
          <div class="actions">
            <button class="ghost" onclick={() => testHook(h.id)}>Test</button>
            <button class="ghost" onclick={() => toggleHook(h)}>{h.is_enabled ? 'Turn off' : 'Turn on'}</button>
            <button class="ghost" onclick={() => removeHook(h.id)}>Remove</button>
          </div>
        </div>
        {#if hookNote[h.id]}<span class="hint">{hookNote[h.id]}</span>{/if}
        {#if h.last_error}<span class="error">{h.last_error}</span>{/if}
      </li>
    {:else}
      <li class="hint">No webhooks yet.</li>
    {/each}
  </ul>
</section>

<style>
  .page { display: grid; gap: var(--s-4); }
  .back { color: var(--accent); text-decoration: none; font-size: var(--fs-sm); }
  h1 { font-size: var(--fs-2xl); margin: 0; }
  h2 { font-size: var(--fs-sm); font-weight: 600; color: var(--ink-2); margin: 0; }
  .hint { color: var(--ink-3); font-size: var(--fs-sm); margin: 0; }
  .card { display: grid; gap: var(--s-2); padding: var(--s-4); background: var(--surface); border: 1px solid var(--line); border-radius: var(--r-md); }
  .row { display: flex; justify-content: space-between; align-items: center; }
  .row div { display: grid; }
  .fresh code { word-break: break-all; }
  .url { word-break: break-all; }
  .actions { display: flex; flex-wrap: wrap; justify-content: flex-end; }
  ul { list-style: none; margin: 0; padding: 0; display: grid; gap: var(--s-2); }
  input:not([type='checkbox']) {
    font: inherit; color: var(--ink); background: var(--surface-2); border: 1px solid var(--line);
    border-radius: var(--r-sm); padding: var(--s-2) var(--s-3);
  }
  .check { display: flex; gap: var(--s-2); align-items: center; color: var(--ink-2); font-size: var(--fs-sm); }
  code { font-family: var(--font-mono); background: var(--surface-2); padding: 0 var(--s-1); border-radius: 4px; font-size: var(--fs-sm); }
  .primary { justify-self: start; background: var(--accent); color: var(--surface); border: 0; border-radius: var(--r-full); padding: var(--s-2) var(--s-4); font-weight: 600; cursor: pointer; }
  .primary:disabled { opacity: 0.5; }
  .ghost { background: none; border: 0; color: var(--accent); cursor: pointer; }
  .error { color: var(--danger); margin: 0; }
</style>
