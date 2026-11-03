<script lang="ts">
  // Chat folders (D-113, Figma F5.25; Android's ChatFoldersScreen): show, hide and order the
  // folders, make folders of our own from chosen chats and whole kinds of chat, and the list's
  // display choices. Everything saves at once as the `chat_folders` account pref.
  import { untrack } from 'svelte';
  import { sync } from '../sync/client';
  import { BUILT_IN, KINDS, SUGGESTIONS, fromSuggestion, holds, label, move, ordered, PREF, setChat, setKind, toJson, without, withCustom,
    type Custom, type FolderChat, type Kind, type Settings } from '../chat-folders';
  import { fuzzy } from '../data';
  import Icon from './Icon.svelte';

  let { settings, chats, person, startWith = null, onclose }: {
    settings: Settings; chats: FolderChat[]; person: boolean; startWith?: string | null; onclose: () => void;
  } = $props();

  const KIND_LABEL: Record<Kind, string> = { internal: 'Internal chats', shared: 'Shared chats', direct: 'Direct chats' };
  const ICONS: Record<string, string> = { all: 'chat', internal: 'lock', shared: 'globe', direct: 'users' };

  function save(next: Settings) {
    sync.create('pref.set', sync.accountScope, null, { device: '', key: PREF, value: toJson(next) });
  }

  // "new", "new:<chat>" or a folder of our own; null: the list of folders
  // the sheet opens fresh each time, so where it starts is read once
  let editing = $state<string | null>(untrack(() => startWith));
  let draft = $state<Custom | null>(null);
  let filter = $state('');
  let confirmDelete = $state(false);
  $effect(() => {
    if (editing === null) { draft = null; return; }
    if (draft) return;
    const existing = settings.custom.find((f) => f.id === editing);
    const chat = editing.startsWith('new:') ? editing.slice(4) : null;
    draft = existing ? structuredClone($state.snapshot(existing)) as Custom
      : { id: sync.newId(), name: '', chats: chat ? [chat] : [], kinds: [], except: [], unreadOnly: false, hideMuted: false };
  });
  const isNew = $derived(!!draft && !settings.custom.some((f) => f.id === draft!.id));
  const kindOf = (id: string) => chats.find((c) => c.id === id)?.kind;
  const listed = $derived(chats.filter((c) => !filter.trim() || fuzzy(filter, c.title) !== null));
  function close() { editing = null; draft = null; filter = ''; confirmDelete = false; }
  function finish() {
    if (!draft || !draft.name.trim()) return;
    save(withCustom(settings, { ...$state.snapshot(draft) as Custom, name: draft.name.trim() }));
    if (startWith) onclose(); else close();
  }
  function remove() {
    if (!draft) return;
    save(without(settings, draft.id));
    if (startWith) onclose(); else close();
  }
  // a singlet has no members to talk among: their own channels stay under All chats
  const folders = $derived(ordered(settings).filter((id) => !(person && id === 'internal')));
  const suggested = $derived(SUGGESTIONS.filter((s) => !settings.custom.some((f) => f.preset === s.preset)));
  const ours = $derived(chats.filter((c) => c.own).map((c) => ({ id: c.id, name: c.title })));
  function summary(id: string): string {
    if ((BUILT_IN as readonly string[]).includes(id)) return id === 'all' ? 'Every chat' : 'Built in';
    const f = settings.custom.find((c) => c.id === id)!;
    const n = chats.filter((c) => holds(f, c.id, c.kind)).length;
    return [`${n} ${n === 1 ? 'chat' : 'chats'}`, f.unreadOnly ? 'unread only' : '', f.hideMuted ? 'not muted' : ''].filter(Boolean).join(' · ');
  }
</script>

