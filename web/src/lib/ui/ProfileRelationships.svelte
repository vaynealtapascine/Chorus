<script lang="ts">
  import { buckets, members, relationshipTypes, relationships } from '../data';
  import AudienceChip from './AudienceChip.svelte';
  import { audienceOf, type Audience } from '../profile-audience';
  import { apiFetch } from '../http';
  import { apiBase } from '../sync/device';
  import { sync, type Projection } from '../sync/client';
  import Sheet from './Sheet.svelte';
  import Icon from './Icon.svelte';
  import EmptyState from './kit/EmptyState.svelte';
  import UnderlineTabs from './kit/UnderlineTabs.svelte';
  import { openMemberDm } from '../member-dm';

  let { projection, id, own }: { projection: Projection; id: string; own: boolean } = $props();
  const people = $derived(members(projection).filter((m) => !m.deleted));
  let remote = $state<{id: string; account_id: string; name: string; display_name?: string}[]>([]);
  let targetError = $state('');
  async function loadTargets() {
    remote = []; targetError = '';
    try {
      const r = await apiFetch(`${apiBase()}/relationship-targets`, { headers: { authorization: `Bearer ${sync.device?.session ?? ''}` } });
      if (!r.ok) throw new Error('Connect to load shared profiles.');
      remote = (await r.json()).items;
    } catch (e) { targetError = e instanceof Error ? e.message : String(e); }
  }
  $effect(() => { if (own) void loadTargets(); });
  const candidates = $derived([...people.filter((m) => !m.archived).map((m) => ({ id: m.id, name: m.display_name ?? m.name })),
    ...remote.filter((m) => !people.some((p) => p.id === m.id)).map((m) => ({ id: m.id, name: m.display_name ?? m.name }))]);
  const byId = $derived(new Map(candidates.map((m) => [m.id, m.name])));
  const sharing = $derived(buckets(projection));
  const types = $derived(relationshipTypes(projection));
  const all = $derived(relationships(projection));
  const links = $derived(all.filter((r) => r.from_member_id === id || (r.to_kind === 'member' && r.to_id === id)));
  const typeById = $derived(new Map(types.map((t) => [t.id, t])));

  let tab = $state<'inside' | 'friends'>('inside');
  let adding = $state(false);
  let typesOpen = $state(false);
  let editLink = $state('');
  const ownIds = $derived(new Set(people.map((m) => m.id)));
  const otherId = (r: (typeof links)[number]) => (r.from_member_id === id ? (r.to_kind === 'member' ? r.to_id ?? '' : '') : r.from_member_id);
  const isInside = (r: (typeof links)[number]) => { const o = otherId(r); return !!o && ownIds.has(o); };
  const inside = $derived(links.filter(isInside));
  const outside = $derived(links.filter((r) => !isInside(r)));
  const viewHref = (r: (typeof links)[number]) => { const m = remote.find((x) => x.id === otherId(r)); return m ? '#/people/' + encodeURIComponent(m.account_id) + '/' + encodeURIComponent(m.id) : null; };
  let typeName = $state('');
  let inverseName = $state('');
  let symmetric = $state(false);
  let selectedType = $state('');
  let targetKind = $state<'member' | 'external'>('member');
  let targetMember = $state('');
  let targetLabel = $state('');
  let note = $state('');
  let error = $state('');
  let audience = $state<Audience>({ mode: 'private' });

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
        visibility: audience,
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
  <UnderlineTabs label="Relationships" bind:value={tab} tabs={[{ id: 'inside', label: 'Inside', count: inside.length }, { id: 'friends', label: 'Friends', count: outside.length }]} />
  <ul>
    {#each tab === 'inside' ? inside : outside as link (link.id)}
      {@const other = otherId(link)}
      <li class="link">
        <span class="who"><strong>{relatedName(link)}</strong><span class="pill">{relatedType(link)}</span>{#if link.note}<small>{link.note}</small>{/if}</span>
        {#if tab === 'inside' && other && own}<button type="button" class="act" onclick={() => openMemberDm(projection, id, other)}>Message</button>
        {:else if tab === 'friends' && viewHref(link)}<a class="act" href={viewHref(link)}>View</a>{/if}
        {#if own}<button type="button" class="more" aria-label="Edit {relatedName(link)}" onclick={() => (editLink = link.id)}><Icon name="more" size={18} /></button>{/if}
      </li>
    {:else}<EmptyState icon="members" label="No relationships yet" action={own ? 'Add relationship' : undefined} onaction={() => (adding = true)} />{/each}
  </ul>
  {#if own && links.length}<button type="button" class="add" onclick={() => (adding = true)}><Icon name="plus" size={20} />Add relationship</button>{/if}
</section>

{#snippet typeForm()}
  <form class="editor" onsubmit={createType}>
    <label>New type <input bind:value={typeName} required maxlength="60" placeholder="Friend" /></label>
    <label><input type="checkbox" bind:checked={symmetric} /> Same in both directions</label>
    {#if !symmetric}<label>Reverse name <input bind:value={inverseName} maxlength="60" placeholder="Friend of" /></label>{/if}
    <div class="actions"><button class="primary">Add type</button></div>
  </form>
{/snippet}

{#if adding}
  <Sheet title="Add relationship" onclose={() => (adding = false)}>
    {#if types.length}
      <form class="editor" onsubmit={(e) => { createLink(e); adding = false; }}>
        <label>Type <select bind:value={selectedType} required><option value="" disabled>Choose a type</option>{#each types as type (type.id)}<option value={type.id}>{type.name}</option>{/each}</select></label>
        <label>With <select bind:value={targetKind}><option value="member">Someone on Chorus</option><option value="external">Someone outside Chorus</option></select></label>
        {#if targetKind === 'member'}<label>Profile <select bind:value={targetMember} required><option value="" disabled>Choose a profile</option>{#each candidates.filter((m) => m.id !== id) as person (person.id)}<option value={person.id}>{person.name}</option>{/each}</select></label>
        {#if targetError}<p role="status">{targetError}</p><button type="button" onclick={loadTargets}>Refresh shared profiles</button>{/if}
        {:else}<label>Name <input bind:value={targetLabel} required maxlength="100" /></label>{/if}
        <label>Note <input bind:value={note} maxlength="300" /></label>
        <AudienceChip label="New relationship" value={audience} own buckets={sharing} onchange={(a) => (audience = a ?? { mode: 'private' })} />
        {#if error}<p class="error" role="alert">{error}</p>{/if}
        <div class="actions"><button type="button" class="link-btn" onclick={() => { adding = false; typesOpen = true; }}>Types</button><button class="primary" disabled={!(selectedType || types.length === 1)}>Add relationship</button></div>
      </form>
    {:else}
      {@render typeForm()}
    {/if}
  </Sheet>
{/if}
{#if typesOpen}
  <Sheet title="Types" onclose={() => (typesOpen = false)}>
    {@render typeForm()}
    {#each types as type (type.id)}<div class="type">{type.name}{#if !type.is_symmetric && type.inverse_name} / {type.inverse_name}{/if}
      <button type="button" disabled={all.some((l) => l.type_id === type.id)} onclick={() => sync.create('reltype.delete', sync.accountScope, type.id, {})}>Delete</button></div>{/each}
  </Sheet>
{/if}
{#if editLink}
  {@const link = links.find((l) => l.id === editLink)}
  {#if link}
    <Sheet title={relatedName(link)} onclose={() => (editLink = '')}>
      <AudienceChip label="Relationship" value={audienceOf(link.visibility)} own buckets={sharing} onchange={(a) => sync.create('relationship.set', sync.accountScope, link.id, { visibility: a ?? { mode: 'private' } })} />
      <button type="button" class="danger" onclick={() => { sync.create('relationship.delete', sync.accountScope, link.id, {}); editLink = ''; }}>Remove</button>
    </Sheet>
  {/if}
{/if}

<style>
  .who { flex: 1; min-width: 0; display: grid; gap: 2px; }
  .pill { justify-self: start; padding: 1px 10px; border-radius: var(--r-full); background: var(--accent-soft); color: var(--accent); font-size: var(--fs-xs); font-weight: 500; }
  .act { padding: 6px 14px; border: 1px solid var(--line); border-radius: var(--r-full); color: var(--ink); text-decoration: none; background: none; font: inherit; font-weight: 600; font-size: var(--fs-sm); }
  .more { display: grid; place-items: center; width: 36px; height: 36px; padding: 0; border: 0; background: none; color: var(--ink-3); border-radius: 50%; }
  .add { display: flex; align-items: center; gap: var(--s-2); padding: var(--s-3); border: 1px dashed var(--line); border-radius: var(--r-row); background: none; justify-self: stretch; justify-content: center; }
  .primary { background: var(--accent); color: var(--on-accent); border: 0; border-radius: var(--r-sm); padding: var(--s-2) var(--s-4); }
  .actions { display: flex; justify-content: space-between; align-items: center; }
  .link-btn { border: 0; background: none; }
  .danger { color: var(--danger); }
  .type { display: flex; justify-content: space-between; align-items: center; padding: var(--s-1) 0; }
  .relations, .editor { display: grid; gap: var(--s-3); }
  .relations ul { list-style: none; display: grid; gap: var(--s-2); margin: 0; padding: 0; }
  .link, .editor { padding: var(--s-3); border: 1px solid var(--line); border-radius: var(--r-md); background: var(--surface); }
  .link { display: flex; justify-content: space-between; gap: var(--s-2); }
  .link small { color: var(--ink-3); }
  label { display: flex; align-items: center; flex-wrap: wrap; gap: var(--s-2); }
  input:not([type='checkbox']), select { min-width: 10ch; max-width: 100%; padding: var(--s-2); border: 1px solid var(--line); border-radius: var(--r-sm); background: var(--surface-2); color: var(--ink); font: inherit; }
  button { justify-self: start; color: var(--accent); cursor: pointer; }
  .error { color: var(--danger); }
</style>
