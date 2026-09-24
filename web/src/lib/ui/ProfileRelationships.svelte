<script lang="ts">
  import { members, relationshipTypes, relationships } from '../data';
  import { sync, type Projection } from '../sync/client';

  let { projection, id, own }: { projection: Projection; id: string; own: boolean } = $props();
  const people = $derived(members(projection).filter((m) => !m.deleted));
  const byId = $derived(new Map(people.map((m) => [m.id, m.display_name ?? m.name])));
  const types = $derived(relationshipTypes(projection));
  const all = $derived(relationships(projection));
  const links = $derived(all.filter((r) => r.from_member_id === id || (r.to_kind === 'member' && r.to_id === id)));
  const typeById = $derived(new Map(types.map((t) => [t.id, t])));

  let typeName = $state('');
  let inverseName = $state('');
  let symmetric = $state(false);
  let selectedType = $state('');
  let targetKind = $state<'member' | 'external'>('member');
  let targetMember = $state('');
  let targetLabel = $state('');
  let note = $state('');
  let error = $state('');

  function createType(e: SubmitEvent) {
    e.preventDefault();
    const name = typeName.trim();
    if (!own || !name) return;
    sync.create('reltype.set', sync.accountScope, sync.newId(), {
      name, inverse_name: symmetric ? name : inverseName.trim() || null, is_symmetric: symmetric,
    });
    typeName = inverseName = '';
    symmetric = false;
  }

  function createLink(e: SubmitEvent) {
    e.preventDefault();
    const type = selectedType || types[0]?.id;
    const to = targetKind === 'member' ? targetMember || people.find((m) => m.id !== id)?.id : targetLabel.trim();
    if (!own || !type || !to || (targetKind === 'member' && to === id)) return;
    error = '';
    try {
      sync.create('relationship.set', sync.accountScope, sync.newId(), {
        from_member_id: id, to_kind: targetKind, to_id: targetKind === 'member' ? to : null,
        to_label: targetKind === 'external' ? to : null, type_id: type, note: note.trim() || null,
        visibility: { mode: 'private' },
      });
      targetLabel = note = '';
    } catch (cause) { error = cause instanceof Error ? cause.message : String(cause); }
  }

  const relatedName = (r: (typeof links)[number]) => r.from_member_id === id
    ? r.to_kind === 'member' ? byId.get(r.to_id ?? '') ?? 'Member' : r.to_label ?? r.to_id ?? 'Someone'
    : byId.get(r.from_member_id) ?? 'Member';
  const relatedType = (r: (typeof links)[number]) => {
    const type = typeById.get(r.type_id);
    return r.from_member_id === id || type?.is_symmetric ? type?.name ?? 'Relationship' : type?.inverse_name ?? `${type?.name ?? 'Relationship'} (incoming)`;
  };
</script>

<section class="relations" aria-label="Relationships">
  {#if links.length}
    <div class="map" aria-label="Relationship map"><strong>{byId.get(id) ?? 'Member'}</strong><span aria-hidden="true">↔</span>
      <div>{#each links as link (link.id)}<span class="node">{relatedName(link)}</span>{/each}</div>
    </div>
    <ul>{#each links as link (link.id)}<li class="link"><span><strong>{relatedType(link)}</strong> · {relatedName(link)}{#if link.note}<small>{link.note}</small>{/if}</span>
      {#if own}<button onclick={() => sync.create('relationship.delete', sync.accountScope, link.id, {})}>Remove</button>{/if}</li>{/each}</ul>
  {:else}<p class="muted">No relationships yet.</p>{/if}

  {#if own}
    <form class="editor" onsubmit={createLink}>
      <h3>Add relationship</h3>
      {#if types.length}
        <label>Type <select bind:value={selectedType} required><option value="" disabled>Choose a type</option>{#each types as type (type.id)}<option value={type.id}>{type.name}</option>{/each}</select></label>
        <label>With <select bind:value={targetKind}><option value="member">A member</option><option value="external">Someone outside Chorus</option></select></label>
        {#if targetKind === 'member'}<label>Member <select bind:value={targetMember} required><option value="" disabled>Choose a member</option>{#each people.filter((m) => m.id !== id) as person (person.id)}<option value={person.id}>{person.display_name ?? person.name}</option>{/each}</select></label>
        {:else}<label>Name <input bind:value={targetLabel} required maxlength="100" /></label>{/if}
        <label>Note <input bind:value={note} maxlength="300" /></label>
        <p class="muted">Relationships are private to this account for now.</p>
        {#if error}<p class="error" role="alert">{error}</p>{/if}
        <button disabled={!(selectedType || types.length === 1)}>Add relationship</button>
      {:else}<p class="muted">Create a relationship type first.</p>{/if}
    </form>
    <form class="editor" onsubmit={createType}>
      <h3>Relationship types</h3>
      <label>Type name <input bind:value={typeName} required maxlength="60" placeholder="Friend" /></label>
      <label><input type="checkbox" bind:checked={symmetric} /> Same in both directions</label>
      {#if !symmetric}<label>Reverse name <input bind:value={inverseName} maxlength="60" placeholder="Friend of" /></label>{/if}
      <button>Add type</button>
      {#if types.length}<ul class="types">{#each types as type (type.id)}<li>{type.name}{#if !type.is_symmetric && type.inverse_name} / {type.inverse_name}{/if}
        <button type="button" disabled={all.some((link) => link.type_id === type.id)} onclick={() => sync.create('reltype.delete', sync.accountScope, type.id, {})}>Delete type</button></li>{/each}</ul>{/if}
    </form>
  {/if}
</section>

<style>
  .relations, .editor { display: grid; gap: var(--s-3); }
  .relations ul { list-style: none; display: grid; gap: var(--s-2); margin: 0; padding: 0; }
  .map, .link, .editor { padding: var(--s-3); border: 1px solid var(--line); border-radius: var(--r-md); background: var(--surface); }
  .map { display: flex; align-items: center; flex-wrap: wrap; gap: var(--s-2); }
  .map > div { display: flex; flex-wrap: wrap; gap: var(--s-1); }
  .node { padding: var(--s-1) var(--s-2); border-radius: var(--r-full); background: var(--accent-soft); }
  .link { display: flex; justify-content: space-between; gap: var(--s-2); }
  .link small { display: block; color: var(--ink-3); }
  h3, p { margin: 0; }
  h3 { font-size: var(--fs-md); }
  label { display: flex; align-items: center; flex-wrap: wrap; gap: var(--s-2); }
  input:not([type='checkbox']), select { min-width: 10ch; max-width: 100%; padding: var(--s-2); border: 1px solid var(--line); border-radius: var(--r-sm); background: var(--surface-2); color: var(--ink); font: inherit; }
  button { justify-self: start; color: var(--accent); cursor: pointer; }
  .muted { color: var(--ink-3); font-size: var(--fs-sm); }
  .error { color: var(--danger); }
</style>
