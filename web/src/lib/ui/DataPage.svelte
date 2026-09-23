<script lang="ts">
  // Your data for scripts, dashboards and stream overlays (API.md §2.3, §3, §6): personal API
  // tokens, read-only and limited to your own account. The secret is shown once.
  import { apiBase } from '../sync/device';
  import { sync } from '../sync/client';

  interface Token { id: string; name: string; scopes: string[]; created_at: number; last_used_at: number | null }

  const SCOPES = [
    { id: 'read:front', label: 'Who is fronting, switches and front history' },
    { id: 'read:members', label: 'Member list' },
    { id: 'stream', label: 'Live stream (overlays)' },
  ];

  let tokens = $state<Token[]>([]);
  let name = $state('');
  let chosen = $state<string[]>(['read:front']);
  let fresh = $state<{ token: string; scopes: string[] } | null>(null);
  let error = $state('');

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

  const overlay = (t: string) => `${location.origin}/overlay/front?token=${t}`;
  const when = (t: number | null) => (t ? new Date(t).toLocaleString() : 'never');
</script>

<section class="page">
  <a class="back" href="#/">‹ Home</a>
  <h1 class="display">Your data</h1>
  <p class="hint">
    Tokens let your own scripts, spreadsheets, Grafana or a stream overlay read <em>your</em> front history.
    They're read-only and never see anyone else's data.
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
