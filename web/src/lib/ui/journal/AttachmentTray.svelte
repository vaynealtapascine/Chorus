<script lang="ts">
  // Files waiting to go with the post (F6.2): square thumbnails with a round × on the corner, ALT for
  // the description and an eye that hides it as a spoiler; other files as a tile with their name.
  import Icon from '../Icon.svelte';
  import Sheet from '../Sheet.svelte';
  import Button from '../kit/Button.svelte';
  import type { Composer } from '../../journal-composer.svelte';

  let { c }: { c: Composer } = $props();
  let editing = $state<number | null>(null);
  const item = $derived(editing === null ? undefined : c.pending[editing]);
</script>

<div class="tray" role="list" aria-label="Attachments ready to post">
  {#each c.pending as file, i (file.url || file.file.name + i)}
    <div class="tile" role="listitem">
      {#if file.url}<img src={file.url} alt={file.alt} class:spoiler={file.spoiler} />
      {:else}<span class="doc"><Icon name="doc" size={22} /><span class="fname">{file.file.name}</span></span>{/if}
      <button type="button" class="remove" aria-label="Remove {file.file.name}" title="Remove" onclick={() => c.removeFile(i)}><Icon name="close" size={14} /></button>
      <div class="tags">
        <button type="button" class="alt" class:on={!!file.alt.trim()} aria-label="Alt text for {file.file.name}" onclick={() => (editing = i)}>ALT</button>
        <button type="button" class="eye" class:on={file.spoiler} aria-pressed={file.spoiler} aria-label="Spoiler" title={file.spoiler ? 'Hidden as a spoiler' : 'Hide as a spoiler'}
          onclick={() => (file.spoiler = !file.spoiler)}><Icon name={file.spoiler ? 'eye-off' : 'eye'} size={14} /></button>
      </div>
    </div>
  {/each}
</div>

{#if item}
  <Sheet title={item.file.name} onclose={() => (editing = null)}>
    {#if item.url}<img class="preview" src={item.url} alt="" />{/if}
    <textarea class="alt-field" bind:value={item.alt} maxlength="1500" rows="3" placeholder="Describe it for people who can’t see it" aria-label="Alt text"></textarea>
    {#snippet footer()}<Button variant="primary" grow onclick={() => (editing = null)}>Done</Button>{/snippet}
  </Sheet>
{/if}

<style>
  .tray { display: flex; gap: var(--s-2); overflow-x: auto; padding: var(--s-1) 0; scrollbar-width: thin; }
  .tile { position: relative; flex: none; width: 104px; height: 104px; border-radius: var(--r-row); overflow: hidden; background: var(--surface-2); animation: chorus-pop-in var(--t-fast) var(--ease); }
  .tile img { width: 100%; height: 100%; object-fit: cover; display: block; transition: filter var(--t-fast) var(--ease); }
  .tile img.spoiler { filter: blur(10px) brightness(.8); }
  .doc { display: grid; place-items: center; align-content: center; gap: var(--s-1); height: 100%; padding: var(--s-2); box-sizing: border-box; color: var(--ink-2); }
  .fname { max-width: 100%; font-size: var(--fs-xs); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .remove { position: absolute; top: 6px; right: 6px; display: grid; place-items: center; width: 28px; height: 28px; border: 0; border-radius: 50%;
    background: color-mix(in srgb, var(--bg) 82%, transparent); color: var(--ink); cursor: pointer; }
  /* a bigger target than it looks */
  .remove::before { content: ''; position: absolute; inset: -8px; }
  .tags { position: absolute; left: 6px; right: 6px; bottom: 6px; display: flex; justify-content: space-between; }
  .alt, .eye { display: inline-grid; place-items: center; height: 24px; min-width: 24px; padding: 0 6px; border: 0; border-radius: var(--r-full);
    background: color-mix(in srgb, var(--bg) 82%, transparent); color: var(--ink); font: inherit; font-size: 11px; font-weight: 700; letter-spacing: .02em; cursor: pointer; }
  .alt.on, .eye.on { background: var(--accent); color: var(--on-accent); }
  .preview { max-height: 220px; max-width: 100%; justify-self: center; border-radius: var(--r-row); object-fit: contain; }
  .alt-field { border: 0; border-radius: var(--r-row); background: var(--surface-2); color: var(--ink); padding: var(--s-3); font: inherit; line-height: 22px; resize: none; }
</style>
