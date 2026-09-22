<script lang="ts">
  // "Link another device": a one-use invite for this account (API.md §2.1).
  import { apiBase } from '../sync/device';
  import { sync } from '../sync/client';

  let link = $state('');
  let error = $state('');
  let busy = $state(false);
  let copied = $state(false);

  async function make() {
    busy = true;
    error = '';
    try {
      const r = await fetch(`${apiBase()}/devices/invite`, {
        method: 'POST',
        headers: { authorization: `Bearer ${sync.device?.session ?? ''}` },
      });
      const j = await r.json();
      if (!r.ok) throw new Error(j?.error?.message ?? `HTTP ${r.status}`);
      link = j.url;
    } catch (e) {
      error = e instanceof Error ? e.message : String(e);
    } finally {
      busy = false;
    }
  }

  async function copy() {
    await navigator.clipboard.writeText(link);
    copied = true;
    setTimeout(() => (copied = false), 1500);
  }
</script>

<section class="link" aria-label="Link another device">
  {#if link}
    <p>Open this on your other device (works once, for a day):</p>
    <div class="row">
      <code>{link}</code>
      <button class="ghost" onclick={copy}>{copied ? 'Copied' : 'Copy'}</button>
    </div>
  {:else}
    <button class="ghost" onclick={make} disabled={busy}>{busy ? 'Making a link…' : 'Link another device…'}</button>
  {/if}
  {#if error}<p class="error" role="alert">{error}</p>{/if}
</section>

<style>
  .link {
    display: grid;
    gap: var(--s-2);
    font-size: var(--fs-sm);
    color: var(--ink-2);
  }
  p {
    margin: 0;
  }
  .row {
    display: flex;
    gap: var(--s-2);
    align-items: center;
    flex-wrap: wrap;
  }
  code {
    font-family: var(--font-mono);
    background: var(--surface-2);
    padding: var(--s-1) var(--s-2);
    border-radius: var(--r-sm);
    word-break: break-all;
  }
  .ghost {
    justify-self: start;
    background: none;
    border: 0;
    padding: 0;
    color: var(--accent);
    cursor: pointer;
    font: inherit;
  }
  .error {
    color: var(--danger);
  }
</style>
