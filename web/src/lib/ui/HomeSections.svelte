<script lang="ts">
  // Home sections (Figma F0.3): reorder with the arrows, switch one off to hide it; Reset goes back to
  // the default order, Done saves for whoever is reading (each member keeps their own, shared with Android).
  import { untrack } from 'svelte';
  import * as H from '../home-dashboard';
  import Sheet from './Sheet.svelte';
  import Icon from './Icon.svelte';
  import Switch from './kit/Switch.svelte';

  let { sections, person, readerName, onsave, onclose }: {
    sections: H.HomeSection[]; person: boolean; readerName: string | null; onsave: (s: H.HomeSection[]) => void; onclose: () => void;
  } = $props();
  let draft = $state(untrack(() => H.editable(sections).map((s) => ({ ...s }))));
  function move(i: number, by: number) {
    const j = i + by;
    if (j < 0 || j >= draft.length) return;
    const next = [...draft];
    [next[i], next[j]] = [next[j], next[i]];
    draft = next;
  }
  function done() {
    // the unbuilt sections keep their place, so the pref still holds them
    onsave(H.parse(H.value(draft), person));
    onclose();
  }
</script>

<Sheet title="Home sections" subtitle={readerName ? `${readerName}’s Home` : null} {onclose}>
  <ul>
    {#each draft as s, i (s.id)}
      <li>
        <span class="moves">
          <button aria-label="Move {H.SECTION_TEXT[s.id]} up" disabled={i === 0} onclick={() => move(i, -1)}><span class="up"><Icon name="chevron" size={16} /></span></button>
          <button aria-label="Move {H.SECTION_TEXT[s.id]} down" disabled={i === draft.length - 1} onclick={() => move(i, 1)}><Icon name="chevron" size={16} /></button>
        </span>
        <span class="name">{H.SECTION_TEXT[s.id] ?? s.id}</span>
        <Switch bind:checked={s.on} aria={H.SECTION_TEXT[s.id]} />
      </li>
    {/each}
  </ul>
  {#snippet footer()}
    <button class="neutral" onclick={() => (draft = H.editable(H.parse(null, person)).map((s) => ({ ...s })))}>Reset</button>
    <button class="primary" onclick={done}>Done</button>
  {/snippet}
</Sheet>

<style>
  ul { list-style: none; margin: 0; padding: 0; display: grid; }
  li { display: flex; align-items: center; gap: 8px; min-height: 56px; border-top: 1px solid var(--line); }
  li:first-child { border-top: 0; }
  .moves { display: grid; }
  .moves button { display: grid; place-items: center; width: 36px; height: 24px; border: 0; background: none; color: var(--ink-2); cursor: pointer; }
  .moves button:disabled { opacity: 0.3; cursor: default; }
  .up { display: inline-grid; transform: rotate(180deg); }
  .name { flex: 1; font-size: 15px; }
  .neutral, .primary { min-height: 44px; padding: 10px 18px; border: 0; border-radius: 14px; font: inherit; font-size: 15px; font-weight: 600; cursor: pointer; }
  .neutral { background: var(--surface-2); color: var(--ink); }
  .primary { flex: 1; background: var(--accent); color: var(--on-accent); }
</style>
