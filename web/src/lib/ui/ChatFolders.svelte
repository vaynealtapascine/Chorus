<script lang="ts">
  // Chat folders (D-113, Figma F5.25; Android's ChatFoldersScreen): show, hide and order the
  // folders, make folders of our own from chosen chats and whole kinds of chat, and the list's
  // display choices. Everything saves at once as the `chat_folders` account pref.
  import { untrack } from 'svelte';
  import { sync } from '../sync/client';
  import { BUILT_IN, KINDS, SUGGESTIONS, fromSuggestion, holds, label, ordered, PREF, setChat, setKind, toJson, without, withCustom,
    type Custom, type FolderChat, type Kind, type Settings } from '../chat-folders';
  import { fuzzy } from '../data';
  import Icon from './Icon.svelte';
  import Sheet from './Sheet.svelte';
  import ConfirmSheet from './kit/ConfirmSheet.svelte';
  import DragList from './kit/DragList.svelte';
  import EmptyState from './kit/EmptyState.svelte';
  import FormCard from './kit/FormCard.svelte';
  import SectionLabel from './kit/SectionLabel.svelte';
  import Switch from './kit/Switch.svelte';

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
  const leave = () => (startWith ? onclose() : close());
  function finish() {
    if (!draft || !draft.name.trim()) return;
    save(withCustom(settings, { ...$state.snapshot(draft) as Custom, name: draft.name.trim() }));
    leave();
  }
  function remove() {
    if (!draft) return;
    save(without(settings, draft.id));
    leave();
  }
  // a singlet has no members to talk among: their own channels stay under All chats
  const folders = $derived(ordered(settings).filter((id) => !(person && id === 'internal')));
  const rows = $derived(folders.map((id) => ({ id })));
  const suggested = $derived(SUGGESTIONS.filter((s) => !settings.custom.some((f) => f.preset === s.preset)));
  const ours = $derived(chats.filter((c) => c.own).map((c) => ({ id: c.id, name: c.title })));
  function summary(id: string): string {
    if ((BUILT_IN as readonly string[]).includes(id)) return id === 'all' ? 'Every chat' : 'Built in';
    const f = settings.custom.find((c) => c.id === id)!;
    const n = chats.filter((c) => holds(f, c.id, c.kind)).length;
    return [`${n} ${n === 1 ? 'chat' : 'chats'}`, f.unreadOnly ? 'unread only' : '', f.hideMuted ? 'not muted' : ''].filter(Boolean).join(' · ');
  }
  // the dragged order covers the listed folders; the ones not listed keep their places
  function reorder(ids: string[]) {
    let k = 0;
    save({ ...settings, order: ordered(settings).map((id) => (folders.includes(id) ? ids[k++] : id)) });
  }
  const setHidden = (id: string, on: boolean) => save({ ...settings, hidden: on ? settings.hidden.filter((h) => h !== id) : [...settings.hidden, id] });
  const isBuiltIn = (id: string) => (BUILT_IN as readonly string[]).includes(id);
</script>

