<script lang="ts">
  // What pings this account and this browser (F7.13, as far as a browser can: a status card with Turn on and
  // Send a test, and a switch per kind the account can silence; `notify_chat` is account-wide, so it also
  // governs the phone).
  import Button from '../kit/Button.svelte';
  import FormCard from '../kit/FormCard.svelte';
  import PageHeader from '../kit/PageHeader.svelte';
  import SectionLabel from '../kit/SectionLabel.svelte';
  import Icon from '../Icon.svelte';
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
  let permission = $state<NotificationPermission | 'unsupported'>(typeof Notification === 'undefined' ? 'unsupported' : Notification.permission);
  const canNotify = $derived(permission === 'granted');
  let pushNote = $state('');
  pushActive().then((on) => (pushOn = on));
  async function enableNotifications() {
    if (pushSupported()) {
      const err = await enablePush();
      pushOn = !err;
      pushNote = err ? `${err} Notifications will show while Chorus is open.` : '';
      permission = Notification.permission;
      if (!err) return;
    }
    permission = await Notification.requestPermission();
  }
  async function sendTest() {
    const err = await testPush();
    pushNote = err ? `The test didn't go out: ${err}` : 'Sent.';
  }
  const status = $derived(pushOn ? ['Notifications are on', 'Arriving through push']
    : canNotify ? ['Notifications are on', 'While Chorus is open']
    : permission === 'denied' ? ['Notifications are blocked', 'Blocked for this site']
    : permission === 'unsupported' ? ['Notifications are off', 'This browser can’t show them'] : ['Notifications are off', null]);
  const canAsk = $derived(permission === 'default' || (permission === 'granted' && pushSupported() && !pushOn));
</script>

<section class="page">
  <PageHeader title="Notifications" />
  <FormCard label="This browser">
    <div class="status">
      <span class="disc" class:on={canNotify || pushOn}><Icon name={canNotify || pushOn ? 'bell' : 'bell-off'} size={22} /></span>
      <span class="text"><strong>{status[0]}</strong>{#if status[1]}<span>{status[1]}</span>{/if}</span>
      {#if pushOn}<Button size="sm" variant="soft" onclick={sendTest}>Send a test</Button>
      {:else if canAsk}<Button size="sm" variant="soft" onclick={enableNotifications}>Turn on</Button>{/if}
    </div>
    {#if pushOn}<button class="row" onclick={async () => { await disablePush(); pushOn = false; }}>Turn off in this browser</button>{/if}
  </FormCard>
  {#if pushNote}<p class="note" role="status">{pushNote}</p>{/if}
  <SectionLabel label="Notify me about" />
  <FormCard label="Notify me about">
    <Switch label="Mentions" checked={kinds.mention !== false} onchange={(on) => chatKind('mention', on)} />
    <Switch label="Direct messages" checked={kinds.dm !== false} onchange={(on) => chatKind('dm', on)} />
    <Switch label="Replies" checked={kinds.reply !== false} onchange={(on) => chatKind('reply', on)} />
    <Switch label="Poll nudges" checked={kinds.poll_nudge !== false} onchange={(on) => chatKind('poll_nudge', on)} />
    {#if isSystem}<Switch label="Our own switches" checked={kinds.own_switch === true} onchange={(on) => chatKind('own_switch', on)} />{/if}
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
  {#if isSystem}
    <FormCard>
      <Switch label="Reply as the mentioned member" checked={kinds.reply_as_mentioned === true} onchange={(on) => chatKind('reply_as_mentioned', on)} />
    </FormCard>
  {/if}
</section>

<style>
  .page { display: grid; gap: var(--s-3); max-width: 720px; align-content: start; }
  .times { display: flex; align-items: center; gap: 10px; padding: 10px 14px; color: var(--ink-3); }
  input[type='time'] { font: inherit; color: var(--ink); background: var(--surface); border: 1px solid var(--line); border-radius: var(--r-sm); padding: 6px 10px; }
  .row { display: block; width: 100%; min-height: 46px; padding: 10px 14px; border: 0; background: none; color: var(--ink); font: inherit; font-size: var(--fs-md); text-align: left; cursor: pointer; margin: 0; }
  .status { display: flex; align-items: center; gap: 14px; padding: var(--s-3) 14px; }
  .disc { width: 40px; height: 40px; flex: none; display: grid; place-items: center; border-radius: 50%; background: var(--surface-3, var(--surface)); color: var(--ink-3); }
  .disc.on { background: var(--accent-soft); color: var(--accent); }
  .text { flex: 1; min-width: 0; display: grid; }
  .text strong { font-size: var(--fs-md); font-weight: 600; }
  .text span { color: var(--ink-3); font-size: var(--fs-sm); }
  .note { margin: 0; color: var(--ink-3); font-size: var(--fs-sm); padding: 0 var(--s-1); }
</style>
