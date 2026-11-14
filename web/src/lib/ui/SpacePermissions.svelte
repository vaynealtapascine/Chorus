<script lang="ts">
  // Permissions of a shared space (F5.11, D-133, D-160). Admins set what everyone in the space may do
  // (`space.set_permission`, the `everyone` role; per-person and per-role exceptions open the
  // override editor); every present member sets what its own system shares here
  // (`space.set_sharing`: member names, fronting status and its delay). Loaded on demand.
  import { untrack } from 'svelte';
  import type { SpaceRow } from '../data';
  import { roleIn } from '../chat-manage';
  import { canSetPermissions, canShare, delayOptions, EVERYONE_ROWS, everyonePayload, HISTORY_ROW, memberSwitches, sharingChanges, sharingOf } from '../space-perms';
  import type { SpaceInfo } from '../spaces';
  import { sync, type Projection } from '../sync/client';
  import ChannelPermissions from './ChannelPermissions.svelte';
  import Button from './kit/Button.svelte';
  import FormCard from './kit/FormCard.svelte';
  import SectionLabel from './kit/SectionLabel.svelte';
  import Segmented from './kit/Segmented.svelte';
  import Switch from './kit/Switch.svelte';
  import ValueRow from './kit/ValueRow.svelte';
  import Sheet from './Sheet.svelte';

  let { projection, space, info, onclose }: { projection: Projection; space: SpaceRow; info: SpaceInfo | undefined; onclose: () => void } = $props();

  const me = sync.accountId;
  const role = $derived(roleIn(projection.rows.space_member, space.id, info, me));
  const manager = $derived(canSetPermissions(space.kind, role));
  const sharing = $derived(canShare(space.kind, role));

  // the form edits a copy; Save writes only what changed
  const switches0 = untrack(() => memberSwitches(projection.rows, space.id));
  const share0 = untrack(() => sharingOf(projection.rows, space.id, me));
  let switches = $state({ ...switches0 });
  let share = $state({ ...share0 });
  let delay = $state(String(share0.fronts_delay_min));
  const delays = $derived(delayOptions(share0.fronts_delay_min));
  let exceptions = $state(false);

  const everyone = $derived(everyonePayload(projection.rows, space.id, switches0, switches));
  const own = $derived(sharingChanges(share0, share));
  const changed = $derived((manager && !!everyone) || (sharing && !!own));

  const scope = $derived(`space:${space.id}`);
  const overrides = $derived(Object.entries(projection.rows.space_permission ?? {})
    .filter(([key, row]) => key.startsWith(`${space.id}|`) && row.exists && !key.endsWith('|role|everyone')
      && ((row.fields.allow as unknown[] | undefined)?.length || (row.fields.deny as unknown[] | undefined)?.length)).length);

  function save() {
    if (manager && everyone) sync.create('space.set_permission', scope, space.id, everyone);
    if (sharing && own) sync.create('space.set_sharing', scope, space.id, own);
    onclose();
  }
</script>

<Sheet title={manager ? 'Permissions' : 'What we share'} subtitle={manager ? `${space.name} · you manage this space` : space.name}
  onclose={() => { if (!exceptions) onclose(); }}>
  {#if manager}
    <SectionLabel label="What friends’ systems can do" />
    <FormCard>
      {#each EVERYONE_ROWS.filter((r) => r.perm !== 'aside') as r (r.perm)}
        <Switch label={r.label} bind:checked={switches[r.perm]} />
      {/each}
    </FormCard>
  {/if}

  {#if sharing}
    <SectionLabel label="What they can see" />
    <FormCard>
      <Switch label="Member names on each message" bind:checked={share.member_names}
        sub={share.member_names ? null : 'Others see only our system, though anyone who sees our members elsewhere can still match them'} />
      <Switch label="Our fronting status" bind:checked={share.fronts}
        sub={share.fronts ? 'Everyone here sees who is fronting after the delay' : null} />
      {#if share.fronts}
        <div class="delay"><Segmented label="Delay" options={delays} bind:value={delay} onchange={(v) => (share.fronts_delay_min = Number(v))} /></div>
      {/if}
      {#if manager}
        <Switch label={HISTORY_ROW.label} bind:checked={switches[HISTORY_ROW.perm]} sub={switches[HISTORY_ROW.perm] ? 'Everything' : 'From when they join'} />
      {/if}
    </FormCard>
  {/if}

  {#if manager}
    <SectionLabel label="Inside {space.name}" />
    <FormCard>
      <Switch label="Allow system asides" bind:checked={switches.aside} />
      <ValueRow label="Exceptions" value={overrides ? String(overrides) : ''} onclick={() => (exceptions = true)} />
    </FormCard>
  {/if}

  {#snippet footer()}
    <Button grow onclick={onclose}>{changed ? 'Cancel' : 'Close'}</Button>
    <Button variant="primary" grow disabled={!changed} onclick={save}>{manager ? 'Save permissions' : 'Save'}</Button>
  {/snippet}
</Sheet>

{#if exceptions}
  <Sheet title="Exceptions" subtitle={space.name} layer={1} onclose={() => (exceptions = false)}>
    <ChannelPermissions {projection} spaceId={space.id} {info} open />
  </Sheet>
{/if}

<style>
  .delay { padding: 0 14px 12px; }
</style>
