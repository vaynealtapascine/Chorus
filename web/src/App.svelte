<script lang="ts">
  // M0.2 skeleton: a static Home with sample members, rendered through the real core
  // (member colour adaptation, front fold). Replaced by the real store in M3.
  import { core } from './lib/core';
  import FrontCard from './lib/ui/FrontCard.svelte';
  import type { Member } from './lib/ui/types';

  const dark = window.matchMedia('(prefers-color-scheme: dark)');
  let isDark = $state(dark.matches);
  dark.addEventListener('change', (e) => (isDark = e.matches));

  const sample: Member[] = [
    { id: 'kai', name: 'Kai', pronouns: 'they/them', color: '#C0694E', sigil: '🌌' },
    { id: 'june', name: 'June', pronouns: 'she/her', color: '#5E8C61', sigil: '🔖' },
    { id: 'rin', name: 'Rin', pronouns: 'he/him', color: '#fff27a', sigil: '❤️‍🔥' },
    { id: 'moss', name: 'Moss', pronouns: 'it/its', color: '#6C7BD6', sigil: '🌿' },
  ];
  let front = $state([
    { id: 'kai', level: 'front', primary: true },
    { id: 'june', level: 'cocon', primary: false },
  ]);
  const since = Date.now() - 2 * 3_600_000 - 13 * 60_000;

  function switchTo(id: string) {
    front = [{ id, level: 'front', primary: true }];
  }
</script>

<main>
  <header>
    <h1 class="display">Chorus</h1>
    <span class="version">core {core.version()}</span>
  </header>

  <FrontCard members={sample} {front} {since} dark={isDark} />

  <section aria-label="Quick switch">
    <h2>Quick switch</h2>
    <div class="row">
      {#each sample as m (m.id)}
        {@const c = core.adaptColor(m.color, isDark)}
        <button class="chip" style="--ring: {c.ring}; --tint: {c.tint}" onclick={() => switchTo(m.id)}>
          <span class="avatar" aria-hidden="true">{m.sigil}</span>
          <span style="color: {c.name}">{m.name}</span>
        </button>
      {/each}
    </div>
  </section>
</main>

<style>
  main {
    max-width: 640px;
    margin: 0 auto;
    padding: var(--s-6) var(--s-4) var(--s-12);
    display: grid;
    gap: var(--s-6);
  }
  header {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
  }
  h1 {
    font-size: var(--fs-2xl);
  }
  h2 {
    font-size: var(--fs-sm);
    font-weight: 600;
    color: var(--ink-2);
    margin-bottom: var(--s-3);
  }
  .version {
    color: var(--ink-3);
    font-size: var(--fs-xs);
  }
  .row {
    display: flex;
    flex-wrap: wrap;
    gap: var(--s-2);
  }
  .chip {
    display: inline-flex;
    align-items: center;
    gap: var(--s-2);
    padding: var(--s-1) var(--s-3) var(--s-1) var(--s-1);
    background: var(--surface);
    border: 1px solid var(--line);
    border-radius: var(--r-full);
    cursor: pointer;
    transition: background var(--t-fast) var(--ease);
  }
  .chip:hover {
    background: var(--tint);
  }
  .avatar {
    width: 32px;
    height: 32px;
    display: grid;
    place-items: center;
    border-radius: 50%;
    background: var(--surface-2);
    box-shadow: 0 0 0 2px var(--ring);
  }
</style>
