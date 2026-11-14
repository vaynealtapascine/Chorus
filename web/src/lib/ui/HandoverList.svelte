<script lang="ts">
  // A handover's checklist on its bubble (F5.15): tappable boxes, each tick written as the speaker (checklist.set);
  // "waiting for next switch" until the front change that delivers it.
  import Icon from './Icon.svelte';
  import type { ViewItem } from '../handover';

  let { items, waiting = false, canTick, ontick }: { items: ViewItem[]; waiting?: boolean; canTick: boolean; ontick: (id: string, done: boolean) => void } = $props();
</script>

{#if items.length}
  <ul class="checks" aria-label="Checklist">
    {#each items as item (item.id)}
      <li><button type="button" role="checkbox" aria-checked={item.done} disabled={!canTick} onclick={() => ontick(item.id, !item.done)}>
        <span class="box" class:on={item.done}>{#if item.done}<Icon name="check" size={14} />{/if}</span><span class:done={item.done}>{item.text}</span></button></li>
    {/each}
  </ul>
{/if}
{#if waiting}<p class="wait"><Icon name="clock" size={14} />Waiting for next switch</p>{/if}

<style>
  .checks { list-style: none; margin: var(--s-1) 0 0; padding: 0; display: grid; }
  .checks button { display: flex; align-items: center; gap: var(--s-2); width: 100%; min-height: 32px; padding: 0; border: 0; background: none; color: inherit; font: inherit; text-align: left; cursor: pointer; }
  .checks button:disabled { cursor: default; }
  .box { flex: none; width: 20px; height: 20px; display: grid; place-items: center; border: 1.5px solid var(--ink-3); border-radius: var(--r-sm); }
  .box.on { background: var(--accent); border-color: var(--accent); color: var(--on-accent); }
  .done { text-decoration: line-through; color: var(--ink-3); }
  .wait { display: flex; align-items: center; gap: var(--s-1); margin: var(--s-1) 0 0; color: var(--ink-3); font-size: var(--fs-xs); }
</style>
