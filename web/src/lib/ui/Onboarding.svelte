<script lang="ts">
  // Join Chorus on one screen (F1.1/F1.5, as Android's ui/Onboarding.kt): the invite, a name, a
  // handle and what this device is called, with "I am plural" (D-093: on = a system, off = one
  // person; the invite's kind sets it). A link for another device of an account needs only the link.
  import { enrol, inviteCode, peekInvite, relinkPlan, type InvitePeek } from '../sync/device';
  import { sync } from '../sync/client';
  import { wipeIn } from '../sync/lifecycle';
  import { homeStatus, homeSetupCode } from '../home';
  import { setupOwed } from '../setup';
  import Icon from './Icon.svelte';
  import Wordmark from './kit/Wordmark.svelte';
  import WelcomeBlobs from './kit/WelcomeBlobs.svelte';

  // Chorus Home's first run (FLOWS F2.2): on the PC itself, no invite is needed
  let homeSetup = $state(false);
  homeStatus().then((s) => { homeSetup = !!s?.needs_setup; });

  const linked = new URLSearchParams(location.search).get('invite') ?? location.pathname.match(/\/i\/(.+)$/)?.[1] ?? '';
  let invite = $state(linked);
  let plural = $state(true);
  let name = $state('');
  let handle = $state('');
  let device = $state('Browser');
  let busy = $state(false);
  let looking = $state(false);
  let error = $state('');

  // signed out from another device (D-152, D-153): the copy this browser still holds waits until
  // `wipe_at`; a device link of the same account picks it up, anything else erases it first
  const signedOut = sync.signedOut;
  const kept = signedOut ? sync.device?.account_id ?? null : null;
  const wipeOn = signedOut && (wipeIn(signedOut, Date.now()) ?? 0) > 0
    ? new Date(signedOut.wipe_at! - signedOut.offset).toLocaleString(undefined, { dateStyle: 'long', timeStyle: 'short' }) : '';
  // undefined: not looked at yet; kind null: a server that can't say, so one form for both
  let peek = $state<InvitePeek | undefined>(undefined);
  const plan = $derived(relinkPlan(kept, homeSetup ? { kind: 'system', account_id: null } : peek ?? null));
  let confirming = $state(false);
  let erased = false;
  const why = { 'other-account': 'This link is for a different account', 'new-account': 'This invite starts a new account',
    'unknown-account': 'This server can’t say which account the link is for' } as Record<string, string>;

  const forDevice = $derived(!homeSetup && peek?.kind === 'device');
  const legacy = $derived(!homeSetup && peek !== undefined && peek.kind === null);
  const code = $derived(inviteCode(invite));
  const shown = $derived(code !== invite.trim() ? code : '');
  const ready = $derived(device.trim() !== '' && (homeSetup || (!!invite.trim() && !!peek)) &&
    (forDevice || (name.trim() !== '' && (handle === '' || handle.length >= 2))));

  // look at the invite as soon as it's a link (D-093's peek): the toggle follows it
  $effect(() => {
    const text = invite.trim();
    peek = undefined;
    if (homeSetup || !text) return;
    const t = setTimeout(async () => {
      looking = true;
      error = '';
      try {
        const p = await peekInvite(text);
        if (p.kind === 'system' || p.kind === 'person') plural = p.kind === 'system';
        peek = p;
      } catch (err) {
        error = err instanceof Error ? err.message : String(err);
      } finally {
        looking = false;
      }
    }, text === linked ? 0 : 300);
    return () => clearTimeout(t);
  });

  async function paste() {
    try { invite = (await navigator.clipboard.readText()).trim(); } catch { /* no clipboard access: type it */ }
  }

  async function submit(e: SubmitEvent) {
    e.preventDefault();
    if (!ready || busy) return;
    if (plan !== 'none' && plan !== 'pick-up' && !confirming) {
      confirming = true;
      return;
    }
    busy = true;
    error = '';
    try {
      // erased first, as the person was told; another tab enrolling meanwhile stops it here
      if (confirming && !erased) {
        if (!(await sync.eraseKept())) return;
        erased = true;
      }
      const link = homeSetup ? await homeSetupCode() : invite;
      const account = forDevice ? null
        : { display_name: name.trim() || undefined, handle: handle || undefined, kind: legacy ? undefined : plural ? 'system' as const : 'person' as const };
      const dev = await enrol(link, account, device.trim());
      history.replaceState(null, '', '/');
      // a new system goes through setup next (F1.2-F1.4), which also names it
      if (account && plural && !legacy) setupOwed.begin(dev.account_id, name.trim());
      await sync.adopt(dev);
      // Home's first run continues on *This computer*: add a phone (the router follows hash changes)
      if (homeSetup) location.hash = '#/computer?welcome';
    } catch (err) {
      error = err instanceof Error ? err.message : String(err);
    } finally {
      busy = false;
    }
  }
