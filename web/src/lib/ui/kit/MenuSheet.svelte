<script lang="ts">
  // A list of actions in a sheet, in place of a dropdown menu (Android MenuDrawer, F5.6). Fill it with
  // MenuRows (and MenuDividers between groups).
  import type { Snippet } from 'svelte';
  import Sheet from '../Sheet.svelte';

  let { title, label, subtitle, onclose, children, layer = 0 }: {
    title?: string; label?: string; subtitle?: string | null; onclose: () => void; children: Snippet; layer?: number;
  } = $props();
</script>

<Sheet {title} label={label ?? title ?? 'Menu'} {subtitle} {onclose} {layer}>
  <div class="menu" role="menu" aria-label={title ?? label ?? 'Menu'}>{@render children()}</div>
</Sheet>

<style>
  .menu { display: grid; padding-top: var(--s-1); }
</style>
