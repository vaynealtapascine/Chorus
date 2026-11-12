<script lang="ts">
  // "Make X a subsystem" and "Inside X" (D-092): a member linked to a subsystem group, whose members
  // are inside them. The link is the account pref `member_subsystem:<member>`; the parts are ordinary
  // group membership. Nobody can be put inside themselves, or inside someone they hold.
  import { fuzzy, groupPath, groups, membership, type MemberRow } from '../data';
  import { sync, type Projection } from '../sync/client';
  import { candidates, groupLine, groupMembers, membershipOp, subsystemOf } from '../member-directory';
  import { memberSubsystems } from '../subsystems';
  import GroupTile from './GroupTile.svelte';
  import Icon from './Icon.svelte';
  import MemberPickRow from './MemberPickRow.svelte';
  import Sheet from './Sheet.svelte';
  import FormCard from './kit/FormCard.svelte';
  import SectionLabel from './kit/SectionLabel.svelte';

  let { projection, member, dark, onclose, layer = 0 }: { projection: Projection; member: MemberRow; dark: boolean; onclose: () => void; layer?: number } = $props();
  const account = $derived(sync.accountId);
  const name = $derived(member.display_name ?? member.name);
  const group = $derived(subsystemOf(projection, account, member.id));
  const gs = $derived(groups(projection));
  const link = (groupId: string | null) => sync.create('pref.set', sync.accountScope, null, { device: '', key: `member_subsystem:${member.id}`, value: groupId });

  const existing = $derived.by(() => {
    const linked = new Set([...memberSubsystems(projection, account)].filter(([id]) => id !== member.id).map(([, g]) => g));
    return gs.filter((g) => g.kind === 'subsystem' && !linked.has(g.id));
  });
  function make() {
    const id = sync.newId();
    const inside = gs.filter((g) => g.kind === 'subsystem' && membership(projection).get(g.id)?.has(member.id));
    sync.create('group.create', sync.accountScope, id, { name, kind: 'subsystem', color: member.color, can_front: false, ...(inside.length === 1 ? { parent_id: inside[0].id } : {}) });
    link(id);
  }

  let query = $state('');
  const all = $derived(group ? candidates(projection, account, group) : []);
  const shown = $derived(query.trim() ? all.filter((c) => fuzzy(query, `${c.member.name} ${c.member.display_name ?? ''}`) !== null) : all);
</script>

<Sheet title={group ? `Inside ${name}` : `Make ${name} a subsystem`} subtitle={group ? groupLine(groupMembers(projection, group.id).length, undefined) : null} {onclose} {layer}>
  {#if !group}
    {#if existing.length}
      <SectionLabel label="Existing" />
      <FormCard>
        {#each existing as g (g.id)}
          <button class="existing" onclick={() => link(g.id)}>
            <GroupTile group={g} size={36} />
            <span><strong>{groupPath(g, gs)}</strong><span>{groupLine(groupMembers(projection, g.id).filter((m) => m.id !== member.id).length, undefined)}</span></span>
          </button>
        {/each}
      </FormCard>
    {/if}
  {:else}
    {#if all.length > 8 || query}<input class="search" bind:value={query} placeholder="Search members" aria-label="Search members" />{/if}
    <FormCard>
      {#each shown as c (c.member.id)}
        <MemberPickRow member={c.member} {dark} on={c.inside} disabled={c.loops} sub={c.loops ? `${name} is inside them` : null}
          onclick={() => sync.create(membershipOp(c.inside), sync.accountScope, group.id, { member_id: c.member.id })} />
      {/each}
    </FormCard>
    <button class="stop" onclick={() => link(null)}><Icon name="close" size={20} />Stop being a subsystem</button>
  {/if}
  {#snippet footer()}
    {#if group}<button class="go" onclick={onclose}>Done</button>{:else}<button class="go" onclick={make}>New subsystem</button>{/if}
  {/snippet}
</Sheet>

<style>
  .existing { display: flex; align-items: center; gap: 12px; min-height: 56px; padding: 8px 14px; border: 0; background: none; color: var(--ink); font: inherit; text-align: left; cursor: pointer; }
  .existing span { display: grid; }
  .existing span span { color: var(--ink-3); font-size: 12px; }
  .search { font: inherit; color: var(--ink); background: var(--surface-2); border: 0; border-radius: 12px; padding: 10px 14px; }
  .stop { display: flex; align-items: center; gap: 12px; min-height: 48px; border: 0; background: none; color: var(--danger); font: inherit; cursor: pointer; padding: 0 4px; }
  .go { flex: 1; min-height: 44px; border: 0; border-radius: 14px; background: var(--accent); color: var(--on-accent); font: inherit; font-weight: 600; cursor: pointer; }
</style>
