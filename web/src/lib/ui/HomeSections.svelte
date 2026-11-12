<script lang="ts">
  // Home sections (Figma F0.3): drag to reorder, switch one off to hide it; Reset goes back to
  // the default order, Done saves for whoever is reading (each member keeps their own, shared with Android).
  import { untrack } from 'svelte';
  import * as H from '../home-dashboard';
  import Sheet from './Sheet.svelte';
  import DragList from './kit/DragList.svelte';
  import Switch from './kit/Switch.svelte';

  let { sections, person, readerName, onsave, onclose }: {
    sections: H.HomeSection[]; person: boolean; readerName: string | null; onsave: (s: H.HomeSection[]) => void; onclose: () => void;
  } = $props();
  let draft = $state(untrack(() => H.editable(sections).map((s) => ({ ...s }))));
  const reorder = (ids: string[]) => (draft = ids.map((id) => draft.find((s) => s.id === id)!));
  function done() {
    // the unbuilt sections keep their place, so the pref still holds them
    onsave(H.parse(H.value(draft), person));
    onclose();
  }
</script>

<Sheet title="Home sections" subtitle={readerName ? `${readerName}’s Home` : null} {onclose}>
  <DragList items={draft} onreorder={reorder} label="Reorder sections">
    {#snippet row(s)}
      <span class="name">{H.SECTION_TEXT[s.id] ?? s.id}</span>
      <Switch checked={s.on} onchange={(on) => (s.on = on)} aria={H.SECTION_TEXT[s.id]} />
    {/snippet}
  </DragList>
  {#snippet footer()}
    <button class="neutral" onclick={() => (draft = H.editable(H.parse(null, person)).map((s) => ({ ...s })))}>Reset</button>
    <button class="primary" onclick={done}>Done</button>
  {/snippet}
</Sheet>

<style>
  .name { flex: 1; font-size: var(--fs-md); }
  .neutral, .primary { min-height: 44px; padding: 10px 18px; border: 0; border-radius: var(--r-control); font: inherit; font-size: var(--fs-md); font-weight: 600; cursor: pointer; }
  .neutral { background: var(--surface-2); color: var(--ink); }
  .primary { flex: 1; background: var(--accent); color: var(--on-accent); }
</style>
