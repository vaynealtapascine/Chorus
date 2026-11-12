<script lang="ts">
  // A quiet line of context above a form (Android FormNote): teal for how it works, red for what
  // leaves this space (F3.5, F5.20).
  import Icon from '../Icon.svelte';

  let { text, icon, title, warning = false }: { text: string; icon?: string; title?: string; warning?: boolean } = $props();
</script>

<div class="form-note" class:warning role={warning ? 'alert' : undefined}>
  {#if icon}<Icon name={icon} size={18} />{/if}
  <div>{#if title}<strong>{title}</strong>{/if}<p>{text}</p></div>
</div>

<style>
  .form-note { display: flex; gap: 10px; padding: 10px 12px; border-radius: 12px; background: var(--accent-soft); color: var(--ink); }
  .form-note > :global(.icon) { color: var(--accent); margin-top: 1px; }
  .form-note.warning { background: color-mix(in oklab, var(--danger) 14%, transparent); }
  .form-note.warning > :global(.icon), .form-note.warning strong { color: var(--danger); }
  strong { display: block; color: var(--accent); font-size: 14px; line-height: 19px; font-weight: 600; }
  p { margin: 0; font-size: 13px; line-height: 18px; }
</style>
