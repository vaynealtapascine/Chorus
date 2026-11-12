<script lang="ts">
  // A group's tile (F4.4): people for a plain group, layers for a subsystem, in the group's colour.
  import type { GroupRow } from '../data';
  import Icon from './Icon.svelte';

  let { group, size = 44 }: { group: Pick<GroupRow, 'kind' | 'color'>; size?: number } = $props();
  const color = $derived(group.color && /^#[0-9a-f]{6}$/i.test(group.color) ? group.color : '#42A397');
</script>

<span class="tile" style="--size: {size}px; --c: {color}" aria-hidden="true"><Icon name={group.kind === 'subsystem' ? 'layers' : 'users'} size={Math.round(size * 0.5)} /></span>

<style>
  .tile { width: var(--size); height: var(--size); display: grid; place-items: center; flex: none; border-radius: calc(var(--size) / 4);
    background: color-mix(in srgb, var(--c) 22%, var(--surface)); color: var(--c); }
</style>
