<script lang="ts">
  // Everything that is not Basic (DESIGN §6), reached from Account.
  import FormCard from '../kit/FormCard.svelte';
  import PageHeader from '../kit/PageHeader.svelte';
  import Switch from '../kit/Switch.svelte';
  import { contentWarningsAutoExpand, readPerMember, segmentParsing, selfMember } from '../../data';
  import { prefOf, savePref } from '../../settings-api';
  import { sync, type Projection } from '../../sync/client';

  let { projection }: { projection: Projection } = $props();
  const isSystem = $derived(!selfMember(projection));
  const pref = (key: string) => prefOf(projection.rows.pref, key);
</script>

<section class="page">
  <PageHeader title="Advanced" />
  <FormCard>
    <Switch label="Show when we're typing in shared chats" checked={pref('share_typing') === true} onchange={(on) => savePref('share_typing', on)} />
    {#if isSystem}<Switch label="Private chats only while their member is here" checked={pref('chat.hide_private') !== false} onchange={(on) => savePref('chat.hide_private', on)} />{/if}
    <Switch label="Expand content warnings automatically" checked={contentWarningsAutoExpand(projection, sync.accountId)} onchange={(on) => savePref('chat.cw_auto_expand', on)} />
    <Switch label="Parse speaker annotations in chat" checked={segmentParsing(projection, sync.accountId)} onchange={(on) => savePref('chat.segment_parsing', on)} />
    {#if isSystem}<Switch label="Track reading per member" sub="A dot shows who hasn't seen a message" checked={readPerMember(projection, sync.accountId)} onchange={(on) => savePref('chat.read_per_member', on)} />{/if}
  </FormCard>
</section>

<style>
  .page { display: grid; gap: 12px; max-width: 720px; align-content: start; }
</style>
