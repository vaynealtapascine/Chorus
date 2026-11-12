<script lang="ts">
  // F8.6: the redaction switches every stage offers.
  import type { Def } from '../../stage-def';
  import FormCard from '../kit/FormCard.svelte';
  import Switch from '../kit/Switch.svelte';
  import Label from './Label.svelte';

  let { def = $bindable(), chatName = true }: { def: Def; chatName?: boolean } = $props();
</script>

<Label>Hide what identifies you</Label>
<FormCard>
  <Switch label="Replace names with Member A, B…" bind:checked={def.redact_names} />
  <Switch label="Plain grey pictures" bind:checked={def.render.blur_avatars} />
  <Switch label="Hide times" checked={def.time.mode === 'hide'} onchange={(on) => (def.time = on ? { mode: 'hide' } : { mode: 'real' })} />
  {#if chatName}<Switch label="Hide the chat name" bind:checked={def.render.hide_header} />{/if}
  <Switch label="Blur photos and files" bind:checked={def.render.blur_attachments} />
</FormCard>
