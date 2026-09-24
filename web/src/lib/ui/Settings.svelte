<script lang="ts">
  import { notifyPreset } from '../core/pkg/chorus_wasm.js';
  import { contentWarningsAutoExpand, segmentParsing, selfMember } from '../data';
  import { apiBase } from '../sync/device';
  import { sync, type Projection } from '../sync/client';

  let { projection }: { projection: Projection } = $props();
  const isSystem = $derived(!selfMember(projection));
  const rows = $derived(projection.rows.pref ?? {});
  const pref = (key: string) => (rows[`||${key}`] ?? rows[`${sync.accountId}||${key}`])?.fields.value;
  const kinds = $derived((pref('notify_chat') ?? {}) as Record<string, boolean>);
  const legacySettings = $derived((projection.rows.account?.[sync.accountId]?.fields.settings ?? {}) as Record<string, unknown>);
  const ceiling = $derived((pref('follow_ceiling') ?? legacySettings.follow_ceiling ?? {}) as Record<string, unknown>);
  const presets = [
    { id: 'close', label: 'Close' },
    { id: 'gentle', label: 'Gentle' },
    { id: 'private', label: 'Private' },
  ];
  const presetValues = Object.fromEntries(presets.map((p) => [p.id, JSON.parse(notifyPreset(p.id))]));
  const withoutSharing = (c: Record<string, unknown>) => Object.fromEntries(Object.entries(c).filter(([k]) => k !== 'share_history' && k !== 'share_stats'));
  const preset = $derived(presets.find((p) => JSON.stringify(presetValues[p.id]) === JSON.stringify(withoutSharing(ceiling)))?.id ?? (Object.keys(ceiling).length ? 'custom' : 'gentle'));

  function savePref(key: string, value: unknown) {
    sync.create('pref.set', sync.accountScope, null, { device: '', key, value });
  }
  function chatKind(key: string, enabled: boolean) {
    savePref('notify_chat', { ...kinds, [key]: enabled });
  }
  function sharing(id: string) {
    savePref('follow_ceiling', { ...presetValues[id], ...Object.fromEntries(['share_history', 'share_stats'].filter((key) => key in ceiling).map((key) => [key, ceiling[key]])) });
  }
  function sharingDetail(key: 'share_history' | 'share_stats', enabled: boolean) {
    savePref('follow_ceiling', { ...ceiling, [key]: enabled });
  }

  interface Follow { id: string; status: string; prefs?: Record<string, unknown> }
  let follows = $state<Follow[]>([]);
  let quietFrom = $state('23:00');
  let quietTo = $state('08:00');
  let quietOn = $state(false);
  let note = $state('');
  const active = $derived(follows.filter((f) => f.status === 'active'));
  const tzOffset = () => -new Date().getTimezoneOffset();
  async function api(path: string, init: RequestInit = {}) {
    const response = await fetch(`${apiBase()}${path}`, {
      ...init,
      headers: { 'content-type': 'application/json', authorization: `Bearer ${sync.device?.session ?? ''}` },
    });
    const text = await response.text();
    const json = text ? JSON.parse(text) : {};
    if (!response.ok) throw new Error(json?.error?.message ?? `HTTP ${response.status}`);
    return json;
  }
  async function loadFollows() {
    try {
      const loaded = (await api('/follows')).following as Follow[];
      follows = loaded;
      const quiet = loaded.find((f) => f.status === 'active' && f.prefs?.quiet_hours)?.prefs?.quiet_hours as { from: string; to: string } | undefined;
      quietOn = !!quiet;
      if (quiet) [quietFrom, quietTo] = [quiet.from, quiet.to];
    } catch (error) {
      note = error instanceof Error ? error.message : String(error);
    }
  }
  $effect(() => { if (sync.device) void loadFollows(); });

  async function saveQuiet(value: { from: string; to: string } | null) {
    note = '';
    try {
      for (const follow of active) {
        const prefs = { ...(follow.prefs ?? {}), quiet_hours: value, tz_offset_min: tzOffset() };
        await api(`/follows/${follow.id}/prefs`, { method: 'PUT', body: JSON.stringify(prefs) });
        follow.prefs = prefs;
      }
      quietOn = !!value;
      note = value ? `Quiet from ${value.from} to ${value.to}, your time.` : 'Quiet hours off.';
    } catch (error) {
      note = error instanceof Error ? error.message : String(error);
    }
  }
</script>

