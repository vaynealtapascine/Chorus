<script lang="ts">
  import { core } from '../core';
  import { fieldDefs, fieldValues, fuzzy, groupPath, groups, members, membership, type MemberRow } from '../data';
  import { router } from '../router.svelte';
  import { sync, type Projection } from '../sync/client';
  import { bodyPresence } from '../front-view';
  import PkImport from './PkImport.svelte';
  import AvatarImage from './AvatarImage.svelte';
  import MemberBanner from './MemberBanner.svelte';
  import Icon from './Icon.svelte';
  import { orderedMembers } from '../member-order';
  import {
    addFolder, bioLine, FOLDER_OF, FOLDERS, memberListPrefs, memberSections, moveFolder, pillsFor, removeFolder,
    renameFolder, roleColor, roleField, roleFields, rolesOf, VIEW, type MembersView, type PillMode,
  } from '../member-list';

  let { projection, dark }: { projection: Projection; dark: boolean } = $props();

  let query = $state('');
  let filter = $state<string>('all'); // 'all' | 'archived' | group id
  let showGroups = $state(false);
  let customizing = $state(false);
  let importing = $state(false);

  const all = $derived(members(projection).filter((m) => !m.deleted && (!projection.rows.member[m.id]?.fields.created_by_account_id || projection.rows.member[m.id].fields.created_by_account_id === sync.accountId)));
  const here = $derived(new Set(bodyPresence(projection, sync.accountId).map((e) => e.subject_id)));
  const gs = $derived(groups(projection));
  const inGroup = $derived(membership(projection));

  // ── list settings and folders (D-100): account prefs, so every device of the system agrees ──
  const prefs = $derived(memberListPrefs((projection.rows.pref ?? {}) as never, sync.accountId));
  const view = $derived(prefs.view);
  const folders = $derived(prefs.folders);
  const defs = $derived(fieldDefs(projection));
  const values = $derived(fieldValues(projection));
  const rolesFrom = $derived(roleField(view, defs));
  const rolesOfMember = (m: MemberRow) => (rolesFrom ? rolesOf(values.get(m.id)?.get(rolesFrom.id)) : []);

  function pref(key: string, value: unknown) {
    sync.create('pref.set', sync.accountScope, null, { device: '', key, value });
  }
  const setView = (patch: Partial<MembersView>) => pref(VIEW, { ...view, ...patch });
  const setFolders = (list: typeof folders) => pref(FOLDERS, list);
  function file(ids: string[], folder: string | null) {
    for (const id of ids) if ((prefs.folderOf.get(id) ?? null) !== folder) pref(FOLDER_OF + id, folder);
  }

  function setUpRoles() {
    const id = sync.newId();
    sync.create('field.define', sync.accountScope, id, { name: 'Roles', type: 'multi_select', options: { choices: [] } });
    setView({ role_field: id });
  }

  let newFolder = $state('');
  function createFolder(e?: SubmitEvent) {
    e?.preventDefault();
    if (!newFolder.trim()) return;
    const id = sync.newId();
    setFolders(addFolder(folders, id, newFolder));
    if (picked.size) { file([...picked], id); picked = new Set(); selecting = false; }
    newFolder = '';
  }
  function deleteFolder(id: string, name: string) {
    if (!confirm(`Delete the folder “${name}”? The people in it stay; they just move out of the folder.`)) return;
    setFolders(removeFolder(folders, id));
  }

  // folded-up folders are this device's own choice
  let collapsed = $state(new Set<string>(JSON.parse(localStorage.getItem('chorus.members.collapsed') ?? '[]') as string[]));
  function toggleCollapsed(id: string) {
    const next = new Set(collapsed);
    if (!next.delete(id)) next.add(id);
    collapsed = next;
    localStorage.setItem('chorus.members.collapsed', JSON.stringify([...next]));
  }

  // ── which members, and how they are laid out ──
  const filtered = $derived.by(() => {
    let list = all.filter((m) => (filter === 'archived' ? m.archived : !m.archived));
    if (filter !== 'all' && filter !== 'archived') list = list.filter((m) => inGroup.get(filter)?.has(m.id));
    return list;
  });
  const searched = $derived.by(() => {
    if (!query.trim()) return null;
    return filtered
      .map((m) => ({ m, s: fuzzy(query, [m.name, m.display_name, m.pronouns, ...m.sigils, ...rolesOfMember(m)].filter(Boolean).join(' ')) }))
      .filter((x) => x.s !== null)
      .sort((a, b) => a.s! - b.s!)
      .map((x) => x.m);
  });
  /** Folders only for the plain list of active members; searches, Archived and the flat view are one list. */
  const foldered = $derived(!view.flat && !searched && filter !== 'archived');
  const sections = $derived(foldered
    ? memberSections(filtered, folders, prefs.folderOf).filter((s) => s.folder ? filter === 'all' || s.members.length : s.members.length || !folders.length)
    : [{ folder: null, members: searched ?? orderedMembers(filtered) }]);
  const shownCount = $derived(sections.reduce((n, s) => n + s.members.length, 0));

  function addMember() {
    const id = sync.newId();
    sync.create('member.create', sync.accountScope, id, { name: query.trim() || 'New member', color: '#C0694E' });
    query = '';
    router.go(`/members/${id}`);
  }

  // ── filing: a row's Move menu, choosing several, or dragging onto a folder ──
  let menuFor = $state<string | null>(null);
  let selecting = $state(false);
  let picked = $state(new Set<string>());
  function pick(id: string) {
    const next = new Set(picked);
    if (!next.delete(id)) next.add(id);
    picked = next;
  }
  function endSelecting() { selecting = false; picked = new Set(); }
  function moveTo(folder: string | null) {
    if (menuFor) { file([menuFor], folder); menuFor = null; return; }
    file([...picked], folder);
    endSelecting();
  }
  let dropOn = $state<string | null | undefined>(undefined); // folder id, null = "not in a folder"
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
  const PILL_MODES: { id: PillMode; label: string }[] = [{ id: 'off', label: 'Off' }, { id: 'first', label: 'First only' }, { id: 'all', label: 'All' }];
