<script lang="ts">
  import { core } from '../core';
  import { fuzzy, groupPath, groups, members, membership } from '../data';
  import { router } from '../router.svelte';
  import { sync, type Projection } from '../sync/client';
  import PkImport from './PkImport.svelte';

  let { projection, dark }: { projection: Projection; dark: boolean } = $props();

  let query = $state('');
  let filter = $state<string>('all'); // 'all' | 'archived' | group id
  let showGroups = $state(false);
  let importing = $state(false);

  const all = $derived(members(projection).filter((m) => !m.deleted));
  const gs = $derived(groups(projection));
  const inGroup = $derived(membership(projection));
  const shown = $derived.by(() => {
    let list = all.filter((m) => (filter === 'archived' ? m.archived : !m.archived));
    if (filter !== 'all' && filter !== 'archived') list = list.filter((m) => inGroup.get(filter)?.has(m.id));
    if (!query.trim()) return list;
    return list
      .map((m) => ({ m, s: fuzzy(query, [m.name, m.display_name, m.pronouns, ...m.sigils].filter(Boolean).join(' ')) }))
      .filter((x) => x.s !== null)
      .sort((a, b) => a.s! - b.s!)
      .map((x) => x.m);
  });

  function addMember() {
    const id = sync.newId();
    sync.create('member.create', sync.accountScope, id, { name: query.trim() || 'New member', color: '#C0694E' });
    query = '';
    router.go(`/members/${id}`);
  }

  let newGroup = $state('');
  let newKind = $state<'subsystem' | 'group'>('subsystem');
  let newParent = $state('');
  function addGroup(e: SubmitEvent) {
    e.preventDefault();
    if (!newGroup.trim()) return;
    sync.create('group.create', sync.accountScope, sync.newId(), {
      name: newGroup.trim(),
      kind: newKind,
      ...(newParent ? { parent_id: newParent } : {}),
    });
    newGroup = '';
  }
</script>

