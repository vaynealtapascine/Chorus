<script lang="ts">
  // A bucket as it reads wherever it can be chosen (D-115): its icon in its colour, then its name.
  import type { BucketPresentation } from '../bucket-controls';
  import BucketGlyph from './BucketGlyph.svelte';
  let { bucket }: { bucket: BucketPresentation } = $props();
</script>
<span class="bucket-label"><BucketGlyph icon={bucket.icon} color={bucket.color} /><span>{bucket.name}</span></span>
<style>.bucket-label { display: inline-flex; align-items: center; gap: 6px; min-width: 0; overflow-wrap: anywhere; }</style>
