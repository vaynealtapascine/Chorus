<script lang="ts">
  // Webhooks (API.md §7; Figma F9.4): signed JSON events posted to your own URL.
  import TextField from '../kit/TextField.svelte';
  import Button from '../kit/Button.svelte';
  import Icon from '../Icon.svelte';
  import Sheet from '../Sheet.svelte';
  import ConfirmSheet from '../kit/ConfirmSheet.svelte';
  import EmptyState from '../kit/EmptyState.svelte';
  import FormCard from '../kit/FormCard.svelte';
  import PageHeader from '../kit/PageHeader.svelte';
  import Switch from '../kit/Switch.svelte';
  import { settingsApi } from '../../settings-api';

  interface Hook { id: string; url: string; events: string[]; is_enabled: boolean; last_status: number | null; last_error: string | null }
  const EVENTS = [
    { id: 'front.switch', label: 'Front changes' },
    { id: 'member.created', label: 'New members' },
    { id: 'member.updated', label: 'Member edits' },
    { id: 'follow.requested', label: 'Follow requests' },
    { id: 'message.created', label: 'Your messages' },
    { id: 'post.created', label: 'Your posts' },
  ];
  let hooks = $state<Hook[]>([]);
  let url = $state('');
  let events = $state<string[]>(['front.switch']);
  let secret = $state<string | null>(null);
  let notes = $state<Record<string, string>>({});
  let error = $state('');
  let adding = $state(false);
  let editing = $state<Hook | null>(null);
  let removing = $state<Hook | null>(null);

  async function load() {
    try { hooks = (await settingsApi('/webhooks')).items; } catch (e) { error = e instanceof Error ? e.message : String(e); }
  }
  void load();

  async function add(e: SubmitEvent) {
    e.preventDefault();
    error = '';
    try {
      const h = await settingsApi('/webhooks', { method: 'POST', body: JSON.stringify({ url: url.trim(), events }) });
      secret = h.secret;
      url = '';
      adding = false;
      await load();
    } catch (err) {
      error = err instanceof Error ? err.message : String(err);
    }
  }
  async function test(id: string) {
    notes = { ...notes, [id]: 'Sending…' };
    try {
      const r = await settingsApi(`/webhooks/${id}/test`, { method: 'POST' });
      notes = { ...notes, [id]: r.ok ? `Delivered (HTTP ${r.status})` : `Failed: ${r.error}` };
    } catch (err) {
      notes = { ...notes, [id]: err instanceof Error ? err.message : String(err) };
    }
    await load();
  }
  async function toggle(h: Hook) {
    await settingsApi(`/webhooks/${h.id}`, { method: 'PUT', body: JSON.stringify({ enabled: !h.is_enabled }) });
    await load();
    editing = hooks.find((x) => x.id === h.id) ?? null;
  }
  async function remove(h: Hook) {
    removing = null;
    editing = null;
    await settingsApi(`/webhooks/${h.id}`, { method: 'DELETE' });
    await load();
  }
</script>

<section class="page">
  <PageHeader title="Webhooks" sub="Tell another service when something happens">
    {#snippet action()}<button class="add" onclick={() => (adding = true)}><Icon name="plus" size={18} />Add webhook</button>{/snippet}
  </PageHeader>
  {#if error}<p class="error" role="alert">{error}</p>{/if}
  {#if secret}
    <div class="card">
      <h2>Signing secret, shown once</h2>
      <code>{secret}</code>
      <button class="link" onclick={() => (secret = null)}>Done</button>
    </div>
  {/if}
  {#each hooks as h (h.id)}
    <div class="card">
      <div class="head">
        <span class="tile"><Icon name="globe" size={20} /></span>
        <strong class="url">{h.url}</strong>
        <Button size="sm" class="pill" onclick={() => test(h.id)}><Icon name="send" size={16} />Test</Button>
        <Button size="sm" class="pill" onclick={() => (editing = h)}><Icon name="edit" size={16} />Edit</Button>
      </div>
      <div class="chips">{#each h.events as ev (ev)}<code>{ev}</code>{/each}{#if !h.is_enabled}<code class="off">off</code>{/if}</div>
      {#if notes[h.id] || h.last_status || h.last_error}
        <span class="hint">{notes[h.id] ?? (h.last_status ? `Last delivery HTTP ${h.last_status}` : '')}</span>
        {#if h.last_error}<span class="error">{h.last_error}</span>{/if}
      {/if}
    </div>
  {:else}
    <div class="card"><EmptyState icon="globe" label="No webhooks" action="Add webhook" onaction={() => (adding = true)} /></div>
  {/each}
</section>

{#if adding}
  <Sheet title="Add webhook" onclose={() => (adding = false)}>
    <form id="new-hook" onsubmit={add}>
      <TextField outlined bind:value={url} type="url" placeholder="https://…" aria-label="Webhook URL" required />
      <FormCard label="Send when">
        {#each EVENTS as ev (ev.id)}
          <Switch label={ev.label} checked={events.includes(ev.id)} onchange={(on) => (events = on ? [...events, ev.id] : events.filter((c) => c !== ev.id))} />
        {/each}
      </FormCard>
    </form>
    {#snippet footer()}<Button variant="primary" size="lg" grow class="primary" form="new-hook" disabled={!events.length || !url.trim()}>Add webhook</Button>{/snippet}
  </Sheet>
{/if}
{#if editing}
  <Sheet title="Webhook" subtitle={editing.url} onclose={() => (editing = null)}>
    <FormCard>
      <Switch label="On" checked={editing.is_enabled} onchange={() => toggle(editing!)} />
    </FormCard>
    {#snippet footer()}<Button variant="danger-soft" size="lg" grow class="danger" onclick={() => (removing = editing)}>Remove</Button>{/snippet}
  </Sheet>
{/if}
{#if removing}
  <ConfirmSheet title="Remove this webhook?" confirm="Remove" danger layer={1} onconfirm={() => remove(removing!)} onclose={() => (removing = null)} />
{/if}

<style>
  .page { display: grid; gap: var(--s-3); max-width: 720px; align-content: start; }
  .add { display: inline-flex; align-items: center; gap: 6px; min-height: 40px; padding: 0 var(--s-4); border: 0; border-radius: var(--r-full); background: var(--accent); color: var(--on-accent); font: inherit; font-size: 14px; font-weight: 600; cursor: pointer; }
  .card { display: grid; gap: var(--s-3); padding: 14px; border-radius: var(--r-md); background: var(--surface-2); }
  h2 { margin: 0; font-size: var(--fs-base); }
  .head { display: flex; align-items: center; gap: 10px; }
  .tile { display: grid; place-items: center; width: 40px; height: 40px; border-radius: var(--r-row); background: var(--accent-soft); color: var(--accent); flex: none; }
  .url { flex: 1; min-width: 0; font-family: var(--font-mono); font-size: 14px; font-weight: 500; word-break: break-all; }
  .chips { display: flex; flex-wrap: wrap; gap: 6px; }
  code { font-family: var(--font-mono); background: var(--surface-3); padding: 3px 10px; border-radius: var(--r-full); font-size: var(--fs-xs); word-break: break-all; }
  code.off { color: var(--ink-3); }
  .hint { color: var(--ink-3); font-size: var(--fs-sm); }
  .error { color: var(--danger); font-size: var(--fs-sm); margin: 0; }
  .link { border: 0; background: none; padding: 0; color: var(--accent); font: inherit; cursor: pointer; justify-self: start; }
  form { display: grid; gap: var(--s-3); }
</style>