{#if draft}
  <Sheet title={isNew ? 'New folder' : 'Edit folder'} onclose={leave} footer={bar}>
    <FormCard>
      <label class="name-row"><span>Name</span><!-- svelte-ignore a11y_autofocus --><input bind:value={draft.name} maxlength="40" placeholder="Folder name" aria-label="Folder name" autofocus /></label>
    </FormCard>
    <SectionLabel label="Every chat of a kind" />
    <FormCard>
      {#each KINDS.filter((k) => !(person && k === 'internal')) as k (k)}
        <Switch label={KIND_LABEL[k]} checked={draft.kinds.includes(k)} onchange={(on) => (draft = setKind(draft!, k, on, kindOf))} />
      {/each}
    </FormCard>
    <SectionLabel label="Only show" />
    <FormCard>
      <Switch label="Unread chats" bind:checked={draft.unreadOnly} />
      <Switch label="Chats that aren’t muted" bind:checked={draft.hideMuted} />
    </FormCard>
    <SectionLabel label="Chats" />
    {#if chats.length > 8}<label class="find"><Icon name="search" size={18} /><input type="search" bind:value={filter} placeholder="Find a chat" aria-label="Find a chat" /></label>{/if}
    <FormCard>
      {#each listed as c (c.id)}
        {@const on = holds(draft, c.id, c.kind)}
        <button class="pick" role="checkbox" aria-checked={on} onclick={() => (draft = setChat(draft!, c.id, c.kind, !on))}>
          <span>{c.title}</span>{#if on}<Icon name="check" size={18} />{/if}</button>
      {:else}<EmptyState icon="chat" label="No chats" />{/each}
    </FormCard>
    {#if !isNew}<button class="danger-link" onclick={() => (confirmDelete = true)}>Delete folder</button>{/if}
  </Sheet>
  {#if confirmDelete}
    <ConfirmSheet title="Delete “{draft.name || 'Folder'}”?" confirm="Delete" danger layer={1} onconfirm={remove} onclose={() => (confirmDelete = false)} />
  {/if}
{:else}
  <Sheet title="Chat folders" {onclose}>
    <DragList items={rows} onreorder={reorder} label="Reorder folders">
      {#snippet row({ id })}
        {@const shown = !settings.hidden.includes(id)}
        <Icon name={ICONS[id] ?? 'folder'} size={20} />
        {#if isBuiltIn(id)}
          <span class="name" class:off={!shown}><strong>{label(settings, id)}</strong><small>{summary(id)}</small></span>
        {:else}
          <button class="name open" class:off={!shown} aria-label="Edit {label(settings, id)}" onclick={() => (editing = id)}><span class="name"><strong>{label(settings, id)}</strong><small>{summary(id)}</small></span><Icon name="chevron-right" size={18} /></button>
        {/if}
        <Switch checked={shown} onchange={(on) => setHidden(id, on)} aria="Show {label(settings, id)}" />
      {/snippet}
    </DragList>
    <button class="add" onclick={() => (editing = 'new')}><Icon name="plus" size={18} />Create a folder</button>
    {#if suggested.length}
      <SectionLabel label="Suggested" />
      <FormCard>
        {#each suggested as s (s.preset)}
          <div class="suggest"><Icon name="folder" size={20} /><span class="name"><strong>{s.title}</strong><small>{s.subtitle}</small></span>
            <button class="pill" onclick={() => save(withCustom(settings, fromSuggestion(sync.newId(), s.preset, s.title, ours)))}>Add</button></div>
        {/each}
      </FormCard>
    {/if}
    <SectionLabel label="Chat list display" />
    <FormCard>
      {#if !person}<Switch label="Show who’s here above the chats" checked={settings.showHere} onchange={(on) => save({ ...settings, showHere: on })} />{/if}
      <Switch label="Compact rows" checked={settings.compact} onchange={(on) => save({ ...settings, compact: on })} />
    </FormCard>
  </Sheet>
{/if}

{#snippet bar()}
  <button class="neutral" onclick={leave}>Cancel</button>
  <button class="primary" disabled={!draft?.name.trim()} onclick={finish}>Save</button>
{/snippet}

<style>
  .name { flex: 1; display: grid; min-width: 0; text-align: left; }
  .name strong { color: var(--ink); font-weight: 600; font-size: 15px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .name small { color: var(--ink-3); font-size: 13px; }
  .name.off { opacity: .55; }
  .open { display: flex; align-items: center; gap: 8px; border: 0; background: none; padding: 0; font: inherit; color: var(--ink-3); cursor: pointer; }
  .name-row { display: flex; align-items: center; gap: 12px; min-height: 46px; padding: 0 14px; font-size: 15px; }
  .name-row input { flex: 1; min-width: 0; border: 0; outline: 0; background: none; color: var(--ink); font: inherit; text-align: right; }
  .find { display: flex; align-items: center; gap: 8px; min-height: 40px; padding: 0 12px; border-radius: 20px; background: var(--surface-2); color: var(--ink-3); }
  .find input { flex: 1; min-width: 0; border: 0; outline: 0; background: none; color: var(--ink); font: inherit; font-size: 15px; }
  .pick { display: flex; align-items: center; gap: 8px; width: 100%; min-height: 46px; padding: 8px 14px; border: 0; background: none; color: var(--ink); font: inherit; font-size: 15px; text-align: left; cursor: pointer; }
  .pick span { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .pick :global(.icon) { color: var(--accent); }
  .add { display: flex; align-items: center; gap: 8px; border: 0; background: none; color: var(--accent); font: inherit; font-weight: 600; cursor: pointer; padding: 12px 4px; }
  .suggest { display: flex; align-items: center; gap: 12px; min-height: 56px; padding: 6px 14px; color: var(--ink-2); }
  .pill { min-height: 34px; padding: 0 16px; border: 0; border-radius: 17px; background: var(--accent); color: var(--on-accent); font: inherit; font-weight: 600; cursor: pointer; }
  .danger-link { justify-self: start; border: 0; background: none; color: var(--danger); font: inherit; cursor: pointer; padding: 12px 4px; }
  .neutral, .primary { min-height: 44px; padding: 10px 18px; border: 0; border-radius: 14px; font: inherit; font-size: 15px; font-weight: 600; cursor: pointer; }
  .neutral { background: var(--surface-2); color: var(--ink); }
  .primary { flex: 1; background: var(--accent); color: var(--on-accent); }
  .primary:disabled { background: var(--surface-2); color: var(--ink-3); cursor: default; }
</style>
