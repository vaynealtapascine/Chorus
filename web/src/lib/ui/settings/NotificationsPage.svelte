<script lang="ts">
  // What pings this account and this browser (no Figma frame; Android's Notification settings page).
  import FormCard from '../kit/FormCard.svelte';
  import PageHeader from '../kit/PageHeader.svelte';
  import SectionLabel from '../kit/SectionLabel.svelte';
  import Switch from '../kit/Switch.svelte';
  import { selfMember } from '../../data';
  import { prefOf, savePref, settingsApi } from '../../settings-api';
  import { sync, type Projection } from '../../sync/client';
  import { disablePush, enablePush, pushActive, pushSupported, testPush } from '../../push';

  let { projection }: { projection: Projection } = $props();
  const isSystem = $derived(!selfMember(projection));
  const kinds = $derived((prefOf(projection.rows.pref, 'notify_chat') ?? {}) as Record<string, boolean>);
  const chatKind = (key: string, on: boolean) => savePref('notify_chat', { ...kinds, [key]: on });

  interface Follow { id: string; status: string; prefs?: Record<string, unknown> }
  let follows = $state<Follow[]>([]);
  let quietFrom = $state('23:00');
  let quietTo = $state('08:00');
  let quietOn = $state(false);
  let note = $state('');
  const active = $derived(follows.filter((f) => f.status === 'active'));
  async function loadFollows() {
    try {
      const loaded = (await settingsApi('/follows')).following as Follow[];
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
        const prefs = { ...(follow.prefs ?? {}), quiet_hours: value, tz_offset_min: -new Date().getTimezoneOffset() };
        await settingsApi(`/follows/${follow.id}/prefs`, { method: 'PUT', body: JSON.stringify(prefs) });
        follow.prefs = prefs;
      }
      quietOn = !!value;
    } catch (error) {
      note = error instanceof Error ? error.message : String(error);
    }
  }

  // this browser's notifications (Web Push, or the page's own while it's open)
  let pushOn = $state(false);
  let canNotify = $state(typeof Notification !== 'undefined' && Notification.permission === 'granted');
  let pushNote = $state('');
  pushActive().then((on) => (pushOn = on));
  async function enableNotifications() {
    if (pushSupported()) {
      const err = await enablePush();
      pushOn = !err;
      pushNote = err ? `${err} Notifications will show while Chorus is open.` : '';
      if (!err) { canNotify = true; return; }
    }
    canNotify = (await Notification.requestPermission()) === 'granted';
  }
  async function sendTest() {
    const err = await testPush();
    pushNote = err ? `The test didn't go out: ${err}` : 'Sent.';
  }
  const canAsk = $derived(typeof Notification !== 'undefined' && (!canNotify || pushSupported()));
</script>

<section class="page">
  <PageHeader title="Notifications" />
  <FormCard label="Notify me about">
    <Switch label="Mentions" checked={kinds.mention !== false} onchange={(on) => chatKind('mention', on)} />
    <Switch label="Direct messages" checked={kinds.dm !== false} onchange={(on) => chatKind('dm', on)} />
    <Switch label="Replies" checked={kinds.reply !== false} onchange={(on) => chatKind('reply', on)} />
    <Switch label="Poll nudges" checked={kinds.poll_nudge !== false} onchange={(on) => chatKind('poll_nudge', on)} />
  </FormCard>
  {#if active.length}
    <SectionLabel label="Quiet hours" />
    <FormCard>
      <Switch label="Quiet hours" sub={quietOn ? `${quietFrom} to ${quietTo}` : null} checked={quietOn}
        onchange={(on) => void saveQuiet(on ? { from: quietFrom, to: quietTo } : null)} />
      <div class="times">
        <input type="time" bind:value={quietFrom} aria-label="Quiet from" onchange={() => quietOn && saveQuiet({ from: quietFrom, to: quietTo })} />
        <span>to</span>
        <input type="time" bind:value={quietTo} aria-label="Quiet until" onchange={() => quietOn && saveQuiet({ from: quietFrom, to: quietTo })} />
      </div>
    </FormCard>
  {/if}
  {#if note}<p class="note" role="status">{note}</p>{/if}
  <SectionLabel label="This browser" />
  <FormCard>
    {#if pushOn}
      <button class="row" onclick={sendTest}>Send a test</button>
      <button class="row" onclick={async () => { await disablePush(); pushOn = false; }}>Stop notifying this browser</button>
    {:else if canAsk}
      <button class="row" onclick={enableNotifications}>Notify me in this browser</button>
    {:else}
      <p class="row off">{canNotify ? 'Notifies while Chorus is open.' : 'This browser can’t show notifications.'}</p>
    {/if}
  </FormCard>
  {#if pushNote}<p class="note" role="status">{pushNote}</p>{/if}
  {#if isSystem}
    <FormCard>
      <Switch label="Ping my other devices when the front changes" checked={kinds.own_switch === true} onchange={(on) => chatKind('own_switch', on)} />
      <Switch label="Reply as the mentioned member" checked={kinds.reply_as_mentioned === true} onchange={(on) => chatKind('reply_as_mentioned', on)} />
    </FormCard>
  {/if}
</section>

<style>
  .page { display: grid; gap: 12px; max-width: 720px; align-content: start; }
  .times { display: flex; align-items: center; gap: 10px; padding: 10px 14px; color: var(--ink-3); }
  input[type='time'] { font: inherit; color: var(--ink); background: var(--surface); border: 1px solid var(--line); border-radius: 10px; padding: 6px 10px; }
  .row { display: block; width: 100%; min-height: 46px; padding: 10px 14px; border: 0; background: none; color: var(--ink); font: inherit; font-size: 15px; text-align: left; cursor: pointer; margin: 0; }
  .row.off { color: var(--ink-3); cursor: default; }
  .note { margin: 0; color: var(--ink-3); font-size: 13px; padding: 0 4px; }
</style>
