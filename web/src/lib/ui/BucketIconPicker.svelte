<script lang="ts">
  // A bucket's look (D-115): a built-in icon or its own emoji, and a colour (a swatch, any
  // colour, or none). Each choice saves at once.
  import { BUCKET_ICONS, BUCKET_SWATCHES, bucketColor, bucketIcon, isBuiltInIcon, isCustomIcon } from '../bucket-controls';
  import BucketGlyph from './BucketGlyph.svelte';
  import Icon from './Icon.svelte';
  let { icon, color, name, onicon, oncolor }: {
    icon: string | undefined; color: string | null | undefined; name: string;
    onicon: (icon: string) => void; oncolor: (color: string | null) => void;
  } = $props();
  const chosen = $derived(bucketIcon(icon));
  const tint = $derived(bucketColor(color));
  let emoji = $state('');
  $effect(() => { emoji = isBuiltInIcon(chosen) ? '' : chosen; });
  const emojiOk = $derived(isCustomIcon(emoji.trim()));
</script>

<div class="look">
  <div class="row" role="radiogroup" aria-label={`Icon for ${name}`}>
    {#each BUCKET_ICONS as option (option)}
      <button type="button" role="radio" aria-checked={chosen === option} aria-label={option} class:on={chosen === option}
        onclick={() => chosen !== option && onicon(option)}><Icon name={option} size={18} /></button>
    {/each}
    <label class="emoji" class:on={!isBuiltInIcon(chosen)}>
      <span class="sr-only">Your own emoji</span>
      <input bind:value={emoji} maxlength="16" placeholder="🙂" aria-label="Your own emoji for {name}"
        onchange={() => { const v = emoji.trim(); if (isCustomIcon(v) && v !== chosen) onicon(v); }} />
    </label>
    {#if emoji.trim() && !emojiOk}<span class="hint">One emoji or symbol</span>{/if}
  </div>
  <div class="row" role="radiogroup" aria-label={`Colour for ${name}`}>
    <button type="button" role="radio" aria-checked={!tint} aria-label="No colour" class="none" class:on={!tint}
      onclick={() => tint && oncolor(null)}><BucketGlyph icon={chosen} size={18} /></button>
    {#each BUCKET_SWATCHES as c (c)}
      <button type="button" role="radio" aria-checked={tint === c} aria-label="Colour {c}" class="swatch" class:on={tint === c}
        style:background={c} onclick={() => tint !== c && oncolor(c)}></button>
    {/each}
    <input type="color" aria-label="Another colour for {name}" value={tint ?? '#42A397'}
      onchange={(e) => oncolor(e.currentTarget.value.toUpperCase())} />
  </div>
</div>

<style>
  .look { display: grid; gap: var(--s-2); }
  .row { display: flex; flex-wrap: wrap; align-items: center; gap: var(--s-1); }
  button { width: 34px; height: 34px; display: grid; place-items: center; border: 2px solid transparent; border-radius: 50%;
    background: var(--surface-2); color: var(--ink-3); cursor: pointer; padding: 0; }
  button:hover { color: var(--ink); }
  button.on { background: var(--accent-soft); color: var(--accent); border-color: var(--accent); }
  .swatch.on { border-color: var(--ink); }
  .emoji { display: inline-grid; place-items: center; width: 44px; height: 34px; border-radius: 17px; border: 2px solid transparent; background: var(--surface-2); }
  .emoji.on { border-color: var(--accent); background: var(--accent-soft); }
  .emoji input { width: 36px; border: 0; background: none; text-align: center; font-size: var(--fs-lg); color: var(--ink); padding: 0; }
  input[type='color'] { width: 34px; height: 34px; padding: 0; border: 0; background: none; cursor: pointer; }
  .hint { color: var(--ink-3); font-size: var(--fs-sm); }
  .sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip-path: inset(50%); }
</style>
