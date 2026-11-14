<script lang="ts">
  // The Handover composer's chips (Figma F5.15): Acknowledge (the newest unread), Checklist (items go with the
  // message) and At next switch (delivered when the front changes). The item editor is a list of rows above.
  import Chip from './kit/Chip.svelte';
  import Icon from './Icon.svelte';

  let { canAck, nextSwitch = $bindable(false), items = $bindable([]), editing = $bindable(false), onack }: {
    canAck: boolean; nextSwitch?: boolean; items?: string[]; editing?: boolean; onack: () => void;
  } = $props();
  const count = $derived(items.filter((t) => t.trim()).length);
  function open() {
    editing = !editing;
    if (editing && !items.length) items = [''];
  }
  const change = (i: number, v: string) => (items = items.map((t, n) => (n === i ? v : t)));
</script>

{#if editing}
  <div class="items" role="group" aria-label="Checklist">
    {#each items as item, i (i)}
      <div class="item">
        <input value={item} maxlength="200" placeholder="Item {i + 1}" aria-label="Item {i + 1}" oninput={(e) => change(i, e.currentTarget.value)}
          onkeydown={(e) => { if (e.key === 'Enter' && items.length < 30) { e.preventDefault(); items = [...items, '']; } }} />
        <button type="button" aria-label="Remove item {i + 1}" onclick={() => (items = items.filter((_, n) => n !== i))}><Icon name="close" size={16} /></button>
      </div>
    {/each}
    {#if items.length < 30}<button type="button" class="add" onclick={() => (items = [...items, ''])}><Icon name="plus" size={16} />Add item</button>{/if}
  </div>
{/if}
<div class="chips">
  <Chip disabled={!canAck} onclick={onack}><Icon name="check" size={16} />Acknowledge</Chip>
  <Chip on={editing || count > 0} onclick={open}><Icon name="select" size={16} />Checklist{count ? ` · ${count}` : ''}</Chip>
  <Chip on={nextSwitch} onclick={() => (nextSwitch = !nextSwitch)}><Icon name="swap" size={16} />At next switch</Chip>
</div>

<style>
  .chips { display: flex; gap: var(--s-2); overflow-x: auto; padding: var(--s-2) var(--s-3) 0; scrollbar-width: none; }
  .items { display: grid; gap: var(--s-1); padding: var(--s-2) var(--s-3) 0; max-height: 14rem; overflow-y: auto; }
  .item { display: flex; align-items: center; gap: var(--s-2); }
  .item input { flex: 1; min-width: 0; min-height: 36px; padding: 0 var(--s-3); border: 1px solid var(--line); border-radius: var(--r-md); background: var(--surface-2); color: var(--ink); font: inherit; font-size: var(--fs-md); }
  .item button, .add { display: inline-flex; align-items: center; gap: var(--s-1); border: 0; background: none; color: var(--ink-3); font: inherit; font-size: var(--fs-sm); cursor: pointer; min-height: 32px; }
  .add { justify-self: start; color: var(--accent); }
</style>
