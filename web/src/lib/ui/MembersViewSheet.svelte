<script lang="ts">
  // What each row of the Members list shows, and its folders (D-100; Android MembersCustomize). Every
  // change saves at once as an account pref, so the system's devices agree.
  import { fieldDefs } from '../data';
  import { sync, type Projection } from '../sync/client';
  import { addFolder, FOLDERS, memberListPrefs, removeFolder, renameFolder, roleField, roleFields, VIEW, type MembersView, type PillMode } from '../member-list';
  import Icon from './Icon.svelte';
  import Sheet from './Sheet.svelte';
  import ConfirmSheet from './kit/ConfirmSheet.svelte';
  import FormCard from './kit/FormCard.svelte';
  import Segmented from './kit/Segmented.svelte';
  import SectionLabel from './kit/SectionLabel.svelte';
  import Switch from './kit/Switch.svelte';
  import ValueRow from './kit/ValueRow.svelte';
  import MenuRow from './kit/MenuRow.svelte';
  import MenuSheet from './kit/MenuSheet.svelte';
  import DragList from './kit/DragList.svelte';

  let { projection, onclose }: { projection: Projection; onclose: () => void } = $props();

  const prefs = $derived(memberListPrefs((projection.rows.pref ?? {}) as never, sync.accountId));
  const view = $derived(prefs.view);
  const folders = $derived(prefs.folders);
  const defs = $derived(fieldDefs(projection));
  const rolesFrom = $derived(roleField(view, defs));

  const pref = (key: string, value: unknown) => sync.create('pref.set', sync.accountScope, null, { device: '', key, value });
  const setView = (patch: Partial<MembersView>) => pref(VIEW, { ...view, ...patch });
  const setFolders = (list: typeof folders) => pref(FOLDERS, list);

  let pills = $state<PillMode>('first');
  $effect(() => { pills = view.pills; });
  const PILLS: { id: PillMode; label: string }[] = [{ id: 'off', label: 'Off' }, { id: 'first', label: 'First' }, { id: 'all', label: 'All' }];

  let choosing = $state(false);
  function setUpRoles() {
    const id = sync.newId();
    sync.create('field.define', sync.accountScope, id, { name: 'Roles', type: 'multi_select', options: { choices: [] } });
    setView({ role_field: id });
  }
  let newFolder = $state('');
  function createFolder(e: SubmitEvent) {
    e.preventDefault();
    if (!newFolder.trim()) return;
    setFolders(addFolder(folders, sync.newId(), newFolder));
    newFolder = '';
  }
  let removing = $state<{ id: string; name: string } | null>(null);
  const reorder = (ids: string[]) => setFolders(ids.map((id) => folders.find((f) => f.id === id)!));
</script>

<Sheet title="Members list" {onclose}>
  <FormCard>
    <div class="row"><span>Role pills</span><div class="seg"><Segmented label="Role pills" options={PILLS} bind:value={pills} onchange={(v) => setView({ pills: v })} /></div></div>
    {#if pills !== 'off'}
      {#if roleFields(defs).length}
        <ValueRow label="Roles come from" value={rolesFrom?.name ?? 'Choose a field'} onclick={() => (choosing = true)} />
      {:else}
        <ValueRow label="Set up Roles" icon="plus" onclick={setUpRoles} />
      {/if}
    {/if}
    <Switch label="A line of their bio" checked={view.bio} onchange={(on) => setView({ bio: on })} />
    <Switch label="Their banner behind the row" checked={view.banners} onchange={(on) => setView({ banners: on })} />
  </FormCard>

  <SectionLabel label="Folders" />
  {#if folders.length}
    <FormCard>
      <DragList items={folders} onreorder={reorder} label="Reorder folders">
        {#snippet row(f)}
          <input class="name" value={f.name} aria-label="Folder name" maxlength="40" onchange={(e) => setFolders(renameFolder(folders, f.id, e.currentTarget.value))} />
          <button class="icon" aria-label="Delete {f.name}" onclick={() => (removing = { id: f.id, name: f.name })}><Icon name="delete" size={18} /></button>
        {/snippet}
      </DragList>
    </FormCard>
  {/if}
  <form class="new" onsubmit={createFolder}>
    <Icon name="plus" size={18} />
    <input bind:value={newFolder} placeholder="New folder" aria-label="New folder name" maxlength="40" />
  </form>
</Sheet>

{#if choosing}
  <MenuSheet title="Roles come from" layer={1} onclose={() => (choosing = false)}>
    {#each roleFields(defs) as d (d.id)}
      <MenuRow label={d.name} selected={rolesFrom?.id === d.id} onclick={() => { setView({ role_field: d.id }); choosing = false; }} />
    {/each}
  </MenuSheet>
{/if}
{#if removing}
  <ConfirmSheet title="Delete {removing.name}?" message="Everyone in it stays." confirm="Delete" danger layer={1} onclose={() => (removing = null)}
    onconfirm={() => { setFolders(removeFolder(folders, removing!.id)); removing = null; }} />
{/if}

<style>
  .row { display: flex; align-items: center; justify-content: space-between; gap: var(--s-3); padding: var(--s-2) 14px; min-height: 52px; }
  .seg { width: 190px; }
  .name { flex: 1; min-width: 0; font: inherit; font-size: var(--fs-md); color: var(--ink); background: none; border: 0; padding: var(--s-3) var(--s-1); }
  .icon { display: grid; place-items: center; width: 40px; height: 40px; border: 0; background: none; color: var(--ink-3); cursor: pointer; }
  .new { display: flex; align-items: center; gap: var(--s-2); padding: 0 14px; min-height: 48px; border: 1.5px dashed var(--line); border-radius: var(--r-md); color: var(--accent); }
  .new input { flex: 1; font: inherit; color: var(--ink); background: none; border: 0; padding: var(--s-3) 0; }
</style>
