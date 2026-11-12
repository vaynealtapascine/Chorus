<script lang="ts">
  // Members (F4.1): a title row, tabs, counted chips and flat rows. The view options, folders, Select,
  // Reorder and Import sit behind one filter icon.
  import Button from './kit/Button.svelte';
  import { core } from '../core';
  import { fieldDefs, fieldValues, fuzzy, groups, type MemberRow } from '../data';
  import { router } from '../router.svelte';
  import { sync, type Projection } from '../sync/client';
  import { counts, eligible, frontingIds, groupLine, groupMembers, layerName, names } from '../member-directory';
  import { orderedMembers } from '../member-order';
  import { addFolder, bioLine, FOLDER_OF, FOLDERS, memberListPrefs, memberSections, pillsFor, roleField, rolesOf, VIEW, type MembersView } from '../member-list';
  import AvatarImage from './AvatarImage.svelte';
  import MemberBanner from './MemberBanner.svelte';
  import Icon from './Icon.svelte';
  import PkImport from './PkImport.svelte';
  import MembersViewSheet from './MembersViewSheet.svelte';
  import MemberGroups from './MemberGroups.svelte';
  import CustomFronts from './CustomFronts.svelte';
  import CustomFields from './CustomFields.svelte';
  import MemberReorder from './MemberReorder.svelte';
  import GroupTile from './GroupTile.svelte';
  import EmptyState from './kit/EmptyState.svelte';
  import MenuRow from './kit/MenuRow.svelte';
  import MenuSheet from './kit/MenuSheet.svelte';
  import SectionLabel from './kit/SectionLabel.svelte';
  import UnderlineTabs from './kit/UnderlineTabs.svelte';

  let { projection, dark, tab }: { projection: Projection; dark: boolean; tab?: string } = $props();

  type Tab = 'members' | 'groups' | 'fronts' | 'fields';
  const current = $derived<Tab>(tab === 'groups' || tab === 'fronts' || tab === 'fields' ? tab : 'members');
  const go = (t: Tab) => router.go(t === 'members' ? '/members' : `/members/${t}`);

  let query = $state('');
  let searching = $state(false);
  let filter = $state<'all' | 'fronting' | 'archived'>('all');
  let menu = $state(false);
  let customizing = $state(false);
  let importing = $state(false);
  let creatingGroup = $state(false);
  let creatingFront = $state(false);
  let creatingField = $state(false);

  const account = $derived(sync.accountId);
  const active = $derived(eligible(projection, account, false));
  const archived = $derived(eligible(projection, account, true));
  const n = $derived(counts(projection, account));
  const fronting = $derived(frontingIds(projection, account));
  const gs = $derived(groups(projection));

  // list settings and folders (D-100): account prefs, so every device of the system agrees
  const prefs = $derived(memberListPrefs((projection.rows.pref ?? {}) as never, account));
  const view = $derived(prefs.view);
  const folders = $derived(prefs.folders);
  const defs = $derived(fieldDefs(projection));
  const values = $derived(fieldValues(projection));
  const rolesFrom = $derived(roleField(view, defs));
  const rolesOfMember = (m: MemberRow) => (rolesFrom ? rolesOf(values.get(m.id)?.get(rolesFrom.id)) : []);

  function pref(key: string, value: unknown) { sync.create('pref.set', sync.accountScope, null, { device: '', key, value }); }
  const setView = (patch: Partial<MembersView>) => pref(VIEW, { ...view, ...patch });
  function file(ids: string[], folder: string | null) {
    for (const id of ids) if ((prefs.folderOf.get(id) ?? null) !== folder) pref(FOLDER_OF + id, folder);
  }

  // folded-up folders are this device's own choice
  let collapsed = $state(new Set<string>(JSON.parse(localStorage.getItem('chorus.members.collapsed') ?? '[]') as string[]));
  function toggleCollapsed(id: string) {
    const next = new Set(collapsed);
    if (!next.delete(id)) next.add(id);
    collapsed = next;
    localStorage.setItem('chorus.members.collapsed', JSON.stringify([...next]));
  }

  const filtered = $derived(filter === 'archived' ? archived : filter === 'fronting' ? active.filter((m) => fronting.has(m.id)) : active);
  const searched = $derived.by(() => {
    if (!query.trim()) return null;
    return filtered
      .map((m) => ({ m, s: fuzzy(query, [m.name, m.display_name, m.pronouns, ...m.sigils, ...rolesOfMember(m)].filter(Boolean).join(' ')) }))
      .filter((x) => x.s !== null)
      .sort((a, b) => a.s! - b.s!)
      .map((x) => x.m);
  });
  /** Folders only for the plain list of active members; searches, Fronting, Archived and the flat view are one list. */
  const foldered = $derived(!view.flat && !searched && filter === 'all');
  const sections = $derived(foldered
    ? memberSections(filtered, folders, prefs.folderOf).filter((s) => s.folder || s.members.length || !folders.length)
    : [{ folder: null, members: searched ?? orderedMembers(filtered) }]);
  const shownCount = $derived(sections.reduce((k, s) => k + s.members.length, 0));
  const plain = $derived(filter === 'all' && !searched);

  function addMember() {
    const id = sync.newId();
    sync.create('member.create', sync.accountScope, id, { name: query.trim() || 'New member', color: '#C0694E' });
    query = '';
    router.go(`/members/${id}`);
  }

  // filing: a row's Move menu, choosing several, or dragging onto a folder
  let moving = $state<string[] | null>(null);
  let selecting = $state(false);
  let picked = $state(new Set<string>());
  function pick(id: string) {
    const next = new Set(picked);
    if (!next.delete(id)) next.add(id);
    picked = next;
  }
  function endSelecting() { selecting = false; picked = new Set(); }
  function moveTo(folder: string | null) {
    if (!moving) return;
    file(moving, folder);
    moving = null;
    if (selecting) endSelecting();
  }
  let newFolder = $state('');
  function newFolderAndMove() {
    const name = newFolder.trim();
    if (!name || !moving) return;
    const id = sync.newId();
    pref(FOLDERS, addFolder(folders, id, name));
    newFolder = '';
    moveTo(id);
  }
  let dropOn = $state<string | null | undefined>(undefined);
  function dragStart(e: DragEvent, id: string) {
    const ids = picked.has(id) ? [...picked] : [id];
    e.dataTransfer?.setData('application/x-chorus-members', JSON.stringify(ids));
    if (e.dataTransfer) e.dataTransfer.effectAllowed = 'move';
  }
  function dragOver(e: DragEvent, folder: string | null) {
    if (!e.dataTransfer?.types.includes('application/x-chorus-members')) return;
    e.preventDefault();
    dropOn = folder;
  }
  function drop(e: DragEvent, folder: string | null) {
    const raw = e.dataTransfer?.getData('application/x-chorus-members');
    dropOn = undefined;
    if (!raw) return;
    e.preventDefault();
    file(JSON.parse(raw) as string[], folder);
    if (selecting) endSelecting();
  }

  const tabs = $derived([
    { id: 'members' as Tab, label: 'Members' },
    { id: 'groups' as Tab, label: 'Groups' },
    { id: 'fronts' as Tab, label: 'Custom fronts' },
    { id: 'fields' as Tab, label: 'Custom fields' },
  ]);
  const subtitle = $derived(current === 'groups' ? `${gs.length} ${gs.length === 1 ? 'group' : 'groups'}` : `${n.all} ${n.all === 1 ? 'member' : 'members'}`);
  const addLabel = $derived(current === 'groups' ? 'New group' : current === 'fronts' ? 'New custom front' : current === 'fields' ? 'Add field' : 'Add member');
  function add() {
    if (current === 'groups') creatingGroup = true;
    else if (current === 'fronts') creatingFront = true;
    else if (current === 'fields') creatingField = true;
    else addMember();
  }
