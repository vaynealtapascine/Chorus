<script lang="ts">
  import { enrol } from '../sync/device';
  import { sync } from '../sync/client';

  let invite = $state(new URLSearchParams(location.search).get('invite') ?? location.pathname.match(/\/i\/(.+)$/)?.[1] ?? '');
  let name = $state('');
  let handle = $state('');
  let device = $state('Browser');
  let busy = $state(false);
  let error = $state('');

  async function submit(e: SubmitEvent) {
    e.preventDefault();
    busy = true;
    error = '';
    try {
      const dev = await enrol(invite, { display_name: name || undefined, handle: handle || undefined }, device);
      history.replaceState(null, '', '/');
      await sync.adopt(dev);
    } catch (err) {
      error = err instanceof Error ? err.message : String(err);
    } finally {
      busy = false;
    }
  }
</script>

<main>
  <h1 class="display">Welcome to Chorus</h1>
  <p class="lead">A cozy home for your system. Paste the invite you were given to set up this browser.</p>
  <form onsubmit={submit}>
    <label>
      <span>Invite link or code</span>
      <input bind:value={invite} required autocomplete="off" placeholder="https://…/i/…" />
    </label>
    <label>
      <span>Name <em>(your system, or you)</em></span>
      <input bind:value={name} placeholder="The Stars" />
    </label>
    <label>
      <span>Handle <em>(optional, for mentions)</em></span>
      <input bind:value={handle} placeholder="stars" pattern="[a-z0-9_]{'{'}2,32{'}'}" />
    </label>
    <label>
      <span>This device</span>
      <input bind:value={device} />
    </label>
    {#if error}<p class="error" role="alert">{error}</p>{/if}
    <button disabled={busy}>{busy ? 'Setting up…' : 'Continue'}</button>
    <p class="hint">Invites for another device of an existing account only need the link.</p>
  </form>
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
  button {
    background: var(--accent);
    color: var(--surface);
    border: 0;
    border-radius: var(--r-full);
    padding: var(--s-3);
    font-weight: 600;
    cursor: pointer;
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
