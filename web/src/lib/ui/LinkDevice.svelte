<script lang="ts">
  // "Link another device": a one-use invite for this account (API.md §2.1).
  import { apiBase } from '../sync/device';
  import { apiFetch } from '../http';
  import { sync } from '../sync/client';

  let link = $state('');
  let qr = $state('');
  let error = $state('');
  let busy = $state(false);
  let copied = $state(false);

  async function make() {
    busy = true;
    error = '';
    try {
      const r = await apiFetch(`${apiBase()}/devices/invite`, {
        method: 'POST',
        headers: { authorization: `Bearer ${sync.device?.session ?? ''}` },
      });
      const j = await r.json();
      if (!r.ok) throw new Error(j?.error?.message ?? `HTTP ${r.status}`);
      link = j.url;
      qr = j.qr_svg ?? '';
    } catch (e) {
      error = e instanceof Error ? e.message : String(e);
    } finally {
      busy = false;
    }
  }

  async function copy() {
    await navigator.clipboard.writeText(link);
    copied = true;
    setTimeout(() => (copied = false), 1500);
  }

  // this account's other devices, so a lost phone can be signed out from here
  interface Dev { id: string; name: string; platform: string; last_seen_at: number | null }
  let devices = $state<Dev[]>([]);

  async function call(method: string, path: string) {
    const r = await apiFetch(`${apiBase()}${path}`, { method, headers: { authorization: `Bearer ${sync.device?.session ?? ''}` } });
    if (!r.ok) throw new Error((await r.json().catch(() => null))?.error?.message ?? `HTTP ${r.status}`);
    return r.status === 204 ? null : r.json();
  }

  async function loadDevices() {
    try {
      devices = (await call('GET', '/me')).devices ?? [];
    } catch {
      devices = [];
    }
  }
  loadDevices();

  async function signOut(d: Dev) {
    if (!confirm(`Sign out ${d.name}? It will need a new link to come back.`)) return;
    error = '';
    try {
      await call('POST', `/devices/${d.id}/revoke`);
      await loadDevices();
    } catch (e) {
      error = e instanceof Error ? e.message : String(e);
    }
  }

  const seen = (t: number | null) => (t ? `last seen ${new Date(t).toLocaleDateString()}` : 'never seen');
</script>

{#if devices.length > 1}
  <section class="link" aria-label="Your devices">
    <p>Your devices</p>
    <ul>
      {#each devices as d (d.id)}
        <li class="row">
          <span>{d.name} <span class="muted">· {d.platform} · {d.id === sync.device?.device_id ? 'this device' : seen(d.last_seen_at)}</span></span>
          {#if d.id !== sync.device?.device_id}<button class="ghost" onclick={() => signOut(d)}>Sign out</button>{/if}
        </li>
      {/each}
    </ul>
  </section>
{/if}

<section class="link" aria-label="Link another device">
  {#if link}
    <p>Scan this with your other device's camera, or open the link there (works once, for a day):</p>
    {#if qr}<div class="qr" role="img" aria-label="QR code for the link">{@html qr}</div>{/if}
    <div class="row">
      <code>{link}</code>
      <button class="ghost" onclick={copy}>{copied ? 'Copied' : 'Copy'}</button>
    </div>
  {:else}
    <button class="ghost" onclick={make} disabled={busy}>{busy ? 'Making a link…' : 'Link another device…'}</button>
  {/if}
  {#if error}<p class="error" role="alert">{error}</p>{/if}
</section>

<style>
  .link {
    display: grid;
    gap: var(--s-2);
    font-size: var(--fs-sm);
    color: var(--ink-2);
  }
  p {
    margin: 0;
  }
  ul {
    list-style: none;
    margin: 0;
    padding: 0;
    display: grid;
    gap: var(--s-1);
  }
  li {
    justify-content: space-between;
  }
  .muted {
    color: var(--ink-3);
  }
  .row {
    display: flex;
    gap: var(--s-2);
    align-items: center;
    flex-wrap: wrap;
  }
  .qr {
    width: min(240px, 100%);
    border-radius: var(--r-md);
    overflow: hidden;
    line-height: 0;
  }
  .qr :global(svg) {
    width: 100%;
    height: auto;
  }
  code {
    font-family: var(--font-mono);
    background: var(--surface-2);
    padding: var(--s-1) var(--s-2);
    border-radius: var(--r-sm);
    word-break: break-all;
  }
  .ghost {
    justify-self: start;
    background: none;
    border: 0;
    padding: 0;
    color: var(--accent);
    cursor: pointer;
    font: inherit;
  }
  .error {
    color: var(--danger);
  }
</style>
