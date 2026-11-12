<script lang="ts">
  // Personal API tokens (API.md §2.3, §3, §6; Figma F9.3): read-only or log-a-switch, limited to your
  // own account. The secret is shown once.
  import TextField from '../kit/TextField.svelte';
  import Button from '../kit/Button.svelte';
  import Icon from '../Icon.svelte';
  import Sheet from '../Sheet.svelte';
  import ConfirmSheet from '../kit/ConfirmSheet.svelte';
  import EmptyState from '../kit/EmptyState.svelte';
  import FormCard from '../kit/FormCard.svelte';
  import PageHeader from '../kit/PageHeader.svelte';
  import Switch from '../kit/Switch.svelte';
  import { ago } from '../../ago';
  import { settingsApi } from '../../settings-api';
  import { apiBase } from '../../sync/device';

  interface Token { id: string; name: string; scopes: string[]; created_at: number; last_used_at: number | null }
  const SCOPES = [
    { id: 'read:front', label: 'Who is fronting, switches and front history' },
    { id: 'read:members', label: 'Member list' },
    { id: 'read:messages', label: 'Read and search your messages' },
    { id: 'read:posts', label: 'Journal posts, lists and feeds' },
    { id: 'stream', label: 'Live stream (overlays)' },
    { id: 'write:front', label: 'Log switches (NFC tags, Tasker, Home Assistant)' },
    { id: 'write:messages', label: 'Send messages (bots, bridges)' },
    { id: 'export', label: 'Download your op log and data exports' },
  ];

  let tokens = $state<Token[]>([]);
  let name = $state('');
  let chosen = $state<string[]>(['read:front']);
  let fresh = $state<{ token: string; scopes: string[] } | null>(null);
  let error = $state('');
  let adding = $state(false);
  let revoking = $state<Token | null>(null);
  let copied = $state(false);
  const now = Date.now();
  const made = (t: number) => new Date(t).toLocaleDateString([], { day: 'numeric', month: 'short' });

  async function load() {
    try { tokens = (await settingsApi('/tokens')).items; } catch (e) { error = e instanceof Error ? e.message : String(e); }
  }
  void load();

  async function create(e: SubmitEvent) {
    e.preventDefault();
    error = '';
    try {
      const t = await settingsApi('/tokens', { method: 'POST', body: JSON.stringify({ name: name.trim(), scopes: chosen }) });
      fresh = { token: t.token, scopes: t.scopes };
      name = '';
      adding = false;
      await load();
    } catch (err) {
      error = err instanceof Error ? err.message : String(err);
    }
  }
  async function revoke(t: Token) {
    revoking = null;
    await settingsApi(`/tokens/${t.id}`, { method: 'DELETE' });
    await load();
  }
  async function copy(text: string) {
    await navigator.clipboard?.writeText(text);
    copied = true;
    setTimeout(() => (copied = false), 1500);
  }
  const overlay = (t: string) => `${location.origin}/overlay/front?token=${t}`;
</script>