<section class="page">
  <div class="head">
    <h1 class="display">Members</h1>
    <button class="primary" onclick={addMember}>Add member</button>
  </div>

  <input class="search" type="search" placeholder="Search {all.length} members" bind:value={query} aria-label="Search members" />

  <div class="chips" role="tablist" aria-label="Filter">
    <button class:on={filter === 'all'} onclick={() => (filter = 'all')}>All</button>
    {#each gs as g (g.id)}
      <button class:on={filter === g.id} onclick={() => (filter = g.id)} title={groupPath(g, gs)}>{g.name}</button>
    {/each}
    <button class:on={filter === 'archived'} onclick={() => (filter = 'archived')}>Archived</button>
    <button class="ghost" onclick={() => (showGroups = !showGroups)}>{showGroups ? 'Done' : 'Groups…'}</button>
    <button class="ghost" onclick={() => (importing = !importing)}>Import from PluralKit…</button>
  </div>

  {#if importing}
    <PkImport onclose={() => (importing = false)} />
  {/if}

  {#if showGroups}
    <div class="groups">
      <ul>
        {#each gs as g (g.id)}
          <li>
            <span class="kind">{g.kind === 'subsystem' ? 'Subsystem' : 'Group'}</span>
            {groupPath(g, gs)}
            <span class="count">{inGroup.get(g.id)?.size ?? 0}</span>
            <button class="ghost" onclick={() => sync.create('group.delete', sync.accountScope, g.id, {})}>Remove</button>
          </li>
        {:else}
          <li class="muted">No subsystems or groups yet.</li>
        {/each}
      </ul>
      <form onsubmit={addGroup}>
        <input bind:value={newGroup} placeholder="Name" aria-label="Group name" />
        <select bind:value={newKind} aria-label="Kind">
          <option value="subsystem">Subsystem</option>
          <option value="group">Group</option>
        </select>
        <select bind:value={newParent} aria-label="Inside">
          <option value="">Top level</option>
          {#each gs.filter((g) => g.kind === 'subsystem') as g (g.id)}
            <option value={g.id}>Inside {groupPath(g, gs)}</option>
          {/each}
        </select>
        <button class="primary">Add</button>
      </form>
    </div>
  {/if}

  {#if shown.length === 0}
    <p class="muted">
      {#if query}No one matches “{query}”. <button class="ghost" onclick={addMember}>Add “{query}”</button>{:else}Nobody here yet.{/if}
    </p>
  {/if}
  <div class="grid">
    {#each shown as m (m.id)}
      {@const c = core.adaptColor(m.color, dark)}
      <a class="member" href="#/members/{m.id}" style="--ring: {c.ring}; --tint: {c.tint}">
        <span class="avatar" aria-hidden="true">{m.sigils[0] ?? m.name[0]}</span>
        <span class="name" style="color: {c.name}">{m.display_name ?? m.name}</span>
        {#if m.pronouns}<span class="pronouns">{m.pronouns}</span>{/if}
      </a>
    {/each}
  </div>
</section>

<style>
  .page {
    display: grid;
    gap: var(--s-4);
  }
  .head {
    display: flex;
    justify-content: space-between;
    align-items: center;
  }
  h1 {
    font-size: var(--fs-2xl);
  }
  .search,
  .groups input,
  .groups select {
    font: inherit;
    color: var(--ink);
    background: var(--surface);
    border: 1px solid var(--line);
    border-radius: var(--r-md);
    padding: var(--s-3) var(--s-4);
  }
  .groups input,
  .groups select {
    border-radius: var(--r-sm);
    padding: var(--s-2) var(--s-3);
  }
  .chips {
    display: flex;
    flex-wrap: wrap;
    gap: var(--s-2);
  }
  .chips button {
    font-size: var(--fs-sm);
    background: var(--surface);
    border: 1px solid var(--line);
    border-radius: var(--r-full);
    padding: var(--s-1) var(--s-3);
    cursor: pointer;
    color: var(--ink-2);
  }
  .chips button.on {
    background: var(--accent-soft);
    border-color: var(--accent);
    color: var(--ink);
  }
  .chips .ghost {
    border-color: transparent;
    background: none;
  }
  .groups {
    background: var(--surface);
    border: 1px solid var(--line);
    border-radius: var(--r-md);
    padding: var(--s-4);
    display: grid;
    gap: var(--s-3);
  }
  .groups ul {
    list-style: none;
    margin: 0;
    padding: 0;
    display: grid;
    gap: var(--s-2);
  }
  .groups li {
    display: flex;
    gap: var(--s-2);
    align-items: center;
  }
  .kind {
    font-size: var(--fs-xs);
    color: var(--ink-3);
    min-width: 6em;
  }
  .count {
    color: var(--ink-3);
    font-size: var(--fs-sm);
    margin-left: auto;
  }
  .groups form {
    display: flex;
    flex-wrap: wrap;
    gap: var(--s-2);
  }
  .groups form input {
    flex: 1;
    min-width: 8em;
  }
  .primary {
    background: var(--accent);
    color: var(--surface);
    border: 0;
    border-radius: var(--r-full);
    padding: var(--s-2) var(--s-4);
    cursor: pointer;
    font-weight: 600;
  }
  .ghost {
    background: none;
    border: 0;
    color: var(--accent);
    cursor: pointer;
  }
  .muted {
    color: var(--ink-3);
  }
  .grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(104px, 1fr));
    gap: var(--s-3);
  }
  .member {
    display: grid;
    justify-items: center;
    gap: var(--s-1);
    padding: var(--s-3) var(--s-2);
    background: var(--surface);
    border: 1px solid var(--line);
    border-radius: var(--r-md);
    text-decoration: none;
    transition: background var(--t-fast) var(--ease);
  }
  .member:hover {
    background: var(--tint);
  }
  .avatar {
    width: 48px;
    height: 48px;
    display: grid;
    place-items: center;
    font-size: 22px;
    border-radius: 50%;
    background: var(--surface-2);
    box-shadow: 0 0 0 2px var(--ring);
    margin-bottom: var(--s-1);
  }
  .name {
    font-size: var(--fs-sm);
    font-weight: 500;
    text-align: center;
  }
  .pronouns {
    font-size: var(--fs-xs);
    color: var(--ink-3);
  }
</style>