</script>

<WelcomeBlobs />
<main>
  <h1 class="art"><Wordmark /><span class="tagline">VOICE FOR THE MANY</span></h1>
  <form onsubmit={submit}>
    {#if signedOut || sync.wasErased}
      <p class="note">This browser was signed out from another device.{#if wipeOn} What it still holds will be
        erased on {wipeOn} unless it’s linked to the same account again before then.{/if}</p>
    {/if}
    {#if !homeSetup}
      <label class="field">
        <span class="caps">Invite code</span>
        <span class="line">
          <input bind:value={invite} disabled={busy} autocomplete="off" placeholder="chrs.…/i/…" class:masked={shown && !!peek} />
          {#if shown && peek}<span class="code" aria-hidden="true">{shown}</span>{/if}
          {#if looking}<span class="spin" aria-label="Looking"></span>
          {:else if peek}<span class="tick"><Icon name="check" size={20} /></span>
          {:else if !invite}<button type="button" class="paste" aria-label="Paste" onclick={paste}><Icon name="copy" size={18} /></button>{/if}
        </span>
      </label>
    {/if}
    {#if !forDevice}
      <label class="field">
        <span class="caps">{legacy ? 'Name' : plural ? 'System name' : 'Your name'}</span>
        <span class="line">
          <input bind:value={name} maxlength="100" disabled={busy} autocomplete="off" placeholder={plural ? 'e.g. The Lanterns' : 'e.g. Alex'} />
          {#if name.trim()}<span class="tick"><Icon name="check" size={20} /></span>{/if}
        </span>
      </label>
      <label class="field">
        <span class="caps">Handle</span>
        <span class="line">
          {#if handle}<span class="at">@</span>{/if}
          <input value={handle} oninput={(e) => (handle = e.currentTarget.value.replace(/^@/, '').toLowerCase().replace(/[^a-z0-9_]/g, '').slice(0, 32))}
            disabled={busy} autocomplete="off" placeholder={plural ? '@lanterns' : '@alex'} />
          {#if handle.length >= 2}<span class="tick"><Icon name="check" size={20} /></span>{/if}
        </span>
      </label>
    {/if}
    <label class="field">
      <span class="caps">This device</span>
      <span class="line">
        <input bind:value={device} maxlength="60" disabled={busy} autocomplete="off" placeholder="Browser" />
        {#if device.trim()}<span class="tick"><Icon name="check" size={20} /></span>{/if}
      </span>
    </label>
    {#if !forDevice && !legacy}
      <button type="button" class="plural" role="switch" aria-checked={plural} disabled={busy} onclick={() => (plural = !plural)}>
        <span>I am {#if !plural}<b>not</b> {/if}plural</span>
        <span class="track"><span class="knob"></span></span>
      </button>
    {/if}
    {#if error}<p class="error" role="alert">{error}</p>{/if}
    {#if confirming}
      <p class="error" role="alert">{why[plan]}, so this browser’s copy of the account it was signed out of is
        erased first, including anything written here that never synced.</p>
    {/if}
    <div class="buttons">
      <button class="go" disabled={busy || !ready}>{confirming ? 'Erase and continue' : forDevice ? 'Link' : 'Continue'}
        {#if busy}<span class="spin dark"></span>{:else}<Icon name="chevron" size={16} />{/if}</button>
    </div>
  </form>
</main>

<style>
  main {
    --cream: #fbf3e5; --ink: #1f5e57; --sun: #f0bd1b;
    position: relative; z-index: 1; min-height: 100dvh; box-sizing: border-box;
    display: flex; flex-direction: column; align-items: center; justify-content: space-evenly; gap: var(--s-8);
    padding: 40px 38px 28px; color: var(--cream);
  }
  .art { display: grid; justify-items: center; gap: var(--s-1); margin: 0; font: inherit; width: min(272px, 100%); }
  .tagline { font-weight: 700; font-size: var(--fs-md); letter-spacing: 1px; color: var(--cream); }
  form { display: grid; gap: var(--s-3); width: 100%; max-width: 314px; }
  .note { margin: 0; font-size: 14px; line-height: 1.4; }
  .field { display: grid; gap: 0; padding: 6px var(--s-2) 6px 10px; min-height: 48px; box-sizing: border-box; border-radius: var(--r-sm); background: rgb(251 243 229 / 0.86); color: var(--ink); }
  .caps { font-size: 9px; line-height: 12px; font-weight: 700; letter-spacing: 0.4px; text-transform: uppercase; }
  .line { display: flex; align-items: center; gap: var(--s-1); position: relative; }
  input { flex: 1; min-width: 0; border: 0; outline: 0; background: none; padding: 0; font: inherit; font-size: var(--fs-md); color: var(--ink); }
  input::placeholder { color: var(--ink); opacity: 0.45; font-style: italic; }
  input.masked { color: transparent; }
  input.masked:focus { color: var(--ink); }
  input.masked:focus ~ .code { display: none; }
  .code { position: absolute; inset: 0 28px 0 0; pointer-events: none; font-size: var(--fs-md); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .at { font-size: var(--fs-md); }
  .tick { display: grid; color: #3da599; }
  .paste { display: grid; place-items: center; width: 36px; height: 36px; margin: -8px -4px; border: 0; border-radius: 8px; background: none; color: var(--ink); cursor: pointer; }
  .spin { width: 18px; height: 18px; border-radius: 50%; border: 2px solid #3da599; border-top-color: transparent; animation: spin 0.8s linear infinite; }
  .spin.dark { width: 14px; height: 14px; border-color: var(--ink); border-top-color: transparent; }
  @keyframes spin { to { transform: rotate(360deg); } }
  .plural { justify-self: end; display: flex; align-items: center; gap: var(--s-2); padding: var(--s-1); border: 0; background: none; color: var(--cream); font: inherit; font-size: var(--fs-md); font-weight: 500; cursor: pointer; }
  .plural b { color: var(--sun); }
  .track { display: flex; width: 36px; height: 20px; box-sizing: border-box; padding: 3px; border: 2px solid var(--cream); border-radius: var(--r-sm); }
  .knob { width: 14px; height: 14px; border-radius: 50%; background: var(--cream); transition: margin 0.15s; }
  .plural[aria-checked='true'] .knob { margin-left: auto; }
  .error { margin: 0; padding: var(--s-2) var(--s-3); border-radius: var(--r-row); background: var(--cream); color: #9e2a1e; font-size: var(--fs-sm); line-height: 18px; }
  .buttons { display: flex; justify-content: flex-end; padding-top: 2px; }
  .go { display: flex; align-items: center; gap: var(--s-2); min-height: 38px; padding: 9px var(--s-4); border: 0; border-radius: 19px; background: var(--cream); color: var(--ink); font: inherit; font-size: 14px; font-weight: 700; letter-spacing: 0.3px; text-transform: uppercase; cursor: pointer; }
  .go:disabled { opacity: 0.6; cursor: default; }
</style>