</script>

{#if tab === 'reorder'}
  <MemberReorder {projection} {dark} />
{:else}
<section class="page">
  <header class="title">
    <div><h1 class="sr">Members</h1><p>{subtitle}</p></div>
    {#if current === 'members'}
      <button class="bar-icon" aria-label="Search" aria-pressed={searching} onclick={() => { searching = !searching; if (!searching) query = ''; }}><Icon name="search" size={22} /></button>
    {/if}
    <button class="bar-icon" aria-label={addLabel} onclick={add}><Icon name="plus" size={22} /></button>
  </header>
  <UnderlineTabs label="Members sections" value={current} {tabs} onchange={go} />

  {#if current === 'groups'}
    <MemberGroups {projection} {dark} bind:creating={creatingGroup} />
  {:else if current === 'fronts'}
    <CustomFronts {projection} bind:creating={creatingFront} />
  {:else if current === 'fields'}
    <CustomFields {projection} bind:creating={creatingField} />
  {:else}
    {#if searching}
      <input class="search" type="search" placeholder="Search {active.length} members" bind:value={query} aria-label="Search members" />
    {/if}
    <div class="chips" role="tablist" aria-label="Show">
      <button role="tab" aria-selected={filter === 'all'} class:on={filter === 'all'} onclick={() => (filter = 'all')}>All <span>{n.all}</span></button>
      <button role="tab" aria-selected={filter === 'fronting'} class:on={filter === 'fronting'} onclick={() => (filter = 'fronting')}>Fronting <span>{n.fronting}</span></button>
      {#if n.archived}<button role="tab" aria-selected={filter === 'archived'} class:on={filter === 'archived'} onclick={() => (filter = 'archived')}>Archived <span>{n.archived}</span></button>{/if}
      <button class="bar-icon filter" aria-label="View and organise" aria-haspopup="menu" onclick={() => (menu = true)}><Icon name="sliders" size={20} /></button>
    </div>

    {#if shownCount === 0 && !(foldered && folders.length)}
      {#if query}<EmptyState icon="search" label="No matches" action="Add “{query}”" onaction={addMember} />
      {:else}<EmptyState icon="members" label={filter === 'archived' ? 'None yet' : 'No members yet'} action={filter === 'all' ? 'Add member' : undefined} onaction={addMember} />{/if}
    {/if}

    {#each sections as s (s.folder?.id ?? '')}
      {@const key = s.folder?.id ?? null}
      {@const open = !s.folder || !collapsed.has(s.folder.id)}
      <div class="section" class:drop={dropOn === key} role="group" aria-label={s.folder?.name ?? 'Not in a folder'}
        ondragover={(e) => foldered && dragOver(e, key)} ondragleave={() => (dropOn = undefined)} ondrop={(e) => foldered && drop(e, key)}>
        {#if foldered && (s.folder || folders.length)}
          <button class="folder-head" aria-expanded={open} onclick={() => s.folder && toggleCollapsed(s.folder.id)} disabled={!s.folder}>
            {#if s.folder}<span class="caret" class:open><Icon name="chevron" size={14} /></span><Icon name="folder" size={16} />{/if}
            <span class="folder-name">{s.folder?.name ?? 'Not in a folder'}</span>
            <span class="count">{s.members.length}</span>
          </button>
        {:else if plain && filter === 'all'}
          <SectionLabel label="All members" />
        {/if}
        {#if open}
          {#if s.folder && !s.members.length}<p class="empty">Empty</p>{/if}
          <div class="list" role="list">
            {#each s.members as m (m.id)}{@render row(m)}{/each}
          </div>
        {/if}
      </div>
    {/each}

    {#if plain}
      {#if gs.length}
        <SectionLabel label="Groups" link="Manage" href="#/members/groups" />
        <div class="list">
          {#each gs.slice(0, 4) as g (g.id)}
            {@const inside = groupMembers(projection, g.id)}
            <a class="tile-row" href="#/members/groups">
              <GroupTile group={g} />
              <span class="info"><strong>{g.name}</strong><span class="sub">{inside.length ? names(inside) : groupLine(0, undefined)}</span></span>
              {#if inside.length}<span class="badge">{inside.length}</span>{/if}
              <Icon name="chevron-right" size={18} />
            </a>
          {/each}
        </div>
      {/if}
      {#if n.archived}
        <div class="list">
          <button class="tile-row" onclick={() => (filter = 'archived')}>
            <span class="tile"><Icon name="history" size={22} /></span>
            <span class="info"><strong>Archived members</strong><span class="sub">{n.archived}</span></span>
            <Icon name="chevron-right" size={18} />
          </button>
        </div>
      {/if}
    {/if}

    {#if selecting}
      <div class="selbar" role="toolbar" aria-label="Selected members">
        <span>{picked.size} selected</span>
        <Button variant="primary" size="sm" class="go" disabled={!picked.size} onclick={() => (moving = [...picked])}>Move</Button>
        <Button variant="quiet" size="sm" class="quiet" onclick={endSelecting}>Cancel</Button>
      </div>
    {/if}
  {/if}
</section>
{/if}

{#snippet row(m: MemberRow)}
  {@const c = core.adaptColor(m.color, dark)}
  {@const roles = pillsFor(rolesOfMember(m), view.pills)}
  {@const bio = view.bio ? bioLine(m.description) : undefined}
  {@const banner = view.banners && m.banner_blob}
  {@const layer = layerName(projection, account, m.id)}
  {@const line = [m.pronouns, ...roles].filter(Boolean).join(' · ')}
  <div class="member" role="listitem" class:has-banner={!!banner} class:picked={picked.has(m.id)} style="--ring: {c.ring}; --avatar-ink: {c.avatar_ink}"
    draggable={foldered} ondragstart={(e) => dragStart(e, m.id)} ondragend={() => (dropOn = undefined)}>
    {#if banner}<MemberBanner hash={banner} />{/if}
    {#if selecting}
      <label class="hit"><input type="checkbox" checked={picked.has(m.id)} onchange={() => pick(m.id)} aria-label="Select {m.display_name ?? m.name}" />{@render body()}</label>
    {:else}
      <a class="hit" href="#/members/{m.id}" draggable="false">{@render body()}</a>
    {/if}
    <span class="end">
      {#if layer}<span class="layer">{layer}</span>{/if}
      {#if !selecting}
        {#if foldered && folders.length}<button class="bar-icon" aria-label="Move {m.display_name ?? m.name} to a folder" onclick={() => (moving = [m.id])}><Icon name="folder" size={18} /></button>{/if}
        <Icon name="chevron-right" size={18} />
      {/if}
    </span>
  </div>
  {#snippet body()}
    <span class="avatar" aria-hidden="true"><AvatarImage hash={m.avatar_blob} glyph={m.sigils[0] ?? m.name[0]} name={m.name} /></span>
    <span class="info">
      <strong>{m.display_name ?? m.name}</strong>
      {#if line}<span class="sub">{line}</span>{/if}
      {#if bio}<span class="sub">{bio}</span>{/if}
    </span>
  {/snippet}
{/snippet}

{#if menu}
  <MenuSheet title="View" onclose={() => (menu = false)}>
    <MenuRow label="Folders" icon="folder" selected={!view.flat} onclick={() => { setView({ flat: false }); menu = false; }} />
    <MenuRow label="List" icon="menu" selected={view.flat} onclick={() => { setView({ flat: true }); menu = false; }} />
    <MenuRow label="Select" icon="select" onclick={() => { menu = false; filter = 'all'; if (view.flat) setView({ flat: false }); selecting = true; }} />
    <MenuRow label="Customize" icon="sliders" onclick={() => { menu = false; customizing = true; }} />
    <MenuRow label="Reorder" icon="grip" href="#/members/reorder" onclick={() => (menu = false)} />
    <MenuRow label="Import from PluralKit" icon="user-plus" onclick={() => { menu = false; importing = true; }} />
  </MenuSheet>
{/if}
{#if customizing}<MembersViewSheet {projection} onclose={() => (customizing = false)} />{/if}
{#if importing}<PkImport onclose={() => (importing = false)} />{/if}
{#if moving}
  <MenuSheet title="Move to" onclose={() => (moving = null)}>
    {#each folders as f (f.id)}
      <MenuRow label={f.name} icon="folder" selected={moving.length === 1 ? prefs.folderOf.get(moving[0]) === f.id : undefined} onclick={() => moveTo(f.id)} />
    {/each}
    <MenuRow label="Not in a folder" onclick={() => moveTo(null)} />
    <form class="new-folder" onsubmit={(e) => { e.preventDefault(); newFolderAndMove(); }}>
      <input bind:value={newFolder} placeholder="New folder" aria-label="New folder name" maxlength="40" />
      <Button variant="primary" size="sm" class="go" disabled={!newFolder.trim()}>Add</Button>
    </form>
  </MenuSheet>
{/if}

<style>
  .page { display: grid; gap: var(--s-3); align-content: start; }
  .title { display: flex; align-items: center; gap: var(--s-1); }
  .title > div { flex: 1; min-width: 0; }
  .sr { position: absolute; width: 1px; height: 1px; overflow: hidden; clip-path: inset(50%); white-space: nowrap; }
  .title p { margin: 0; color: var(--ink-3); font-size: 14px; }
  .bar-icon { display: inline-grid; place-items: center; width: 40px; height: 40px; border: 0; border-radius: 50%; background: none; color: var(--ink); cursor: pointer; flex: none; }
  .bar-icon:hover { background: var(--surface-2); }
  .bar-icon[aria-pressed='true'] { color: var(--accent); }
  .search { font: inherit; color: var(--ink); background: var(--surface-2); border: 0; border-radius: var(--r-row); padding: 10px 14px; }
  .chips { display: flex; align-items: center; gap: var(--s-2); flex-wrap: wrap; }
  .chips > button:not(.filter) { display: inline-flex; gap: 6px; border: 0; border-radius: var(--r-full); background: var(--surface-2); color: var(--ink-2); font: inherit; font-size: 14px; padding: 6px 14px; cursor: pointer; }
  .chips > button.on { background: var(--accent-soft); color: var(--accent); font-weight: 600; }
  .chips .filter { margin-left: auto; color: var(--ink-2); }
  .section { display: grid; gap: var(--s-1); border-radius: var(--r-control); }
  .section.drop { background: var(--accent-soft); outline: 2px dashed var(--accent); outline-offset: 4px; }
  .folder-head { display: flex; align-items: center; gap: var(--s-2); border: 0; background: none; color: var(--ink-2); font: inherit; font-weight: 600; padding: var(--s-2) 2px; cursor: pointer; text-align: left; }
  .folder-head:disabled { cursor: default; color: var(--ink-3); }
  .count { color: var(--ink-3); font-size: var(--fs-sm); font-weight: 400; }
  .caret { display: inline-flex; transition: transform var(--t-fast) var(--ease); }
  .caret.open { transform: rotate(90deg); }
  .empty { margin: 0; padding: 10px 14px; color: var(--ink-3); border: 1px dashed var(--line); border-radius: var(--r-row); }
  .list { display: grid; }
  .member, .tile-row { position: relative; display: flex; align-items: center; min-width: 0; min-height: 64px; border-radius: var(--r-row); isolation: isolate; color: var(--ink); }
  .member:hover, .tile-row:hover { background: var(--surface-2); }
  .member :global(.banner) { border-radius: 0 12px 12px 0; z-index: -1; }
  .member.picked { outline: 2px solid var(--accent); }
  .member[draggable='true'] { cursor: grab; }
  .hit { display: flex; align-items: center; gap: var(--s-3); flex: 1; min-width: 0; align-self: stretch; padding: var(--s-2) var(--s-1) var(--s-2) 6px; text-decoration: none; color: inherit; cursor: pointer; }
  .hit input { width: 18px; height: 18px; accent-color: var(--accent); flex: none; }
  .avatar { width: 44px; height: 44px; display: grid; place-items: center; flex: none; font-weight: 700; font-size: 20px; border-radius: 50%; overflow: hidden; background: var(--ring); color: var(--avatar-ink); }
  .info { display: grid; flex: 1; min-width: 0; }
  .info strong { font-size: var(--fs-base); font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .sub { color: var(--ink-3); font-size: var(--fs-sm); line-height: 18px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .has-banner .info strong, .has-banner .sub { text-shadow: 0 0 6px var(--surface), 0 0 2px var(--surface); }
  .end { display: flex; align-items: center; gap: var(--s-1); padding-right: var(--s-2); color: var(--ink-3); }
  .layer { border-radius: var(--r-full); background: var(--accent-soft); color: var(--accent); font-size: var(--fs-sm); font-weight: 600; padding: var(--s-1) var(--s-3); white-space: nowrap; }
  .tile-row { width: 100%; gap: var(--s-3); padding: var(--s-2) var(--s-2) var(--s-2) 6px; border: 0; background: none; font: inherit; text-align: left; text-decoration: none; cursor: pointer; }
  .tile-row > :global(.icon) { color: var(--ink-3); }
  .tile { width: 44px; height: 44px; display: grid; place-items: center; border-radius: var(--r-row); background: var(--surface-2); color: var(--ink-2); flex: none; }
  .badge { min-width: 24px; text-align: center; border-radius: var(--r-full); background: var(--surface-2); font-size: var(--fs-xs); font-weight: 600; padding: 2px var(--s-2); }
  .selbar { position: sticky; bottom: 12px; display: flex; align-items: center; gap: 10px; padding: var(--s-2) var(--s-3) var(--s-2) 18px; background: var(--surface); border: 1px solid var(--line); border-radius: var(--r-full); box-shadow: var(--shadow-pop); justify-self: center; z-index: 5; }
  .new-folder { display: flex; gap: var(--s-2); padding: var(--s-2) var(--s-3); }
  .new-folder input { flex: 1; min-width: 0; font: inherit; color: var(--ink); background: var(--surface-2); border: 0; border-radius: var(--r-row); padding: var(--s-2) var(--s-3); }
</style>