<section class="settings">
  <h1 class="display">Settings</h1>
  <p class="muted">Changes save on this device and sync with your account.</p>
  <section class="card">
    <h2>Notifications</h2>
    <label><input type="checkbox" checked={kinds.mention !== false} onchange={(e) => chatKind('mention', e.currentTarget.checked)} /> Mentions</label>
    <label><input type="checkbox" checked={kinds.dm !== false} onchange={(e) => chatKind('dm', e.currentTarget.checked)} /> Direct messages</label>
    <label><input type="checkbox" checked={kinds.reply !== false} onchange={(e) => chatKind('reply', e.currentTarget.checked)} /> Replies</label>
    {#if active.length}
      <form class="quiet" onsubmit={(e) => { e.preventDefault(); void saveQuiet({ from: quietFrom, to: quietTo }); }}>
        <span>Quiet hours for people you follow</span>
        <input type="time" bind:value={quietFrom} aria-label="Quiet from" required /> to
        <input type="time" bind:value={quietTo} aria-label="Quiet until" required />
        <button>{quietOn ? 'Update' : 'Turn on'}</button>
        {#if quietOn}<button type="button" onclick={() => saveQuiet(null)}>Turn off</button>{/if}
      </form>
    {/if}
    {#if note}<p role="status">{note}</p>{/if}
  </section>
  {#if isSystem}
    <section class="card">
      <h2>Sharing</h2>
      <label>Default for new followers
        <select value={preset} onchange={(e) => sharing(e.currentTarget.value)} aria-label="Default follower ceiling">
          {#if preset === 'custom'}<option value="custom" disabled>Custom</option>{/if}
          {#each presets as p (p.id)}<option value={p.id}>{p.label}</option>{/each}
        </select>
      </label>
      <p class="muted">Set buckets and each follower’s access on <a href="#/people">People</a>.</p>
    </section>
  {/if}
  <details class="card">
    <summary>Advanced</summary>
    <div class="advanced">
      {#if isSystem}<label><input type="checkbox" checked={kinds.own_switch === true} onchange={(e) => chatKind('own_switch', e.currentTarget.checked)} /> Ping my other devices when the front changes</label>{/if}
      {#if isSystem}<label><input type="checkbox" checked={kinds.reply_as_mentioned === true} onchange={(e) => chatKind('reply_as_mentioned', e.currentTarget.checked)} /> Replies from a notification speak as the member it mentions</label>{/if}
      {#if isSystem}
        <label><input type="checkbox" checked={ceiling.share_history === true} onchange={(e) => sharingDetail('share_history', e.currentTarget.checked)} /> Followers can look back at who fronted</label>
        <label><input type="checkbox" checked={ceiling.share_stats === true} onchange={(e) => sharingDetail('share_stats', e.currentTarget.checked)} /> Followers can see fronting stats</label>
      {/if}
      <label><input type="checkbox" checked={contentWarningsAutoExpand(projection, sync.accountId)} onchange={(e) => savePref('chat.cw_auto_expand', e.currentTarget.checked)} /> Expand content warnings automatically</label>
      <label><input type="checkbox" checked={segmentParsing(projection, sync.accountId)} onchange={(e) => savePref('chat.segment_parsing', e.currentTarget.checked)} /> Parse speaker annotations in chat</label>
    </div>
  </details>
</section>

<style>
  .settings { display: grid; gap: var(--s-4); max-width: 720px; margin: auto; }
  h1, h2, p { margin: 0; }
  h2 { font-size: var(--fs-lg); }
  .muted { color: var(--ink-3); }
  .card { display: grid; gap: var(--s-3); padding: var(--s-4); background: var(--surface); border: 1px solid var(--line); border-radius: var(--r-lg); }
  label { display: flex; align-items: center; gap: var(--s-2); }
  input, select, button { font: inherit; }
  select, input[type='time'] { color: var(--ink); background: var(--surface-2); border: 1px solid var(--line); border-radius: var(--r-sm); padding: var(--s-1) var(--s-2); }
  button { color: var(--ink); background: var(--surface-2); border: 1px solid var(--line); border-radius: var(--r-sm); padding: var(--s-1) var(--s-3); cursor: pointer; }
  .quiet { display: flex; flex-wrap: wrap; align-items: center; gap: var(--s-2); border-top: 1px solid var(--line); padding-top: var(--s-3); }
  summary { cursor: pointer; font-weight: 600; }
  .advanced { display: grid; gap: var(--s-3); padding-top: var(--s-3); }
</style>
