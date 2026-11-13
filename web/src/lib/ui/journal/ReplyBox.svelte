<script lang="ts">
  // A thread's reply box (F6.4): a cell under the post that opens in place on focus. On a phone it is a
  // bar above the tab bar that opens the full composer on the same reply draft.
  import { untrack } from 'svelte';
  import { MediaQuery } from 'svelte/reactivity';
  import Face from '../Face.svelte';
  import Composer from './Composer.svelte';
  import { whoOf } from './who';
  import { composerFor, contextOf } from '../../journal-composer.svelte';
  import { openComposer } from '../../journal-compose.svelte';
  import type { Projection } from '../../sync/client';

  let { projection, replyTo, replyVisibility, replyNames, authors, dark, onsent }: {
    projection: Projection; replyTo: string; replyVisibility: 'private' | 'followers' | 'server'; replyNames: string;
    authors?: string[]; dark: boolean; onsent?: () => void;
  } = $props();
  const phone = new MediaQuery('max-width: 640px', false);
  const c = $derived(composerFor(contextOf(replyTo)));
  $effect.pre(() => {
    const p = projection, a = authors, v = replyVisibility;
    untrack(() => c.attach(p, { authors: a, replyVisibility: v }));
  });
  const open = (inline = false) => openComposer({ replyTo, replyNames, replyVisibility, inline });
  const said = $derived(c.draft.body.trim().split('\n')[0]);
</script>

{#if phone.current}
  <div class="bar">
    <Face who={whoOf(c.authorMembers[0])} size={32} {dark} />
    <button type="button" class="ask" class:said={!!said} onclick={() => open()}>{said || 'Post your reply'}</button>
  </div>
{:else}
  <Composer {c} {projection} {dark} surface="reply" {authors} {replyVisibility} {onsent} onexpand={() => open(true)} />
{/if}

<style>
  /* above the phone's tab bar (App.svelte: 6 + 56 + 6 px and the safe area) */
  .bar { position: fixed; left: 0; right: 0; bottom: calc(69px + env(safe-area-inset-bottom)); z-index: 9; display: flex; align-items: center; gap: var(--s-2);
    padding: var(--s-2) var(--s-3); background: var(--bg); border-top: 1px solid var(--line); animation: chorus-rise-in var(--t-base) var(--ease); }
  .ask { flex: 1; min-width: 0; min-height: 44px; padding: 0 var(--s-4); border: 0; border-radius: var(--r-full); background: var(--surface-2); color: var(--ink-3);
    font: inherit; font-size: var(--fs-md); text-align: left; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; cursor: text; }
  .ask:focus-visible { outline-offset: -2px; }
  .ask.said { color: var(--ink); }
</style>
