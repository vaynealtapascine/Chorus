<script lang="ts" generics="T extends { id: string }">
  // A list whose rows are dragged by their grip (Android's reorder lists: Home sections, Members,
  // fields). The grip also answers the arrow keys. Rows follow the pointer; the new order is reported
  // once, when it is let go (or a key moves a row).
  import type { Snippet } from 'svelte';
  import { dropIndex, moveId } from '../../drag-reorder';
  import Icon from '../Icon.svelte';

  let { items, row, onreorder, label = 'Reorder' }: {
    items: T[];
    /** one row's content; the grip is drawn at its left */
    row: Snippet<[T, number]>;
    onreorder: (ids: string[]) => void;
    label?: string;
  } = $props();

  let order = $state<string[] | null>(null);
  let held = $state<string | null>(null);
  let dy = $state(0);
  let list: HTMLElement | undefined = $state();
  const ids = $derived(order ?? items.map((i) => i.id));
  const shown = $derived(ids.map((id) => items.find((i) => i.id === id)).filter((i): i is T => !!i));

  function rows(): HTMLElement[] { return [...(list?.querySelectorAll<HTMLElement>(':scope > [data-row]') ?? [])]; }
  function down(e: PointerEvent, id: string) {
    if (e.button > 0) return;
    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
    held = id;
    order = ids;
    dy = 0;
    e.preventDefault();
  }
  function move(e: PointerEvent) {
    if (!held || !list) return;
    const y = e.clientY - list.getBoundingClientRect().top;
    const all = rows();
    const me = all.find((r) => r.dataset.row === held);
    if (!me) return;
    const others = all.filter((r) => r !== me).map((r): [number, number] => [r.offsetTop, r.offsetHeight]);
    const to = dropIndex(others, y);
    const from = ids.indexOf(held);
    if (to !== from) order = moveId(ids, from, to);
    dy = y - (me.offsetTop + me.offsetHeight / 2);
  }
  function up() {
    if (!held) return;
    const changed = order && order.join() !== items.map((i) => i.id).join();
    const result = order;
    held = null;
    dy = 0;
    if (changed && result) onreorder(result);
    order = null;
  }
  function key(e: KeyboardEvent, id: string) {
    const by = e.key === 'ArrowUp' ? -1 : e.key === 'ArrowDown' ? 1 : 0;
    if (!by) return;
    e.preventDefault();
    const from = items.findIndex((i) => i.id === id);
    const next = moveId(items.map((i) => i.id), from, from + by);
    if (next.join() !== items.map((i) => i.id).join()) onreorder(next);
  }
</script>

<div class="drag-list" bind:this={list} role="list" aria-label={label}>
  {#each shown as item, i (item.id)}
    <div class="item" class:held={held === item.id} data-row={item.id} role="listitem" style={held === item.id ? `transform: translateY(${dy}px)` : undefined}>
      <button class="grip" aria-label="{label}: move up or down with the arrow keys" onpointerdown={(e) => down(e, item.id)} onpointermove={move} onpointerup={up} onpointercancel={up}
        onkeydown={(e) => key(e, item.id)}><Icon name="grip" size={20} /></button>
      {@render row(item, i)}
    </div>
  {/each}
</div>

<style>
  .drag-list { position: relative; display: grid; }
  .item { position: relative; display: flex; align-items: center; }
  .item + .item { border-top: 1px solid var(--line); }
  .item.held { z-index: 2; background: var(--surface-3, var(--surface)); box-shadow: var(--shadow-pop); border-radius: 12px; }
  .grip { flex: none; display: grid; place-items: center; width: 40px; align-self: stretch; min-height: 44px; border: 0; background: none; color: var(--ink-3);
    cursor: grab; touch-action: none; }
  .grip:active { cursor: grabbing; }
  .grip:focus-visible { color: var(--accent); outline: 2px solid var(--accent); border-radius: 8px; }
</style>
