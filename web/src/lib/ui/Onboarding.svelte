<script lang="ts">
  import { enrol, inviteKind } from '../sync/device';
  import { sync } from '../sync/client';
  import { homeStatus, homeSetupCode } from '../home';

  // Chorus Home's first run (FLOWS F2.2): on the PC itself, no invite is needed
  let homeSetup = $state(false);
  homeStatus().then((s) => {
    homeSetup = !!s?.needs_setup;
    if (homeSetup) step = 'account';
  });

  const linked = new URLSearchParams(location.search).get('invite') ?? location.pathname.match(/\/i\/(.+)$/)?.[1] ?? '';
  let invite = $state(linked);
  // link: paste it · account: a new account, so system or just me (D-093) · device: another device ·
  // legacy: a server that can't say, so one form for both
  let step = $state<'link' | 'account' | 'device' | 'legacy'>('link');
  let kind = $state<'system' | 'person'>('system');
  let name = $state('');
  let handle = $state('');
  let device = $state('Browser');
  let busy = $state(false);
  let error = $state('');

  async function look(e?: SubmitEvent) {
    e?.preventDefault();
    if (!invite.trim()) return;
    busy = true;
    error = '';
    try {
      const k = await inviteKind(invite);
      if (k === 'system' || k === 'person') kind = k;
      step = k === 'device' ? 'device' : k ? 'account' : 'legacy';
    } catch (err) {
      error = err instanceof Error ? err.message : String(err);
    } finally {
      busy = false;
    }
  }
  if (linked) void look();

  async function submit(e: SubmitEvent) {
    e.preventDefault();
    busy = true;
    error = '';
    try {
      const code = homeSetup ? await homeSetupCode() : invite;
      const account = step === 'device' ? null
        : { display_name: name || undefined, handle: handle || undefined, kind: step === 'account' ? kind : undefined };
      const dev = await enrol(code, account, device);
      history.replaceState(null, '', '/');
      await sync.adopt(dev);
      // Home's first run continues on *This computer*: add a phone (the router follows hash changes)
      if (homeSetup) location.hash = '#/computer?welcome';
    } catch (err) {
      error = err instanceof Error ? err.message : String(err);
    } finally {
      busy = false;
    }
  }

  function another() {
    step = 'link';
    error = '';
  }
</script>

