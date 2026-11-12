<script lang="ts">
  // F8.6: the redaction switches every stage offers.
  import type { Def } from '../../stage-def';
  import FormCard from '../kit/FormCard.svelte';
  import Switch from '../kit/Switch.svelte';
  import FormNote from '../kit/FormNote.svelte';
  import Chip from '../kit/Chip.svelte';
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
{#if Object.keys(def.blackout ?? {}).length}
  <div class="chips"><Chip label="Clear black-outs" onclick={() => (def.blackout = {})} /></div>
{:else}
  <FormNote text="To black out words, press and drag over them in the preview." icon="select" />
{/if}
