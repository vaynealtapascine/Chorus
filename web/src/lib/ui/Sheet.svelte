<script lang="ts">
  // A bottom sheet (a centred dialog on wide screens), as the Friends screens use them (F7.7): a
  // title and a line under it, a close button, the body, and an optional footer of buttons.
  import type { Snippet } from 'svelte';
  import Icon from './Icon.svelte';

  let { title, label, subtitle, onclose, children, footer, actions, layer = 0 }: {
    /** no title: no header (a menu of actions), and `label` names the dialog */
    title?: string; label?: string; subtitle?: string | null; onclose: () => void; children: Snippet; footer?: Snippet;
    /** icon buttons beside Close (a search, say) */
    actions?: Snippet;
    /** a sheet opened from a sheet lies over it */
    layer?: number;
  } = $props();
</script>

<svelte:window onkeydown={(e) => e.key === 'Escape' && onclose()} />
<div class="scrim" onclick={onclose} role="presentation" style="z-index: {40 + layer * 2}"></div>
<div class="sheet" role="dialog" aria-modal="true" aria-label={title ?? label} style="z-index: {41 + layer * 2}; --lower: {layer * 28}px">
  <div class="handle" aria-hidden="true"></div>
  {#if title}
    <header>
      <div class="titles"><h2>{title}</h2>{#if subtitle}<p>{subtitle}</p>{/if}</div>
      {#if actions}{@render actions()}{/if}
      <button class="close" aria-label="Close" onclick={onclose}><Icon name="close" size={20} /></button>
    </header>
  {/if}
  <div class="body">{@render children()}</div>
  {#if footer}<footer>{@render footer()}</footer>{/if}
</div>

<style>
  .scrim { position: fixed; inset: 0; background: rgba(0, 0, 0, 0.55); }
  .sheet { position: fixed; inset: auto 0 0 0; margin: 0 auto; max-width: 560px; max-height: calc(92vh - var(--lower)); overflow: auto; background: var(--surface);
    border-radius: 20px 20px 0 0; box-shadow: var(--shadow-pop); padding-bottom: env(safe-area-inset-bottom); display: grid; align-content: start; }
  @media (min-width: 641px) { .sheet { inset: calc(6vh + var(--lower)) 0 auto 0; border-radius: 20px; } }
  .handle { width: 36px; height: 4px; border-radius: 2px; background: var(--line); margin: var(--s-2) auto var(--s-1); }
  header { display: flex; align-items: center; gap: var(--s-1); padding: var(--s-2) var(--s-2) var(--s-3) var(--s-5); }
  .titles { flex: 1; min-width: 0; padding-right: var(--s-2); }
  h2 { margin: 0; font-size: 19px; line-height: 24px; font-weight: 600; }
  .titles p { margin: 2px 0 0; color: var(--ink-3); font-size: var(--fs-sm); }
  .close { width: 44px; height: 44px; display: grid; place-items: center; border: 0; background: none; color: var(--ink-2); border-radius: 50%; cursor: pointer; }
  .body { display: grid; gap: 10px; padding: 0 var(--s-4) var(--s-4); }
  footer { display: flex; gap: 10px; padding: var(--s-1) var(--s-4) var(--s-4); position: sticky; bottom: 0; background: var(--surface); }
</style>
