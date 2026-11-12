<script lang="ts">
  import PageHeader from './kit/PageHeader.svelte';
  import Button from './kit/Button.svelte';
  import FormCard from './kit/FormCard.svelte';
  import SectionLabel from './kit/SectionLabel.svelte';
  import Switch from './kit/Switch.svelte';
  import TextField from './kit/TextField.svelte';
  // Chorus Home's *This computer* page (D-071, FLOWS F2.3–F2.4): add a phone over the home wifi,
  // and the few settings a home install has, instead of editing chorus.toml.
  import { onMount } from 'svelte';
  import { apiBase } from '../sync/device';
  import { apiFetch } from '../http';
  import { sync } from '../sync/client';
  import { homeStatus, saveHomeSettings, waitForServer, type HomeStatus } from '../home';

  let status = $state<HomeStatus | null>(null);
  let loaded = $state(false);
  let qr = $state('');
  let phoneLink = $state('');
  let error = $state('');
  let port = $state(5250);
  let lan = $state(true);
  let keepDaily = $state(14);
  let saving = $state(false);
  let note = $state('');
  const welcome = location.hash.includes('welcome');

  onMount(async () => {
    status = await homeStatus();
    loaded = true;
    if (status) {
      port = status.port ?? 5250;
      lan = status.lan;
      keepDaily = status.backup.keep_daily;
      if (welcome && status.lan) void addPhone();
    }
  });

  async function addPhone() {
    error = '';
    try {
      const r = await apiFetch(`${apiBase()}/devices/invite`, {
        method: 'POST',
        headers: { authorization: `Bearer ${sync.device?.session ?? ''}` },
      });
      const j = await r.json();
      if (!r.ok) throw new Error(j?.error?.message ?? `HTTP ${r.status}`);
      qr = j.qr_svg ?? '';
      phoneLink = j.lan_url ?? j.url;
    } catch (e) {
      error = e instanceof Error ? e.message : String(e);
    }
  }

  async function save(e: SubmitEvent) {
    e.preventDefault();
    saving = true;
    error = '';
    note = 'Saving… Chorus restarts for a few seconds.';
    try {
      const url = await saveHomeSettings({ port: Number(port), lan, keep_daily: Number(keepDaily) });
      if (await waitForServer(url)) {
        if (new URL(url).port !== location.port) location.href = `${url}#/computer`;
        else location.reload();
      } else {
        note = `Chorus hasn't come back yet. Try ${url} in a moment.`;
      }
    } catch (err) {
      error = err instanceof Error ? err.message : String(err);
      note = '';
    } finally {
      saving = false;
    }
  }
</script>

<section class="computer">
  <PageHeader title="This computer" />
  {#if !loaded}
    <p class="muted">Looking…</p>
  {:else if !status}
    <p class="muted">These settings are only here on the computer Chorus Home runs on, in its own browser.</p>
  {:else}
    {#if welcome}<p class="lead">You're set up. Chorus runs in the background and starts with Windows.</p>{/if}

    <SectionLabel label="Add a phone" />
    <div class="card">
      {#if status.lan}
        <ol>
          <li>Install the Chorus app on the phone (<a href="https://github.com/vaynealtapascine/Chorus/releases/latest" target="_blank" rel="noreferrer">download page</a>).</li>
          <li>Connect the phone to the same wifi as this computer.</li>
          <li>Scan this code with the phone's camera. It opens Chorus and links the phone.</li>
        </ol>
        {#if qr}
          <div class="qr" aria-label="Code for the phone">{@html qr}</div>
          <p class="hint">Works once, for a day. <button class="ghost" onclick={addPhone}>New code</button></p>
          <details><summary>Can't scan?</summary><code>{phoneLink}</code><p class="hint">Paste this into the app's invite box.</p></details>
        {:else}
          <Button variant="primary" block onclick={addPhone}>Show the code</Button>
        {/if}
        <p class="hint">
          Other browsers on the wifi (a laptop, an iPhone) will see a security warning and can't work offline.
          For those, the Android app or Tailscale works better.
        </p>
      {:else}
        <p class="muted">Phones can't connect: "Let devices on my wifi connect" is off below.</p>
      {/if}
    </div>

    <form onsubmit={save}>
      <SectionLabel label="Settings" />
      <FormCard>
        <Switch label="Let devices on my wifi connect" bind:checked={lan} />
        <label class="field"><span>Keep this many daily backups</span><TextField type="number" min="1" max="365" bind:value={keepDaily} /></label>
        <details>
          <summary>Advanced</summary>
          <label class="field">
            <span>Port on this computer</span>
            <TextField type="number" min="1024" max="65534" bind:value={port} />
            <small class="hint">Phones use the next one ({Number(port) + 1}). Change it only if something else needs {status.port}.</small>
          </label>
        </details>
      </FormCard>
      {#if note}<p class="hint" role="status">{note}</p>{/if}
      {#if error}<p class="error" role="alert">{error}</p>{/if}
      <div class="save"><Button variant="primary" block disabled={saving}>{saving ? 'Restarting…' : 'Save'}</Button></div>
    </form>

    <SectionLabel label="About" />
    <div class="card facts">
      <p>Chorus Home {status.version}</p>
      {#if status.lan_address}<p>On the wifi at <code>{status.lan_address}</code></p>{/if}
      <p>Data: <code>{status.data_dir}</code></p>
      <p>Backups: <code>{status.backup.dir}</code></p>
      <p class="hint">Remove Chorus Home from Windows Settings → Apps. Your data stays unless you choose otherwise.</p>
    </div>
  {/if}
</section>

<style>
  .computer { display: grid; gap: var(--s-1); max-width: 620px; align-content: start; }
  .lead { color: var(--ink-2); margin: 0; }
  .card { display: grid; gap: var(--s-3); padding: var(--s-4); background: var(--surface-2); border-radius: var(--r-md); }
  ol { margin: 0; padding-left: var(--s-5); color: var(--ink-2); display: grid; gap: var(--s-1); }
  .qr { width: min(240px, 100%); justify-self: center; background: #fff; padding: var(--s-3); border-radius: var(--r-md); }
  .qr :global(svg) { width: 100%; height: auto; display: block; }
  .field { display: grid; gap: var(--s-1); padding: var(--s-3) var(--s-4); font-size: var(--fs-sm); color: var(--ink-2); }
  .field :global(.field) { background: var(--surface); max-width: 12ch; }
  details { padding: var(--s-3) var(--s-4); color: var(--ink-2); }
  details .field { padding-inline: 0; }
  summary { cursor: pointer; }
  .save { padding-top: var(--s-3); }
  form > :global(.hint), form > .error { margin: var(--s-2) var(--s-1) 0; }
  .facts p { margin: 0; color: var(--ink-2); }
  .ghost { border: 0; background: none; padding: 0; color: var(--accent); font: inherit; cursor: pointer; }
  code { font-size: var(--fs-sm); word-break: break-all; }
  .hint, .muted { color: var(--ink-3); font-size: var(--fs-sm); margin: 0; }
  .error { color: var(--danger); margin: 0; }
</style>