<main>
  <h1 class="display">Welcome to Chorus</h1>
  {#if step === 'link'}
    <p class="lead">A cozy place for plural systems and the people close to them. Paste the invite you were given to set up this browser.</p>
    <form onsubmit={look}>
      <label>
        <span>Invite link or code</span>
        <input bind:value={invite} required autocomplete="off" placeholder="https://…/i/…" />
      </label>
      {#if error}<p class="error" role="alert">{error}</p>{/if}
      <button disabled={busy || !invite.trim()}>{busy ? 'Checking…' : 'Continue'}</button>
    </form>
  {:else if step === 'device'}
    <p class="lead">This link adds this browser to an account you already have.</p>
    <form onsubmit={submit}>
      <label>
        <span>This device</span>
        <input bind:value={device} />
      </label>
      {#if error}<p class="error" role="alert">{error}</p>{/if}
      <button disabled={busy}>{busy ? 'Linking…' : 'Link this browser'}</button>
      <button type="button" class="ghost" onclick={another} disabled={busy}>Use another invite</button>
    </form>
  {:else}
    {#if homeSetup}
      <p class="lead">Chorus is installed on this computer. Tell it who's here to start.</p>
    {/if}
    <form onsubmit={submit}>
      {#if step === 'account'}
        <fieldset class="kinds">
          <legend>Who's joining?</legend>
          <label class="kind" class:on={kind === 'system'}>
            <input type="radio" name="kind" value="system" bind:group={kind} />
            <span><strong>We're a system</strong>More than one of us shares this body. Chorus keeps track of members, who's fronting and front history, and lets each of us post and chat as ourselves.</span>
          </label>
          <label class="kind" class:on={kind === 'person'}>
            <input type="radio" name="kind" value="person" bind:group={kind} />
            <span><strong>Just me</strong>I'm one person (a singlet). Follow friends, including plural ones, see who's fronting when they share it, and chat and post, without the switching tools.</span>
          </label>
        </fieldset>
      {/if}
      <label>
        <span>{step === 'legacy' ? 'Name' : kind === 'system' ? "Your system's name" : 'Your name'}{#if step === 'legacy'} <em>(your system, or you)</em>{/if}</span>
        <input bind:value={name} placeholder={kind === 'system' ? 'The Stars' : 'Alex'} />
      </label>
      <label>
        <span>Handle <em>(optional; friends follow you with it)</em></span>
        <input bind:value={handle} placeholder={kind === 'system' ? 'stars' : 'alex'} pattern="[a-z0-9_]{'{'}2,32{'}'}" />
      </label>
      <label>
        <span>This device</span>
        <input bind:value={device} />
      </label>
      {#if error}<p class="error" role="alert">{error}</p>{/if}
      <button disabled={busy}>{busy ? 'Setting up…' : 'Join'}</button>
      {#if !homeSetup}
        <button type="button" class="ghost" onclick={another} disabled={busy}>Use another invite</button>
        {#if step === 'legacy'}<p class="hint">Invites for another device of an existing account only need the link.</p>{/if}
      {/if}
    </form>
  {/if}
</main>

<style>
  main {
    max-width: 440px;
    margin: 0 auto;
    padding: var(--s-12) var(--s-4);
    display: grid;
    gap: var(--s-4);
  }
  h1 {
    font-size: var(--fs-2xl);
  }
  .lead {
    color: var(--ink-2);
    margin: 0;
  }
  form {
    display: grid;
    gap: var(--s-4);
    padding: var(--s-6);
    background: var(--surface);
    border: 1px solid var(--line);
    border-radius: var(--r-lg);
  }
  label {
    display: grid;
    gap: var(--s-1);
    font-size: var(--fs-sm);
    color: var(--ink-2);
  }
  em {
    color: var(--ink-3);
    font-style: normal;
  }
  input {
    font: inherit;
    font-size: var(--fs-base);
    color: var(--ink);
    background: var(--surface-2);
    border: 1px solid var(--line);
    border-radius: var(--r-sm);
    padding: var(--s-2) var(--s-3);
  }
  .kinds {
    border: 0;
    padding: 0;
    margin: 0;
    display: grid;
    gap: var(--s-2);
  }
  legend {
    font-size: var(--fs-lg);
    font-weight: 600;
    color: var(--ink);
    margin-bottom: var(--s-2);
  }
  .kind {
    display: grid;
    grid-template-columns: auto 1fr;
    gap: var(--s-3);
    align-items: start;
    padding: var(--s-3) var(--s-4);
    border: 1px solid var(--line);
    border-radius: var(--r-md);
    cursor: pointer;
    line-height: 1.45;
  }
  .kind.on {
    border-color: var(--accent);
    box-shadow: inset 0 0 0 1px var(--accent);
    background: var(--accent-soft);
  }
  .kind input {
    margin-top: 0.3em;
    accent-color: var(--accent);
  }
  .kind strong {
    display: block;
    color: var(--ink);
    font-size: var(--fs-base);
    margin-bottom: var(--s-1);
  }
  button {
    background: var(--accent);
    color: var(--on-accent);
    border: 0;
    border-radius: var(--r-full);
    padding: var(--s-3);
    font-weight: 600;
    cursor: pointer;
  }
  button.ghost {
    background: none;
    color: var(--accent);
    padding: var(--s-1);
  }
  button:disabled {
    opacity: 0.6;
  }
  .error {
    color: var(--danger);
    margin: 0;
  }
  .hint {
    color: var(--ink-3);
    font-size: var(--fs-xs);
    margin: 0;
  }
</style>