<section class="page">
  <PageHeader title="API tokens" sub="For overlays, scripts and dashboards">
    {#snippet action()}<button class="add" onclick={() => (adding = true)}><Icon name="plus" size={18} />New token</button>{/snippet}
  </PageHeader>
  {#if error}<p class="error" role="alert">{error}</p>{/if}
  {#if fresh}
    <div class="card">
      <h2><span class="tile warn"><Icon name="target" size={18} /></span>New token shown once</h2>
      <div class="secret"><code>{fresh.token}</code><button class="copy" onclick={() => copy(fresh!.token)}><Icon name="copy" size={16} />{copied ? 'Copied' : 'Copy'}</button></div>
      {#if fresh.scopes.includes('stream') && fresh.scopes.includes('read:front')}
        <div class="secret"><code>{overlay(fresh.token)}</code><button class="copy" onclick={() => copy(overlay(fresh!.token))}><Icon name="copy" size={16} />Copy</button></div>
      {/if}
      <p class="hint">Scripts: <code>curl -H "Authorization: Bearer {fresh.token.slice(0, 12)}…" {apiBase()}/front</code></p>
      <button class="link" onclick={() => (fresh = null)}>Done</button>
    </div>
  {/if}
  <div class="card">
    <h2>Tokens<span class="count">{tokens.length}</span></h2>
    {#each tokens as t (t.id)}
      <div class="row">
        <span class="tile"><Icon name="globe" size={20} /></span>
        <div class="titles">
          <strong>{t.name}</strong>
          <span>{t.last_used_at ? `Last used ${ago(t.last_used_at, now)}` : 'Never used'} · made {made(t.created_at)}</span>
          <span class="chips">{#each t.scopes as s (s)}<code>{s}</code>{/each}</span>
        </div>
        <button class="revoke" onclick={() => (revoking = t)}>Revoke</button>
      </div>
    {:else}
      <EmptyState icon="globe" label="No tokens" action="New token" onaction={() => (adding = true)} />
    {/each}
  </div>
</section>

{#if adding}
  <Sheet title="New token" onclose={() => (adding = false)}>
    <form id="new-token" onsubmit={create}>
      <TextField outlined bind:value={name} placeholder="What it's for (e.g. OBS overlay)" aria-label="Token name" required />
      <FormCard label="What it can do">
        {#each SCOPES as s (s.id)}
          <Switch label={s.label} checked={chosen.includes(s.id)} onchange={(on) => (chosen = on ? [...chosen, s.id] : chosen.filter((c) => c !== s.id))} />
        {/each}
      </FormCard>
    </form>
    {#snippet footer()}<Button variant="primary" size="lg" grow class="primary" form="new-token" disabled={!chosen.length || !name.trim()}>Create token</Button>{/snippet}
  </Sheet>
{/if}
{#if revoking}
  <ConfirmSheet title="Revoke {revoking.name}?" confirm="Revoke" danger onconfirm={() => revoke(revoking!)} onclose={() => (revoking = null)} />
{/if}

<style>
  .page { display: grid; gap: var(--s-3); max-width: 720px; align-content: start; }
  .add { display: inline-flex; align-items: center; gap: 6px; min-height: 40px; padding: 0 var(--s-4); border: 0; border-radius: var(--r-full); background: var(--accent); color: var(--on-accent); font: inherit; font-size: 14px; font-weight: 600; cursor: pointer; }
  .card { display: grid; gap: 14px; padding: 14px; border-radius: var(--r-md); background: var(--surface-2); }
  h2 { display: flex; align-items: center; gap: 10px; margin: 0; font-size: var(--fs-base); }
  .count { margin-left: auto; color: var(--ink-3); font-size: 14px; font-weight: 400; }
  .row { display: flex; align-items: flex-start; gap: var(--s-3); }
  .tile { display: grid; place-items: center; width: 40px; height: 40px; border-radius: var(--r-row); background: var(--accent-soft); color: var(--accent); flex: none; }
  .tile.warn { width: 32px; height: 32px; border-radius: var(--r-sm); background: color-mix(in oklab, var(--sun) 22%, transparent); color: var(--sun); }
  .titles { flex: 1; min-width: 0; display: grid; gap: 2px; }
  .titles strong { font-size: var(--fs-md); }
  .titles span { color: var(--ink-3); font-size: var(--fs-sm); }
  .chips { display: flex; flex-wrap: wrap; gap: 6px; margin-top: var(--s-1); }
  code { font-family: var(--font-mono); background: var(--surface-3); padding: 2px var(--s-2); border-radius: 8px; font-size: var(--fs-xs); word-break: break-all; }
  .secret { display: flex; align-items: center; gap: 10px; padding: 10px var(--s-3); border-radius: var(--r-row); background: var(--surface-3); }
  .secret code { flex: 1; background: none; padding: 0; font-size: var(--fs-sm); }
  .copy { display: inline-flex; align-items: center; gap: 6px; border: 0; background: none; color: var(--ink); font: inherit; font-size: 14px; cursor: pointer; }
  .revoke, .link { border: 0; background: none; color: var(--danger); font: inherit; font-size: 14px; cursor: pointer; padding: 6px var(--s-2); }
  .link { color: var(--accent); justify-self: start; padding: 0; }
  .hint { margin: 0; color: var(--ink-3); font-size: var(--fs-sm); }
  .error { margin: 0; color: var(--danger); }
  form { display: grid; gap: var(--s-3); }
</style>
