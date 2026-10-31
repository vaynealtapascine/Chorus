<script lang="ts">
  // The desktop chat's left rail (Figma F5.21): chat folders with unread-chat counts above, the
  // other destinations below; the menu button opens the full navigation as a drawer.
  import { FOLDERS, type Folder } from '../chat-list';
  import Icon from './Icon.svelte';

  let { folder, counts, person, onfolder, onmenu }: {
    folder: Folder; counts: Record<Folder, number>; person: boolean; onfolder: (f: Folder) => void; onmenu: () => void;
  } = $props();
  // a singlet has no members to talk among: their own channels stay under All chats
  const folders = $derived(person ? FOLDERS.filter((f) => f.id !== 'internal') : FOLDERS);
  const destinations = $derived([
    ...(person ? [] : [{ href: '#/members', label: 'Members', icon: 'users' }, { href: '#/', label: 'Fronting', icon: 'target' }]),
    ...(person ? [{ href: '#/journal', label: 'Journal', icon: 'journal' }] : []),
    { href: '#/people', label: 'Friends', icon: 'swap' },
    { href: '#/more', label: 'More', icon: 'more' },
  ]);
</script>

<nav class="rail" aria-label="Chat folders">
  <button class="menu" onclick={onmenu} aria-label="Open navigation"><Icon name="menu" size={22} /></button>
  {#each folders as f (f.id)}
    <button class="item" class:on={folder === f.id} aria-pressed={folder === f.id} onclick={() => onfolder(f.id)}
      aria-label={counts[f.id] ? `${f.label}, ${counts[f.id]} unread` : f.label}>
      <Icon name={f.icon} size={22} />
      <span class="label">{f.label}</span>
      {#if counts[f.id]}<span class="badge" class:sun={f.id === 'all'}>{counts[f.id] > 99 ? '99+' : counts[f.id]}</span>{/if}
    </button>
  {/each}
  <span class="spacer"></span>
  {#each destinations as d (d.href)}
    <a class="item" href={d.href}><Icon name={d.icon} size={22} /><span class="label">{d.label}</span></a>
  {/each}
</nav>

<style>
  .rail { display: flex; flex-direction: column; align-items: center; gap: 4px; padding: 10px 6px 12px; background: var(--bg); border-right: 1px solid var(--line); min-height: 0; overflow-y: auto; }
  .menu { width: 40px; height: 40px; display: grid; place-items: center; border: 0; border-radius: 50%; background: none; color: var(--ink-3); cursor: pointer; }
  .menu:hover { background: var(--surface-2); color: var(--ink); }
  .item { position: relative; width: 64px; display: flex; flex-direction: column; align-items: center; gap: 4px; padding: 8px 4px;
    border: 0; border-radius: 12px; background: none; color: var(--ink-3); text-decoration: none; cursor: pointer; font: inherit;
    transition: background var(--t-fast) var(--ease), color var(--t-fast) var(--ease); }
  .item:hover { background: var(--surface); color: var(--ink-2); }
  .item.on { background: var(--accent-soft); color: var(--accent); }
  .label { font-size: 11px; line-height: 14px; font-weight: 500; white-space: nowrap; }
  .item.on .label { font-weight: 600; }
  .badge { position: absolute; left: 38px; top: 4px; min-width: 16px; padding: 1px 5px; border-radius: 9px; background: var(--line);
    color: var(--ink); font-size: 10px; line-height: 13px; font-weight: 700; text-align: center; }
  .badge.sun { background: var(--sun); color: var(--on-sun); }
  .spacer { flex: 1; min-height: 12px; }
</style>
