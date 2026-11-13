<script lang="ts">
  // The poll as part of the draft (Android ComposerPoll): options (remove once there are more than two),
  // Add option, Pick several, and when it closes (D-136).
  import Icon from '../Icon.svelte';
  import Switch from '../kit/Switch.svelte';
  import type { Composer } from '../../journal-composer.svelte';

  let { c }: { c: Composer } = $props();
  const d = $derived(c.draft);
</script>

<div class="poll" role="group" aria-label="Poll">
  <div class="head"><Icon name="poll" size={18} /><strong>Poll</strong>
    <button type="button" class="x" aria-label="Remove poll" title="Remove poll" onclick={() => (d.pollOn = false)}><Icon name="close" size={16} /></button></div>
  {#each d.pollOptions as _, i (i)}
    <div class="option">
      <input bind:value={d.pollOptions[i]} placeholder="Option {i + 1}" aria-label="Poll option {i + 1}" maxlength="200" />
      {#if d.pollOptions.length > 2}<button type="button" class="x" aria-label="Remove option {i + 1}" onclick={() => (d.pollOptions = d.pollOptions.filter((_, n) => n !== i))}><Icon name="close" size={16} /></button>{/if}
    </div>
  {/each}
  <div class="foot">
    {#if d.pollOptions.length < 20}<button type="button" class="add" onclick={() => (d.pollOptions = [...d.pollOptions, ''])}><Icon name="plus" size={16} />Add option</button>{/if}
    <span class="several">Pick several <Switch aria="Pick several" bind:checked={d.pollMultiple} /></span>
  </div>
  <label class="closes"><span>Closes</span><input type="datetime-local" bind:value={d.pollCloses} aria-label="Poll closes" />
    {#if d.pollCloses}<button type="button" class="x" aria-label="No closing time" onclick={() => (d.pollCloses = '')}><Icon name="close" size={16} /></button>{/if}</label>
</div>

<style>
  .poll { display: grid; gap: var(--s-2); padding: var(--s-3); border: 1px solid var(--line); border-radius: var(--r-md); animation: chorus-rise-in var(--t-fast) var(--ease); }
  .head { display: flex; align-items: center; gap: var(--s-2); color: var(--accent); }
  .head strong { flex: 1; color: var(--ink); font-size: var(--fs-md); font-weight: 600; }
  .option { display: flex; align-items: center; gap: var(--s-1); }
  input { flex: 1; min-width: 0; min-height: 44px; box-sizing: border-box; border: 1px solid transparent; border-radius: var(--r-row); background: var(--surface-2); color: var(--ink);
    padding: 0 var(--s-3); font: inherit; font-size: var(--fs-md); }
  input:focus { outline: none; border-color: var(--accent); }
  .x { display: grid; place-items: center; width: 36px; height: 36px; flex: none; border: 0; border-radius: 50%; background: none; color: var(--ink-3); cursor: pointer; }
  .foot { display: flex; align-items: center; justify-content: space-between; gap: var(--s-2); flex-wrap: wrap; }
  .add { display: inline-flex; align-items: center; gap: 6px; min-height: 40px; padding: 0 var(--s-2); border: 0; border-radius: var(--r-sm); background: none; color: var(--accent);
    font: inherit; font-size: var(--fs-md); font-weight: 500; cursor: pointer; }
  .several { display: inline-flex; align-items: center; margin-left: auto; color: var(--ink-2); font-size: var(--fs-md); }
  .closes { display: flex; align-items: center; gap: var(--s-2); color: var(--ink-2); font-size: var(--fs-md); padding-left: var(--s-2); }
  .closes span { flex: 1; }
  .closes input { flex: 0 1 auto; min-height: 40px; background: none; color: var(--accent); font-weight: 500; }
</style>
