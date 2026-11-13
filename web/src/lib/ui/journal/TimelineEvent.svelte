<script lang="ts">
  // A switch or pin in the timeline, as Android's quiet marker: an icon in the face column, a line, a hairline on.
  import Icon from '../Icon.svelte';
  import type { Marker } from '../../journal-marker';
  let { marker }: { marker: Marker } = $props();
</script>

<div class="event"><span class="icon"><Icon name={marker.icon} size={18} /></span><span class="text">{marker.text}</span><span class="ago">· {marker.ago}</span><span class="rule"></span></div>

<style>
  .event { display: flex; align-items: center; gap: var(--s-3); min-height: 40px; padding: 0 var(--s-4); color: var(--ink-3); font-size: var(--fs-sm); }
  .icon { width: 40px; flex: none; display: grid; place-items: center; }
  .text { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .ago { flex: none; margin-left: calc(-1 * var(--s-2)); white-space: nowrap; }
  .rule { flex: 1 0 var(--s-4); height: 1px; background: var(--line); }
</style>
