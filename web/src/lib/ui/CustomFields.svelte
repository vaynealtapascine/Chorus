<script lang="ts">
  import type { Projection } from "../sync/client";
  let { projection, creating = $bindable(false) }: { projection: Projection; creating?: boolean } = $props();
</script>

<p hidden data-fields={Object.keys(projection.rows.field_def ?? {}).length} data-creating={creating}></p>