<svelte:window onkeydown={(e) => { if (e.key === 'Escape') { if (editing && !startWith) close(); else onclose(); } }} />
<div class="scrim" onclick={onclose} role="presentation"></div>
<div class="sheet" role="dialog" aria-modal="true" aria-label={draft ? (isNew ? 'New folder' : 'Edit folder') : 'Chat folders'}>
  {#if draft}
    <div class="top">
      <button class="ghost" onclick={() => (startWith ? onclose() : close())}>Cancel</button>
      <h2>{isNew ? 'New folder' : 'Edit folder'}</h2>
      <button class="ghost strong" disabled={!draft.name.trim()} onclick={finish}>Save</button>
    </div>
    <label class="field">Name<input bind:value={draft.name} maxlength="40" placeholder="Folder name" /></label>
    <fieldset class="field">
      <legend>Every chat of a kind</legend>
      {#each KINDS.filter((k) => !(person && k === 'internal')) as k (k)}
        <label class="check"><input type="checkbox" checked={draft.kinds.includes(k)}
          onchange={(e) => (draft = setKind(draft!, k, e.currentTarget.checked, kindOf))} />{KIND_LABEL[k]}</label>
      {/each}
    </fieldset>
    <fieldset class="field">
      <legend>Only show</legend>
      <label class="check"><input type="checkbox" bind:checked={draft.unreadOnly} />Unread chats</label>
      <label class="check"><input type="checkbox" bind:checked={draft.hideMuted} />Chats that aren’t muted</label>
    </fieldset>
    <fieldset class="field">
      <legend>Chats</legend>
      {#if chats.length > 8}<input class="search" type="search" bind:value={filter} placeholder="Find a chat" aria-label="Find a chat" />{/if}
      <div class="chats">
        {#each listed as c (c.id)}
          <label class="check"><input type="checkbox" checked={holds(draft, c.id, c.kind)}
            onchange={(e) => (draft = setChat(draft!, c.id, c.kind, e.currentTarget.checked))} />{c.title}</label>
        {:else}<p class="hint">No chats match.</p>{/each}
      </div>
    </fieldset>
    {#if !isNew}
      {#if confirmDelete}
        <div class="confirm">Delete “{draft.name || 'Folder'}”? Its chats stay where they are.
          <button class="danger" onclick={remove}>Delete</button><button class="ghost" onclick={() => (confirmDelete = false)}>Keep</button></div>
      {:else}<button class="danger-link" onclick={() => (confirmDelete = true)}>Delete folder</button>{/if}
    {/if}
  {:else}
    <div class="top">
      <h2>Chat folders</h2>
      <button class="ghost" onclick={onclose}>Done</button>
    </div>
    <p class="hint">Sort chats and hide what you don’t need. Your devices share these.</p>
    <ul class="folders" aria-label="My folders">
      {#each folders as id, i (id)}
        {@const shown = !settings.hidden.includes(id)}
        <li class:off={!shown}>
          <Icon name={ICONS[id] ?? 'folder'} size={20} />
          <span class="name"><strong>{label(settings, id)}</strong><small>{summary(id)}</small></span>
          {#if !(BUILT_IN as readonly string[]).includes(id)}<button class="icon" aria-label={`Edit ${label(settings, id)}`} onclick={() => (editing = id)}><Icon name="edit" size={16} /></button>{/if}
          <button class="icon" aria-label={`Move ${label(settings, id)} up`} disabled={i === 0} onclick={() => save(move(settings, id, ordered(settings).indexOf(folders[i - 1])))}>↑</button>
          <button class="icon" aria-label={`Move ${label(settings, id)} down`} disabled={i === folders.length - 1} onclick={() => save(move(settings, id, ordered(settings).indexOf(folders[i + 1])))}>↓</button>
          <label class="switch"><input type="checkbox" checked={shown} aria-label={`Show ${label(settings, id)}`}
            onchange={(e) => save({ ...settings, hidden: e.currentTarget.checked ? settings.hidden.filter((h) => h !== id) : [...settings.hidden, id] })} /></label>
        </li>
      {/each}
    </ul>
    <button class="add" onclick={() => (editing = 'new')}><Icon name="plus" size={18} />Create a folder</button>
    {#if suggested.length}
      <h3>Suggested</h3>
      <ul class="folders">
        {#each suggested as s (s.preset)}
          <li><Icon name="folder" size={20} /><span class="name"><strong>{s.title}</strong><small>{s.subtitle}</small></span>
            <button class="ghost" onclick={() => save(withCustom(settings, fromSuggestion(sync.newId(), s.preset, s.title, ours)))}>Add</button></li>
        {/each}
      </ul>
    {/if}
    <h3>Chat list display</h3>
    {#if !person}<label class="check"><input type="checkbox" checked={settings.showHere} onchange={(e) => save({ ...settings, showHere: e.currentTarget.checked })} />Show who’s here above the chats</label>{/if}
    <label class="check"><input type="checkbox" checked={settings.compact} onchange={(e) => save({ ...settings, compact: e.currentTarget.checked })} />Compact rows</label>
  {/if}
</div>

<style>
  .scrim { position: fixed; inset: 0; background: rgb(0 0 0 / 0.45); z-index: 30; }
  .sheet { position: fixed; z-index: 31; inset: auto 0 0 0; max-height: 90vh; overflow: auto; margin: 0 auto; max-width: 520px;
    background: var(--surface); color: var(--ink); border-radius: var(--r-lg) var(--r-lg) 0 0; box-shadow: var(--shadow-pop);
    padding: 20px 20px calc(20px + env(safe-area-inset-bottom)); display: grid; gap: 14px; animation: rise var(--t-med, 220ms) var(--ease); }
  @media (min-width: 641px) { .sheet { inset: 8vh 0 auto 0; border-radius: var(--r-lg); } }
  @keyframes rise { from { transform: translateY(16px); opacity: 0; } }
  @media (prefers-reduced-motion: reduce) { .sheet { animation: none; } }
  .top { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
  h2 { margin: 0; font-size: var(--fs-lg); }
  h3 { margin: 6px 0 0; font-size: var(--fs-sm); color: var(--ink-3); font-weight: 600; text-transform: uppercase; letter-spacing: .04em; }
  .hint { margin: 0; color: var(--ink-3); font-size: var(--fs-sm); }
  .ghost { border: 0; background: none; color: var(--accent); font: inherit; cursor: pointer; padding: 4px 0; }
  .ghost.strong { font-weight: 600; }
  .ghost:disabled { color: var(--ink-3); cursor: default; }
  .field { display: grid; gap: 6px; font-size: var(--fs-sm); color: var(--ink-2); border: 0; margin: 0; padding: 0; min-width: 0; }
  .field legend { padding: 0; margin-bottom: 6px; }
  input:not([type]), .search { font: inherit; font-size: 15px; color: var(--ink); background: var(--surface-2);
    border: 1px solid var(--line); border-radius: var(--r-sm); padding: 8px 10px; min-width: 0; }
  .check { display: flex; align-items: center; gap: 10px; color: var(--ink); padding: 6px 2px; font-size: 15px; cursor: pointer; }
  .chats { display: grid; max-height: 280px; overflow: auto; }
  .folders { list-style: none; margin: 0; padding: 0; display: grid; gap: 2px; }
  .folders li { display: flex; align-items: center; gap: 10px; min-height: 52px; padding: 4px 6px; border-radius: var(--r-sm); color: var(--ink-2); }
  .folders li:hover { background: var(--surface-2); }
  .folders li.off .name { opacity: .55; }
  .name { flex: 1; display: grid; min-width: 0; }
  .name strong { color: var(--ink); font-weight: 600; font-size: 15px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .name small { color: var(--ink-3); font-size: var(--fs-sm); }
  .icon { width: 32px; height: 32px; display: grid; place-items: center; border: 0; border-radius: 50%; background: none; color: var(--ink-3); cursor: pointer; font: inherit; }
  .icon:hover:not(:disabled) { background: var(--surface); color: var(--ink); }
  .icon:disabled { opacity: .3; cursor: default; }
  .switch input { width: 18px; height: 18px; accent-color: var(--accent); cursor: pointer; }
  .add { display: flex; align-items: center; gap: 8px; border: 0; background: none; color: var(--accent); font: inherit; font-weight: 600; cursor: pointer; padding: 8px 6px; justify-self: start; }
  .confirm { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; color: var(--ink-2); font-size: var(--fs-sm); }
  .danger { border: 0; border-radius: var(--r-sm); background: var(--danger); color: white; font: inherit; padding: 6px 12px; cursor: pointer; }
  .danger-link { border: 0; background: none; color: var(--danger); font: inherit; cursor: pointer; padding: 4px 0; justify-self: start; }
</style>
