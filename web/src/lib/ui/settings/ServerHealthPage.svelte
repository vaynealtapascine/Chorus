<script lang="ts">
  // Server health (Figma F10.6): three tiles, then the restore window when one is open (SYNC.md §7.3).
  import Icon from '../Icon.svelte';
  import ConfirmSheet from '../kit/ConfirmSheet.svelte';
  import PageHeader from '../kit/PageHeader.svelte';
  import { ago } from '../../ago';
  import { settingsApi } from '../../settings-api';
  import { sync } from '../../sync/client';

  interface Health {
    db_bytes: number; wal_bytes: number; op_count: number; connected_devices: number;
    pending_notifications: number; last_backup: { at: number; size_bytes: number } | null;
    last_error: { source: string; at: number; message: string } | null;
    ntfy_configured: boolean; ntfy_reachable: boolean | null;
    restore_window?: {
      open: boolean; closes_at: number | null;
      devices: { account: string; name: string; platform: string; last_seen_at: number | null; back_at: number | null }[];
    };
  }
  let health = $state<Health | null>(null);
  let error = $state('');
  let asking = $state(false);
  const size = (bytes: number) => `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  const now = $derived(health ? Date.now() : 0);

  async function load() {
    if (!sync.device?.is_admin) return;
    try { health = await settingsApi<Health>('/admin/health'); error = ''; } catch (e) { error = e instanceof Error ? e.message : String(e); }
  }
  void load();
  // this device counts as back once its sync socket has said hello: look again when it goes live
  $effect(() => {
    let was = sync.status;
    return sync.subscribe(() => {
      if (sync.status === 'live' && was !== 'live') void load();
      was = sync.status;
    });
  });

  const restore = $derived(health?.restore_window?.open ? health.restore_window : null);
  const back = $derived(restore ? restore.devices.filter((d) => d.back_at !== null).length : 0);
  async function close() {
    asking = false;
    try { await settingsApi('/admin/reconcile/close', { method: 'POST' }); await load(); } catch (e) { error = e instanceof Error ? e.message : String(e); }
  }
  const when = (t: number | null) => (t ? new Date(t).toLocaleString() : 'never');
</script>

<section class="page">
  <PageHeader title="Server health">
    {#snippet action()}<button class="pill" onclick={load}>Refresh</button>{/snippet}
  </PageHeader>
  {#if error}<p class="error" role="alert">Health unavailable: {error}</p>{/if}
  {#if health}
    <div class="tiles">
      <div class="tile">
        <span class="disc"><Icon name="history" size={18} /></span>
        <span class="k">Last backup</span>
        <strong>{health.last_backup ? ago(health.last_backup.at, now) : 'None yet'}</strong>
        <span class="s">{health.last_backup ? size(health.last_backup.size_bytes) : ''}</span>
      </div>
      <div class="tile">
        <span class="disc"><Icon name="target" size={18} /></span>
        <span class="k">Errors</span>
        <strong>{health.last_error ? ago(health.last_error.at, now) : 'None'}</strong>
        <span class="s">{health.last_error ? `${health.last_error.source}: ${health.last_error.message}` : 'Nothing to fix'}</span>
      </div>
      <div class="tile">
        <span class="disc"><Icon name="users" size={18} /></span>
        <span class="k">Devices</span>
        <strong>{health.connected_devices} connected</strong>
        <span class="s">{health.op_count.toLocaleString()} changes · {size(health.db_bytes)}</span>
      </div>
    </div>
    {#if restore}
      <div class="card">
        <div class="head">
          <span class="disc"><Icon name="swap" size={18} /></span>
          <h2>Restore in progress</h2>
          <span class="count">{back} of {restore.devices.length} devices back</span>
        </div>
        <div class="bar"><span style="width: {restore.devices.length ? (back / restore.devices.length) * 100 : 0}%"></span></div>
        <ul>
          {#each restore.devices as d (d.account + d.name)}
            <li><Icon name={d.back_at !== null ? 'check' : 'clock'} size={16} /><strong>{d.name}</strong>
              <span>{d.back_at !== null ? 'Back' : d.last_seen_at ? `Waiting · last seen ${ago(d.last_seen_at, now)}` : 'Waiting · offline'}</span></li>
          {/each}
        </ul>
        <button class="link" onclick={() => (back < restore.devices.length ? (asking = true) : close())}>Close</button>
        <span class="s">Closes by itself on {when(restore.closes_at)}</span>
      </div>
    {/if}
    {#if health.ntfy_configured}<p class="s">ntfy: {health.ntfy_reachable ? 'reachable' : 'unreachable'} · {health.pending_notifications} pending</p>{/if}
  {/if}
</section>

{#if asking}
  <ConfirmSheet title="Close the restore?" message="Some devices are not back yet. Anything they still hold will count as sent by them now, not by its original author." confirm="Close anyway" onconfirm={close} onclose={() => (asking = false)} />
{/if}

<style>
  .page { display: grid; gap: var(--s-3); max-width: 840px; align-content: start; }
  .pill { min-height: 36px; padding: 0 var(--s-4); border: 0; border-radius: var(--r-full); background: var(--surface-2); color: var(--ink); font: inherit; font-size: 14px; font-weight: 600; cursor: pointer; }
  .tiles { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: var(--s-3); }
  .tile, .card { display: grid; gap: var(--s-1); padding: 14px; border-radius: var(--r-md); background: var(--surface-2); align-content: start; }
  .card { gap: var(--s-3); }
  .disc { display: grid; place-items: center; width: 32px; height: 32px; border-radius: var(--r-sm); background: var(--accent-soft); color: var(--accent); }
  .tile .disc { margin-bottom: var(--s-2); }
  .k { color: var(--ink-3); font-size: var(--fs-sm); }
  .tile strong { font-size: 20px; }
  .s { color: var(--ink-3); font-size: var(--fs-sm); overflow-wrap: anywhere; }
  .head { display: flex; align-items: center; gap: 10px; }
  h2 { margin: 0; font-size: var(--fs-base); flex: 1; }
  .count { color: var(--accent); font-size: var(--fs-sm); font-weight: 600; }
  .bar { height: 6px; border-radius: 3px; background: var(--surface-3, var(--surface)); overflow: hidden; }
  .bar span { display: block; height: 100%; background: var(--accent); }
  ul { list-style: none; margin: 0; padding: var(--s-3) 14px; display: grid; gap: var(--s-3); border-radius: var(--r-row); background: var(--surface); }
  li { display: flex; align-items: center; gap: 10px; }
  li :global(.icon) { color: var(--accent); }
  li span { color: var(--ink-3); font-size: var(--fs-sm); }
  .link { justify-self: start; border: 0; background: none; padding: 0; color: var(--accent); font: inherit; cursor: pointer; }
  .error { margin: 0; color: var(--danger); }
</style>
