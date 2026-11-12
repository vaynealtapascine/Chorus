<script lang="ts">
  // Who is in a group: tap to put someone in or take them out. One op per tap, so it works offline.
  import { fuzzy, groupPath, groups } from '../data';
  import { sync, type Projection } from '../sync/client';
  import { candidates, groupLine, groupMembers, membershipOp } from '../member-directory';
  import MemberPickRow from './MemberPickRow.svelte';
  import Sheet from './Sheet.svelte';
  import FormCard from './kit/FormCard.svelte';

  let { projection, groupId, dark, onclose, layer = 0 }: { projection: Projection; groupId: string; dark: boolean; onclose: () => void; layer?: number } = $props();
  const gs = $derived(groups(projection));
  const group = $derived(gs.find((g) => g.id === groupId));
  const all = $derived(group ? candidates(projection, sync.accountId, group) : []);
  let query = $state('');
  const shown = $derived(query.trim() ? all.filter((c) => fuzzy(query, `${c.member.name} ${c.member.display_name ?? ''}`) !== null) : all);
  const toggle = (id: string, inside: boolean) => sync.create(membershipOp(inside), sync.accountScope, groupId, { member_id: id });
</script>

{#if group}
  <Sheet title={groupPath(group, gs)} subtitle={groupLine(groupMembers(projection, groupId).length, group.visibility)} {onclose} {layer}>
    {#if all.length > 8}<input class="search" bind:value={query} placeholder="Search members" aria-label="Search members" />{/if}
    <FormCard>
      {#each shown as c (c.member.id)}
        <MemberPickRow member={c.member} {dark} on={c.inside} disabled={c.loops} sub={c.loops ? 'Inside them already' : null} onclick={() => toggle(c.member.id, c.inside)} />
      {/each}
    </FormCard>
    {#snippet footer()}<button class="go" onclick={onclose}>Done</button>{/snippet}
  </Sheet>
{/if}

<style>
  .search { font: inherit; color: var(--ink); background: var(--surface-2); border: 0; border-radius: 12px; padding: 10px 14px; }
  .go { flex: 1; min-height: 44px; border: 0; border-radius: 14px; background: var(--accent); color: var(--on-accent); font: inherit; font-weight: 600; cursor: pointer; }
</style>
