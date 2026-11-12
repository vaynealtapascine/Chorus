<script lang="ts">
  import DragList from './DragList.svelte';
  let { items, onreorder }: { items: { id: string }[]; onreorder: (ids: string[]) => void } = $props();
</script>

<DragList {items} {onreorder}>
  {#snippet row(item)}<span>row {item.id}</span>{/snippet}
</DragList>
