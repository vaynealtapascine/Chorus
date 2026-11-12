<script lang="ts">
  // "Link another device": a one-use invite for this account (API.md §2.1).
  import { apiBase } from '../sync/device';
  import { apiFetch } from '../http';
  import { sync } from '../sync/client';
  import Button from './kit/Button.svelte';
  import FormCard from './kit/FormCard.svelte';
  import SectionLabel from './kit/SectionLabel.svelte';
  import Icon from './Icon.svelte';

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
      // Chorus Home: phones join over the wifi with the pinned certificate (chorus:// link)
      link = j.lan_url ?? j.url;
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

  async function call(method: string, path: string, body?: unknown) {
    const r = await apiFetch(`${apiBase()}${path}`, {
      method,
      headers: { authorization: `Bearer ${sync.device?.session ?? ''}`, ...(body === undefined ? {} : { 'content-type': 'application/json' }) },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
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

  // signing out asks first, and may erase that device's copy at once instead of in two days (D-152)
  let asking = $state<string | null>(null);
  let wipe = $state(false);
  function ask(d: Dev) {
    asking = d.id;
    wipe = false;
  }

  async function signOut(d: Dev) {
    error = '';
    try {
      await call('POST', `/devices/${d.id}/revoke`, wipe ? { wipe: true } : undefined);
      asking = null;
      await loadDevices();
    } catch (e) {
      error = e instanceof Error ? e.message : String(e);
    }
  }

  const seen = (t: number | null) => (t ? `last seen ${new Date(t).toLocaleDateString()}` : 'never seen');
</script>

{#if devices.length > 1}
  <section class="link" aria-label="Your devices">
    <SectionLabel label="Your devices" />
    <FormCard>
      {#each devices as d (d.id)}
        <div class="dev">
          <div class="row">
            <span class="who"><strong>{d.name}</strong><small>{d.platform} · {d.id === sync.device?.device_id ? 'this device' : seen(d.last_seen_at)}</small></span>
            {#if d.id !== sync.device?.device_id && asking !== d.id}<Button variant="quiet" size="sm" onclick={() => ask(d)}>Sign out</Button>{/if}
          </div>
          {#if asking === d.id}
            <div class="asking" role="group" aria-label="Sign out {d.name}">
              <p>Sign out {d.name}? It stops syncing and needs a new link to come back. Its copy of your account is erased from it
                two days from now, unless it’s linked to this account again before then.</p>
              <label class="check"><input type="checkbox" bind:checked={wipe} /> Erase its copy now instead</label>
              <p class="muted">It’s erased the next time it connects, including anything written there that never synced.</p>
              <div class="actions">
                <Button variant="neutral" grow onclick={() => (asking = null)}>Cancel</Button>
                <Button variant="danger" grow onclick={() => signOut(d)}>Sign out {d.name}</Button>
              </div>
            </div>
          {/if}
        </div>
      {/each}
    </FormCard>
  </section>
{/if}

<section class="link" aria-label="Link another device">
  {#if link}
    <p>Scan this with your other device's camera, or open the link there (works once, for a day):</p>
    {#if qr}<div class="qr" role="img" aria-label="QR code for the link">{@html qr}</div>{/if}
    <div class="row">
      <code>{link}</code>
      <Button variant="soft" size="sm" onclick={copy}>{copied ? 'Copied' : 'Copy'}</Button>
    </div>
  {:else}
    <Button variant="neutral" block onclick={make} disabled={busy}><Icon name="user-plus" size={20} />{busy ? 'Making a link…' : 'Link another device…'}</Button>
  {/if}
  {#if error}<p class="error" role="alert">{error}</p>{/if}
</section>

<style>
  .link { display: grid; gap: var(--s-2); font-size: var(--fs-sm); color: var(--ink-2); }
  p { margin: 0; }
  .dev { display: grid; }
  .dev > .row { padding: var(--s-2) var(--s-3) var(--s-2) var(--s-4); min-height: 56px; flex-wrap: nowrap; }
  .who { flex: 1; min-width: 0; display: grid; }
  .who strong { color: var(--ink); font-size: var(--fs-md); font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .who small { color: var(--ink-3); font-size: var(--fs-sm); }
  .asking { display: grid; gap: var(--s-2); padding: 0 var(--s-4) var(--s-4); }
  .actions { display: flex; gap: var(--s-2); }
  .check { display: flex; gap: var(--s-2); align-items: center; color: var(--ink); }
  .muted { color: var(--ink-3); }
  .row { display: flex; gap: var(--s-2); align-items: center; flex-wrap: wrap; }
  .qr { width: min(240px, 100%); border-radius: var(--r-md); overflow: hidden; line-height: 0; }
  .qr :global(svg) { width: 100%; height: auto; }
  code { font-family: var(--font-mono); background: var(--surface-2); padding: var(--s-1) var(--s-2); border-radius: var(--r-sm); word-break: break-all; }
  .error { color: var(--danger); }
</style>
