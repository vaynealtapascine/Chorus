<script lang="ts">
  // What an empty list shows (UI21; Android EmptyState): an icon in a soft disc, a two-to-four word
  // label and at most one action. No explanation: the screen around it says what belongs here.
  import Icon from '../Icon.svelte';

  let { icon, label, action, onaction, href }: {
    icon: string; label: string; action?: string; onaction?: () => void; href?: string;
  } = $props();
</script>

<div class="empty-state">
  <span class="disc" aria-hidden="true"><Icon name={icon} size={26} /></span>
  <p>{label}</p>
  {#if action && href}<a class="act" {href}>{action}</a>
  {:else if action && onaction}<button class="act" onclick={onaction}>{action}</button>{/if}
</div>

<style>
  .empty-state { display: grid; justify-items: center; gap: 12px; padding: 32px 24px; text-align: center; }
  .disc { width: 56px; height: 56px; display: grid; place-items: center; border-radius: 50%; background: var(--accent-soft); color: var(--accent); }
  p { margin: 0; color: var(--ink-2); font-size: 16px; font-weight: 600; }
  .act { min-height: 44px; min-width: 120px; max-width: 220px; display: grid; place-items: center; padding: 10px 16px; border: 0; border-radius: 14px;
    background: var(--surface-2); color: var(--ink); font: inherit; font-size: 15px; font-weight: 600; cursor: pointer; text-decoration: none; }
</style>