</script>

<svelte:window onclick={() => (menuFor = null)} onkeydown={(e) => { if (e.key === 'Escape') menuFor = null; }} />

<section class="page">
  <div class="head">
    <div><h1 class="display">Members</h1><p class="member-count">{all.filter((m) => !m.archived).length} members</p></div>
    <button class="primary" onclick={addMember}>Add member</button>
  </div>

  <input class="search" type="search" placeholder="Search {all.length} members" bind:value={query} aria-label="Search members" />

  <div class="toolbar">
    <div class="seg" role="group" aria-label="Layout">
      <button class:on={!view.flat} aria-pressed={!view.flat} onclick={() => setView({ flat: false })}><Icon name="folder" size={16} /> Folders</button>
      <button class:on={view.flat} aria-pressed={view.flat} onclick={() => setView({ flat: true })}><Icon name="menu" size={16} /> List</button>
    </div>
    {#if foldered}
      <button class="tool" class:on={selecting} aria-pressed={selecting} onclick={() => (selecting ? endSelecting() : (selecting = true))}><Icon name="select" size={16} /> {selecting ? 'Done' : 'Select'}</button>
    {/if}
    <button class="tool" class:on={customizing} aria-pressed={customizing} aria-expanded={customizing} onclick={() => (customizing = !customizing)}><Icon name="sliders" size={16} /> Customize</button>
  </div>

  <div class="chips" role="tablist" aria-label="Filter">
    <button class:on={filter === 'all'} onclick={() => (filter = 'all')}>All</button>
    {#each gs as g (g.id)}
      <button class:on={filter === g.id} onclick={() => (filter = g.id)} title={groupPath(g, gs)}>{g.name}</button>
    {/each}
    <button class:on={filter === 'archived'} onclick={() => (filter = 'archived')}>Archived</button>
    <button class="ghost" onclick={() => (showGroups = !showGroups)}>{showGroups ? 'Done' : 'Groups…'}</button>
    <button class="ghost" onclick={() => (importing = !importing)}>Import from PluralKit…</button>
  </div>

  {#if customizing}
    <div class="panel customize">
      <h2>On each member</h2>
      <div class="opt">
        <span>Role pills</span>
        <div class="seg small" role="group" aria-label="Role pills">
          {#each PILL_MODES as p (p.id)}
            <button class:on={view.pills === p.id} aria-pressed={view.pills === p.id} onclick={() => setView({ pills: p.id })}>{p.label}</button>
          {/each}
        </div>
      </div>
      {#if view.pills !== 'off'}
        <div class="opt">
          <span>Roles come from</span>
          <span class="inline">
            {#if !defs.some((d) => d.type === 'multi_select' || /^roles?$/i.test(d.name.trim()))}
              <button class="ghost" onclick={setUpRoles}>Set up a Roles field</button>
            {/if}
            {#if roleFields(defs).length}
              <select value={rolesFrom?.id ?? ''} onchange={(e) => setView({ role_field: (e.currentTarget as HTMLSelectElement).value || undefined })} aria-label="Field for roles">
                {#if !rolesFrom}<option value="">Choose a field</option>{/if}
                {#each roleFields(defs) as d (d.id)}<option value={d.id}>{d.name}</option>{/each}
              </select>
            {/if}
          </span>
        </div>
        <p class="hint">Give people roles in their profile’s custom fields. Text fields can hold several, split by commas.</p>
      {/if}
      <label class="opt"><span>A line of their bio</span><input type="checkbox" checked={view.bio} onchange={(e) => setView({ bio: (e.currentTarget as HTMLInputElement).checked })} /></label>
      <label class="opt"><span>Their banner behind the row</span><input type="checkbox" checked={view.banners} onchange={(e) => setView({ banners: (e.currentTarget as HTMLInputElement).checked })} /></label>

      <h2>Folders</h2>
      <ul class="folders">
        {#each folders as f, i (f.id)}
          <li>
            <input value={f.name} aria-label="Folder name" onchange={(e) => setFolders(renameFolder(folders, f.id, (e.currentTarget as HTMLInputElement).value))} />
            <span class="count">{all.filter((m) => !m.archived && prefs.folderOf.get(m.id) === f.id).length}</span>
            <button class="icon-btn" disabled={i === 0} aria-label="Move {f.name} up" onclick={() => setFolders(moveFolder(folders, f.id, -1))}>↑</button>
            <button class="icon-btn" disabled={i === folders.length - 1} aria-label="Move {f.name} down" onclick={() => setFolders(moveFolder(folders, f.id, 1))}>↓</button>
            <button class="icon-btn" aria-label="Delete {f.name}" onclick={() => deleteFolder(f.id, f.name)}><Icon name="delete" size={16} /></button>
          </li>
        {/each}
      </ul>
      <form class="inline" onsubmit={createFolder}>
        <input bind:value={newFolder} placeholder="New folder, e.g. Littles" aria-label="New folder name" />
        <button class="ghost">Add folder</button>
      </form>
    </div>
  {/if}

  {#if importing}
    <PkImport onclose={() => (importing = false)} />
  {/if}

  {#if showGroups}
    <div class="panel groups">
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

  {#if shownCount === 0 && !(foldered && filter === 'all' && folders.length)}
    <p class="muted">
      {#if query}No one matches “{query}”. <button class="ghost" onclick={addMember}>Add “{query}”</button>{:else}Nobody here yet.{/if}
    </p>
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
      {/if}
      {#if open}
        {#if s.folder && !s.members.length}
          <p class="empty">Empty. Drag people here, or use a row’s ⋯ menu.</p>
        {/if}
        <div class="grid" role="list">
          {#each s.members as m (m.id)}
            {@render row(m)}
          {/each}
        </div>
      {/if}
    </div>
  {/each}

  {#if selecting}
    <div class="selbar" role="toolbar" aria-label="Move selected members">
      <span>{picked.size} selected</span>
      <select disabled={!picked.size} aria-label="Move selected to folder" onchange={(e) => {
        const v = (e.currentTarget as HTMLSelectElement).value;
        (e.currentTarget as HTMLSelectElement).value = '';
        if (v === '__new') { customizing = true; return; }
        if (v) moveTo(v === '__none' ? null : v);
      }}>
        <option value="">Move to…</option>
        {#each folders as f (f.id)}<option value={f.id}>{f.name}</option>{/each}
        <option value="__none">Not in a folder</option>
        <option value="__new">New folder…</option>
      </select>
      <button class="ghost" onclick={endSelecting}>Cancel</button>
    </div>
  {/if}
</section>

{#snippet row(m: MemberRow)}
  {@const c = core.adaptColor(m.color, dark)}
  {@const pills = pillsFor(rolesOfMember(m), view.pills)}
  {@const bio = view.bio ? bioLine(m.description) : undefined}
  {@const banner = view.banners && m.banner_blob}
  <div class="member" role="listitem" class:has-banner={!!banner} class:picked={picked.has(m.id)} style="--ring: {c.ring}; --avatar-ink: {c.avatar_ink}; --tint: {c.tint}"
    draggable={foldered} ondragstart={(e) => dragStart(e, m.id)} ondragend={() => (dropOn = undefined)}>
    {#if banner}<MemberBanner hash={banner} />{/if}
    {#if selecting}
      <label class="hit">
        <input type="checkbox" checked={picked.has(m.id)} onchange={() => pick(m.id)} aria-label="Select {m.display_name ?? m.name}" />
        {@render body()}
      </label>
    {:else}
      <a class="hit" href="#/members/{m.id}" draggable="false">{@render body()}</a>
    {/if}
    <span class="end">
      {#if here.has(m.id)}<span class="front-badge">Here</span>{/if}
      {#if foldered && !selecting}
        <span class="menu-wrap">
          <button class="icon-btn more" aria-label="Move {m.display_name ?? m.name} to a folder" aria-haspopup="menu" aria-expanded={menuFor === m.id}
            onclick={(e) => { e.stopPropagation(); menuFor = menuFor === m.id ? null : m.id; }}><Icon name="more" size={18} /></button>
          {#if menuFor === m.id}
            <div class="menu" role="menu" tabindex="-1" onclick={(e) => e.stopPropagation()} onkeydown={() => {}}>
              <p class="menu-title">Move to</p>
              {#each folders as f (f.id)}
                <button role="menuitemradio" aria-checked={prefs.folderOf.get(m.id) === f.id} onclick={() => moveTo(f.id)}><Icon name="folder" size={16} /> {f.name}</button>
              {/each}
              <button role="menuitemradio" aria-checked={!prefs.folderOf.has(m.id)} onclick={() => moveTo(null)}>Not in a folder</button>
              <form class="inline" onsubmit={(e) => { e.preventDefault(); if (!newFolder.trim()) return; const id = sync.newId(); setFolders(addFolder(folders, id, newFolder)); file([m.id], id); newFolder = ''; menuFor = null; }}>
                <input bind:value={newFolder} placeholder="New folder" aria-label="New folder name" />
                <button class="ghost" aria-label="Create folder and move here"><Icon name="plus" size={16} /></button>
              </form>
            </div>
          {/if}
        </span>
      {/if}
      {#if !selecting}<Icon name="chevron" size={16} />{/if}
    </span>
  </div>
  {#snippet body()}
    <span class="avatar" aria-hidden="true"><AvatarImage hash={m.avatar_blob} glyph={m.sigils[0] ?? m.name[0]} name={m.name} /></span>
    <span class="member-info">
      <span class="name-line"><span class="name">{m.display_name ?? m.name}</span>{#if m.pronouns}<span class="pronouns">{m.pronouns}</span>{/if}</span>
      {#if pills.length}
        <span class="pills">{#each pills as r (r)}<span class="pill" style="--pill: {roleColor(r)}">{r}</span>{/each}</span>
      {/if}
      {#if bio}<span class="bio">{bio}</span>{/if}
    </span>
  {/snippet}
{/snippet}

<style>
  .page { display: grid; gap: var(--s-4); }
  .head { display: flex; justify-content: space-between; align-items: center; }
  h1 { font-size: var(--fs-2xl); }
  h2 { font-size: var(--fs-sm); color: var(--ink-3); text-transform: uppercase; letter-spacing: .06em; margin: var(--s-2) 0 0; }
  .search, .panel input, .panel select, .selbar select {
    font: inherit;
    color: var(--ink);
    background: var(--surface);
    border: 1px solid var(--line);
    border-radius: var(--r-md);
    padding: var(--s-3) var(--s-4);
  }
  .panel input, .panel select, .selbar select { border-radius: var(--r-sm); padding: var(--s-2) var(--s-3); background: var(--bg); }
  .toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: var(--s-2); }
  .seg { display: inline-flex; padding: 3px; gap: 2px; background: var(--surface); border-radius: var(--r-full); }
  .seg button { display: inline-flex; align-items: center; gap: 6px; border: 0; background: none; color: var(--ink-2); font: inherit; font-size: var(--fs-sm); padding: var(--s-1) var(--s-3); border-radius: var(--r-full); cursor: pointer; }
  .seg button.on { background: var(--accent-soft); color: var(--ink); }
  .seg.small { background: var(--bg); }
  .tool { display: inline-flex; align-items: center; gap: 6px; font: inherit; font-size: var(--fs-sm); background: none; border: 1px solid var(--line); color: var(--ink-2); border-radius: var(--r-full); padding: var(--s-1) var(--s-3); cursor: pointer; }
  .tool.on { border-color: var(--accent); color: var(--ink); background: var(--accent-soft); }
  .chips { display: flex; flex-wrap: wrap; gap: var(--s-2); }
  .chips button { font-size: var(--fs-sm); background: var(--surface); border: 1px solid var(--line); border-radius: var(--r-full); padding: var(--s-1) var(--s-3); cursor: pointer; color: var(--ink-2); }
  .chips button.on { background: var(--accent-soft); border-color: var(--accent); color: var(--ink); }
  .chips .ghost { border-color: transparent; background: none; }
  .panel { background: var(--surface); border: 1px solid var(--line); border-radius: var(--r-md); padding: var(--s-4); display: grid; gap: var(--s-3); }
  .panel ul { list-style: none; margin: 0; padding: 0; display: grid; gap: var(--s-2); }
  .panel li { display: flex; gap: var(--s-2); align-items: center; }
  .opt { display: flex; align-items: center; justify-content: space-between; gap: var(--s-3); flex-wrap: wrap; }
  .opt input[type='checkbox'] { width: 18px; height: 18px; accent-color: var(--accent); }
  .hint { margin: 0; color: var(--ink-3); font-size: var(--fs-sm); }
  .folders input { flex: 1; min-width: 6em; }
  .inline { display: flex; gap: var(--s-2); align-items: center; }
  .inline input { flex: 1; min-width: 6em; }
  .kind { font-size: var(--fs-xs); color: var(--ink-3); min-width: 6em; }
  .count { color: var(--ink-3); font-size: var(--fs-sm); margin-left: auto; font-variant-numeric: tabular-nums; }
  .groups form { display: flex; flex-wrap: wrap; gap: var(--s-2); }
  .groups form input { flex: 1; min-width: 8em; }
  .primary { background: var(--accent); color: var(--on-accent); border: 0; border-radius: var(--r-full); padding: var(--s-2) var(--s-4); cursor: pointer; font-weight: 600; }
  .ghost { background: none; border: 0; color: var(--accent); cursor: pointer; font: inherit; }
  .icon-btn { display: inline-grid; place-items: center; min-width: 32px; height: 32px; border: 0; border-radius: var(--r-full); background: none; color: var(--ink-2); cursor: pointer; font: inherit; }
  .icon-btn:hover:not(:disabled) { background: var(--surface-2); }
  .icon-btn:disabled { opacity: .35; cursor: default; }
  .muted { color: var(--ink-3); }
  .member-count { margin: 4px 0 0; color: var(--ink-3); font-size: var(--fs-sm); }

  .section { display: grid; gap: var(--s-2); border-radius: 14px; transition: background var(--t-fast) var(--ease); }
  .section.drop { background: var(--accent-soft); outline: 2px dashed var(--accent); outline-offset: 4px; }
  .folder-head { display: flex; align-items: center; gap: 8px; border: 0; background: none; color: var(--ink-2); font: inherit; font-weight: 600; padding: var(--s-2) 2px; cursor: pointer; text-align: left; }
  .folder-head:disabled { cursor: default; color: var(--ink-3); }
  .folder-head .count { margin-left: 0; font-weight: 400; }
  .folder-name { overflow-wrap: anywhere; }
  .caret { display: inline-flex; transition: transform var(--t-fast) var(--ease); }
  .caret.open { transform: rotate(90deg); }
  .empty { margin: 0; padding: var(--s-3) var(--s-4); color: var(--ink-3); font-size: var(--fs-sm); border: 1px dashed var(--line); border-radius: 12px; }

  .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 320px), 1fr)); gap: 10px; }
  .member { position: relative; display: flex; align-items: center; min-width: 0; min-height: 84px; background: var(--surface); border-radius: 12px; isolation: isolate; transition: background var(--t-fast) var(--ease); }
  .member :global(.banner) { border-radius: 0 12px 12px 0; z-index: -1; }
  .member:hover { background: var(--surface-2); }
  .member:has(.menu) { z-index: 20; }
  .member.picked { outline: 2px solid var(--accent); }
  .member[draggable='true'] { cursor: grab; }
  .hit { display: flex; align-items: center; gap: 12px; flex: 1; min-width: 0; align-self: stretch; padding: 12px 4px 12px 16px; text-decoration: none; color: var(--ink); cursor: pointer; }
  .hit input { width: 18px; height: 18px; accent-color: var(--accent); flex: none; }
  .avatar { width: 44px; height: 44px; display: grid; place-items: center; flex: none; font-weight: 700; font-size: 20px; border-radius: 50%; overflow: hidden; background: var(--ring); color: var(--avatar-ink); border: 2px solid var(--ring); }
  .member-info { display: grid; gap: 4px; flex: 1; min-width: 0; }
  .name-line { display: flex; align-items: baseline; gap: 8px; min-width: 0; }
  .name { font-size: var(--fs-md); font-weight: 700; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .pronouns { font-size: var(--fs-xs); color: var(--ink-3); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .pills { display: flex; flex-wrap: wrap; gap: 4px; }
  .pill { font-size: var(--fs-xs); font-weight: 600; line-height: 1.5; padding: 0 8px; border-radius: var(--r-full); color: var(--ink); background: color-mix(in srgb, var(--pill) 28%, var(--surface)); border: 1px solid color-mix(in srgb, var(--pill) 55%, transparent); white-space: nowrap; }
  .bio { font-size: var(--fs-sm); color: var(--ink-2); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .has-banner .name, .has-banner .bio, .has-banner .pronouns { text-shadow: 0 0 6px var(--surface), 0 0 2px var(--surface); }
  .end { display: flex; align-items: center; gap: 2px; padding-right: 12px; color: var(--ink-3); }
  .has-banner .end > :global(*) { filter: drop-shadow(0 0 3px var(--surface)); }
  .front-badge { font-size: var(--fs-xs); color: var(--accent); background: color-mix(in srgb, var(--surface) 80%, transparent); padding: 2px 8px; border-radius: var(--r-full); margin-right: 4px; }
  .menu-wrap { position: relative; }
  .menu { position: absolute; right: 0; top: calc(100% + 4px); z-index: 10; width: 240px; display: grid; gap: 2px; padding: 6px; background: var(--surface); border: 1px solid var(--line); border-radius: 14px; box-shadow: var(--shadow-pop); }
  .menu-title { margin: 4px 8px; font-size: var(--fs-xs); color: var(--ink-3); }
  .menu > button { display: flex; align-items: center; gap: 8px; text-align: left; border: 0; background: none; color: var(--ink); font: inherit; padding: 8px; border-radius: 8px; cursor: pointer; }
  .menu > button:hover { background: var(--surface-2); }
  .menu > button[aria-checked='true'] { color: var(--accent); font-weight: 600; }
  .menu input { font: inherit; color: var(--ink); background: var(--bg); border: 1px solid var(--line); border-radius: var(--r-sm); padding: 6px 8px; }
  .selbar { position: sticky; bottom: var(--s-3); display: flex; align-items: center; gap: var(--s-3); padding: var(--s-2) var(--s-3) var(--s-2) var(--s-4); background: var(--surface); border: 1px solid var(--line); border-radius: var(--r-full); box-shadow: var(--shadow-pop); justify-self: center; z-index: 5; }
</style>
