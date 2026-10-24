<script lang="ts">
  let { name, size = 22 }: { name: string; size?: number } = $props();
</script>
<span class="icon" aria-hidden="true" style="--icon: url('/icons/{name}.svg'); --icon-size: {size}px"></span>
<style>
  .icon { display: inline-block; width: var(--icon-size); height: var(--icon-size); flex: none; background: currentColor; mask: var(--icon) center / contain no-repeat; }
</style>
