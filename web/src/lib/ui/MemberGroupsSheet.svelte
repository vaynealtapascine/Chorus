<script lang="ts">
  // The groups one member is in (F4.2 "Groups"): tick to put them in or take them out.
  import Button from './kit/Button.svelte';
  import { groupPath, groups, membership, type MemberRow } from '../data';
  import { sync, type Projection } from '../sync/client';
  import { candidates, groupLine, groupMembers, membershipOp } from '../member-directory';
  import GroupTile from './GroupTile.svelte';
  import Icon from './Icon.svelte';
  import NewGroupSheet from './NewGroupSheet.svelte';
  import Sheet from './Sheet.svelte';
  import FormCard from './kit/FormCard.svelte';

  let { projection, member, onclose, layer = 0 }: { projection: Projection; member: MemberRow; onclose: () => void; layer?: number } = $props();
  const gs = $derived(groups(projection));
  const inGroup = $derived(membership(projection));
  let creating = $state(false);
  const toggle = (gid: string, inside: boolean) => sync.create(membershipOp(inside), sync.accountScope, gid, { member_id: member.id });
</script>

<Sheet title="Groups" subtitle={member.display_name ?? member.name} {onclose} {layer}>
  {#if gs.length}
    <FormCard>
      {#each gs as g (g.id)}
        {@const inside = inGroup.get(g.id)?.has(member.id) ?? false}
        {@const c = candidates(projection, sync.accountId, g).find((x) => x.member.id === member.id)}
        <button class="pick" role="checkbox" aria-checked={inside} disabled={!c || c.loops} onclick={() => toggle(g.id, inside)}>
          <GroupTile group={g} size={36} />
          <span class="text"><strong>{groupPath(g, gs)}</strong><span>{groupLine(groupMembers(projection, g.id).length, g.visibility)}</span></span>
          <span class="check" class:on={inside} aria-hidden="true">{#if inside}<Icon name="check" size={16} />{/if}</span>
        </button>
      {/each}
    </FormCard>
  {/if}
  <button class="dashed" onclick={() => (creating = true)}><Icon name="plus" size={18} />New group</button>
  {#snippet footer()}<Button variant="primary" grow class="go" onclick={onclose}>Done</Button>{/snippet}
</Sheet>

{#if creating}
  <NewGroupSheet {projection} layer={layer + 1} onclose={() => (creating = false)} oncreated={(id) => sync.create('group.add_member', sync.accountScope, id, { member_id: member.id })} />
{/if}

<style>
  .pick { display: flex; align-items: center; gap: var(--s-3); width: 100%; min-height: 56px; padding: var(--s-2) 14px; border: 0; background: none; color: var(--ink); font: inherit; text-align: left; cursor: pointer; }
  .pick:disabled { cursor: default; opacity: .6; }
  .text { flex: 1; min-width: 0; display: grid; }
  .text strong { font-size: var(--fs-md); font-weight: 400; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .text span { color: var(--ink-3); font-size: var(--fs-xs); }
  .check { width: 22px; height: 22px; display: grid; place-items: center; border: 2px solid var(--line); border-radius: 50%; color: var(--on-accent); flex: none; }
  .check.on { background: var(--accent); border-color: var(--accent); }
  .dashed { display: flex; align-items: center; justify-content: center; gap: var(--s-2); min-height: 52px; border: 1.5px dashed var(--line); border-radius: var(--r-md); background: none; color: var(--accent); font: inherit; font-weight: 600; cursor: pointer; }
</style>
