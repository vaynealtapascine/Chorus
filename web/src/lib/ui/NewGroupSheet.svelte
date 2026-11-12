<script lang="ts">
  // A name, a colour, group or subsystem, and where a subsystem sits (F4.4 New group).
  import Button from './kit/Button.svelte';
  import { groupPath, groups } from '../data';
  import { sync, type Projection } from '../sync/client';
  import Sheet from './Sheet.svelte';
  import MenuRow from './kit/MenuRow.svelte';
  import MenuSheet from './kit/MenuSheet.svelte';
  import Segmented from './kit/Segmented.svelte';
  import Swatches from './kit/Swatches.svelte';
  import FormCard from './kit/FormCard.svelte';
  import ValueRow from './kit/ValueRow.svelte';

  let { projection, onclose, oncreated, layer = 0 }: { projection: Projection; onclose: () => void; oncreated?: (id: string) => void; layer?: number } = $props();
  let name = $state('');
  let kind = $state<'group' | 'subsystem'>('group');
  let color = $state('#42A397');
  let parent = $state<string | null>(null);
  let choosing = $state(false);
  const gs = $derived(groups(projection));
  const subsystems = $derived(gs.filter((g) => g.kind === 'subsystem'));
  const parentGroup = $derived(subsystems.find((g) => g.id === parent));

  function add() {
    const title = name.trim();
    if (!title) return;
    const id = sync.newId();
    sync.create('group.create', sync.accountScope, id, { name: title, kind, color, ...(kind === 'subsystem' && parent ? { parent_id: parent } : {}) });
    oncreated?.(id);
    onclose();
  }
</script>

<Sheet title="New group" {onclose} {layer}>
  <input class="name" bind:value={name} placeholder="Name" aria-label="Name" maxlength="60" onkeydown={(e) => e.key === 'Enter' && add()} />
  <Swatches value={color} onpick={(c) => (color = c)} />
  <Segmented label="Kind" options={[{ id: 'group', label: 'Group' }, { id: 'subsystem', label: 'Subsystem' }]} bind:value={kind} />
  {#if kind === 'subsystem' && subsystems.length}
    <FormCard><ValueRow label="Where" value={parentGroup ? `Inside ${groupPath(parentGroup, gs)}` : 'Top level'} onclick={() => (choosing = true)} /></FormCard>
  {/if}
  {#snippet footer()}<Button variant="primary" grow class="go" disabled={!name.trim()} onclick={add}>Add {kind}</Button>{/snippet}
</Sheet>

{#if choosing}
  <MenuSheet title="Where it goes" layer={layer + 1} onclose={() => (choosing = false)}>
    <MenuRow label="Top level" selected={!parent} onclick={() => { parent = null; choosing = false; }} />
    {#each subsystems as g (g.id)}
      <MenuRow label="Inside {groupPath(g, gs)}" selected={parent === g.id} onclick={() => { parent = g.id; choosing = false; }} />
    {/each}
  </MenuSheet>
{/if}

<style>
  .name { font: inherit; color: var(--ink); background: var(--surface-2); border: 0; border-radius: var(--r-row); padding: var(--s-3) 14px; }
</style>
