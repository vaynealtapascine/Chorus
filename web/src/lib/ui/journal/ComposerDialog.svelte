<script lang="ts">
  // The full composer (F6.2), hosted once by App.svelte and opened with `openComposer()`. A phone gets a
  // page the size of what the keyboard leaves visible, bars docked above it; wider screens a centred
  // dialog. Cancel or Esc with words in the main draft asks Save draft / Discard, as Twitter does.
  import { tick, untrack } from 'svelte';
  import Sheet from '../Sheet.svelte';
  import Button from '../kit/Button.svelte';
  import Composer from './Composer.svelte';
  import { closeComposer, journalCompose } from '../../journal-compose.svelte';
  import { composerFor, contextOf } from '../../journal-composer.svelte';
  import type { Projection } from '../../sync/client';

  let { projection, dark }: { projection: Projection; dark: boolean } = $props();
  const options = $derived(journalCompose.options);
  const context = $derived(contextOf(options.replyTo));
  const c = $derived(composerFor(context));
  let asking = $state(false);
  let drafts = $state(false);
  let dialog = $state<HTMLDivElement | undefined>();

  // what the opener asked for lands once per opening
  let applied = 0;
  $effect(() => {
    if (!journalCompose.open || journalCompose.seq === applied) return;
    applied = journalCompose.seq;
    untrack(() => {
      c.attach(projection, { replyVisibility: options.replyVisibility });
      c.apply(options);
    });
  });

  // the page is as tall as what the on-screen keyboard leaves (visualViewport), so the bars ride on it
  let vv = $state<{ top: number; height: number } | null>(null);
  let returnTo: HTMLElement | null = null;
  $effect(() => {
    if (!journalCompose.open) return;
    returnTo = untrack(() => document.activeElement as HTMLElement | null);
    const root = document.documentElement;
    const before = root.style.overflow;
    root.style.overflow = 'hidden';
    const view = window.visualViewport;
    const size = () => { if (view) vv = { top: view.offsetTop, height: view.height }; };
    size();
    view?.addEventListener('resize', size);
    view?.addEventListener('scroll', size);
    return () => {
      root.style.overflow = before;
      view?.removeEventListener('resize', size);
      view?.removeEventListener('scroll', size);
      const back = returnTo;
      void tick().then(() => back?.isConnected && back.focus?.({ preventScroll: true }));
    };
  });

  function close() { asking = false; drafts = false; closeComposer(); }
  function cancel() {
    // a reply, an untouched draft, or one handed back to the inline box it came from closes as it is
    if (options.inline || c.replyTo || !c.hasContent) close();
    else asking = true;
  }
  function keydown(e: KeyboardEvent) {
    if (!journalCompose.open) return;
    if (e.key === 'Escape') {
      // a sheet or menu over the dialog closes first
      if (document.querySelector('.sheet') || dialog?.querySelector('[role="menu"]')) return;
      e.preventDefault();
      cancel();
    } else if (e.key === 'Tab' && dialog && !document.querySelector('.sheet')) trap(e, dialog);
  }
  // Tab stays inside the dialog
  function trap(e: KeyboardEvent, box: HTMLElement) {
    const all = [...box.querySelectorAll<HTMLElement>('button:not([disabled]), input:not([disabled]):not([tabindex="-1"]), textarea:not([disabled]), select, a[href], [tabindex]:not([tabindex="-1"])')]
      .filter((el) => el.offsetParent !== null);
    if (!all.length) return;
    const first = all[0], last = all[all.length - 1];
    if (e.shiftKey && (document.activeElement === first || !box.contains(document.activeElement))) { e.preventDefault(); last.focus(); }
    else if (!e.shiftKey && (document.activeElement === last || !box.contains(document.activeElement))) { e.preventDefault(); first.focus(); }
  }
  const replyNames = $derived(options.replyNames);
  const authors = $derived(options.author ? [options.author] : undefined);
  let DraftsSheet = $state<typeof import('./DraftsSheet.svelte').default | null>(null);
  $effect(() => { if (drafts && !DraftsSheet) void import('./DraftsSheet.svelte').then((m) => (DraftsSheet = m.default)); });
</script>

<svelte:window onkeydown={keydown} />

{#if journalCompose.open}
  <div class="scrim compose-scrim" role="presentation" onclick={cancel}></div>
  <div bind:this={dialog} class="dialog" role="dialog" aria-modal="true" aria-label={c.replyTo ? 'Reply' : 'New post'}
    style={vv ? `--vv-top: ${vv.top}px; --vv-height: ${vv.height}px` : undefined}>
    {#key context}
      <Composer {c} {projection} {dark} surface="full" autofocus {authors} {replyNames} replyVisibility={options.replyVisibility}
        oncancel={cancel} ondrafts={() => (drafts = true)} onsent={close} />
    {/key}
  </div>
  {#if asking}
    <Sheet title="Save this draft?" onclose={() => (asking = false)}>
      <div class="ask">
        <Button variant="primary" onclick={() => { c.shelve(); close(); }}>Save draft</Button>
        <Button variant="danger-soft" onclick={() => { c.discard(); close(); }}>Discard</Button>
        <Button variant="quiet" onclick={() => (asking = false)}>Keep writing</Button>
      </div>
    </Sheet>
  {/if}
  {#if drafts && DraftsSheet}<DraftsSheet {c} onclose={() => (drafts = false)} />{/if}
{/if}

<style>
  .compose-scrim { position: fixed; inset: 0; z-index: 30; background: var(--scrim); }
  /* a phone: the whole visible screen, above the tab bar, sized to what the keyboard leaves */
  .dialog { position: fixed; left: 0; right: 0; top: var(--vv-top, 0px); height: var(--vv-height, 100dvh); z-index: 31; display: flex; flex-direction: column;
    background: var(--bg); animation: chorus-rise-in var(--t-base) var(--ease); }
  .dialog > :global(form) { flex: 1 1 auto; min-height: 0; }
  @media (min-width: 641px) {
    .dialog { top: 5vh; left: 0; right: 0; margin: 0 auto; width: min(600px, calc(100vw - 32px)); height: auto; min-height: 360px; max-height: 90vh;
      border-radius: 20px; box-shadow: var(--shadow-menu); animation: chorus-pop-in var(--t-base) var(--ease); }
    .dialog > :global(form) { border-radius: 20px; }
    .dialog :global(.dock) { border-radius: 0 0 20px 20px; }
  }
  .ask { display: grid; gap: 6px; width: 100%; }
</style>
