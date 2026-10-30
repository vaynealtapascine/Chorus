<script lang="ts">
  // The desktop chat list (Figma F5.21): search, who is here now, then every conversation across
  // spaces, newest first, with its last message, time and unread count. Muted chats keep a grey badge.
  import { core } from '../core';
  import { channels, latestMessage, members, spaces, unread, fuzzy, type ChannelRow, type SpaceRow } from '../data';
  import { byActivity, chatTime, discColor, folderOf, previewOf, type Folder } from '../chat-list';
  import { bodyPresence, frontLayers } from '../front-view';
  import { expandFront } from '../subsystems';
  import { spaceTitle, type SpaceInfo } from '../spaces';
  import { sync, type Projection } from '../sync/client';
  import AvatarImage from './AvatarImage.svelte';
  import Icon from './Icon.svelte';

  let { projection, dark, currentId, folder, directory, person, onswitch, onnewchannel }: {
    projection: Projection; dark: boolean; currentId?: string; folder: Folder; directory: Map<string, SpaceInfo>;
    person: boolean; onswitch: () => void; onnewchannel?: (name: string) => void;
  } = $props();

  let query = $state('');
  let composing = $state(false);
  let newName = $state('');
  const people = $derived(new Map(members(projection).map((m) => [m.id, m])));
  const nameOf = (id: string) => { const m = people.get(id); return m?.display_name ?? m?.name ?? 'Someone'; };
  const ss = $derived(spaces(projection));

  interface Row {
    channel: ChannelRow; space: SpaceRow | undefined; folder: Exclude<Folder, 'all'>; title: string; icon: string | null;
    at: number; unread: number; muted: boolean; who: string | null; text: string; disc: { glyph: string; color: string; blob?: string };
  }
  const rows: Row[] = $derived.by(() => {
    const all = channels(projection).filter((c) => !c.archived && c.kind !== 'thread');
    const prefs = projection.rows.pref ?? {};
    return byActivity(all.map((c) => {
      const space = ss.find((s) => s.id === c.space_id);
      const f = folderOf(c, space);
      const siblings = all.filter((o) => o.space_id === c.space_id).length;
      const title = c.kind === 'member_dm' ? c.name
        : space?.kind === 'dm' ? spaceTitle(space, directory.get(space.id), sync.accountId)
        : space?.kind === 'shared' ? (siblings > 1 ? `${space.name} · ${c.name}` : space.name)
        : c.name;
      const latest = latestMessage(projection, c.id);
      // a DM between two of our own members already says who: show only the words
      const preview = previewOf(latest, (id) => (people.has(id) ? nameOf(id) : ''), c.kind !== 'member_dm');
      const notify = (prefs[`${sync.accountId}||notify_channel:${c.id}`] ?? prefs[`||notify_channel:${c.id}`])?.fields.value;
      const partner = c.kind === 'member_dm' ? people.get(c.member_ids?.[0] ?? '') : undefined;
      const disc = partner
        ? { glyph: partner.sigils[0] ?? partner.name[0] ?? '?', color: partner.color, blob: partner.avatar_blob ?? undefined }
        : { glyph: (title.match(/\p{L}|\p{N}/u)?.[0] ?? '#').toUpperCase(), color: discColor(c.id) };
      return {
        channel: c, space, folder: f, title, icon: c.kind === 'member_dm' || space?.kind === 'dm' ? null : f === 'internal' ? 'hash' : 'globe',
        at: latest?.occurred_at ?? 0, unread: c.id === currentId ? 0 : unread(projection, c.id, sync.accountId),
        muted: notify === 'none', who: preview.who, text: preview.text, disc,
      };
    }));
  });
  const shown = $derived(rows.filter((r) => (folder === 'all' || r.folder === folder) &&
    (!query.trim() || fuzzy(query, `${r.title} ${r.text}`) !== null)));

  // who is here now: the Body's presence, innermost layer first
  const layers = $derived(frontLayers(projection, sync.accountId));
  // members inside a fronting subsystem come with it (D-092, display only)
  const expanded = $derived(expandFront(projection, sync.accountId, bodyPresence(projection, sync.accountId)));
  const here = $derived(expanded.entries.filter((e) => e.subject_type === 'member' && people.has(e.subject_id))
    .sort((a, b) => layers.findIndex((l) => l.id === a.level) - layers.findIndex((l) => l.id === b.level) || Number(b.is_primary) - Number(a.is_primary)));
  const layerName = (id: string) => layers.find((l) => l.id === id)?.name ?? '';

  function create(e: SubmitEvent) {
    e.preventDefault();
    if (!newName.trim()) return;
    onnewchannel?.(newName.trim());
    newName = ''; composing = false;
  }
</script>

<section class="list" aria-label="Chats">
  <div class="search-row">
    <label class="search"><Icon name="search" size={18} /><input bind:value={query} placeholder="Search" aria-label="Search chats" /></label>
    <button class="compose" class:on={composing} onclick={() => (composing = !composing)} aria-label="New chat" aria-expanded={composing}><Icon name="edit" size={22} /></button>
  </div>
  {#if composing}
    <div class="compose-panel">
      {#if onnewchannel}
        <form onsubmit={create}><input bind:value={newName} placeholder="New channel name" aria-label="New channel name" /><button disabled={!newName.trim()}>Create</button></form>
      {/if}
      <a href="#/people" onclick={() => (composing = false)}>Message a friend…</a>
    </div>
  {/if}
  {#if !person && here.length}
    <div class="here" aria-label="Here now">
      {#each here as e (e.subject_id)}
        {@const m = people.get(e.subject_id)!}
        {@const c = core.adaptColor(m.color, dark)}
        <button class="here-one" onclick={onswitch} title="{nameOf(m.id)} · {layerName(e.level)}{expanded.via.has(m.id) ? ` with ${expanded.via.get(m.id)}` : ''}" aria-label="{nameOf(m.id)}, {layerName(e.level)}. Switch who is here">
          <span class="ring" class:front={e.level === 'front' || e.is_primary} class:cocon={e.level === 'cocon'}>
            <span class="face" style="background: {c.ring}; color: {c.avatar_ink}"><AvatarImage hash={m.avatar_blob} glyph={m.sigils[0] ?? m.name[0]} name={nameOf(m.id)} /></span>
          </span>
          <span class="here-name">{nameOf(m.id)}</span>
        </button>
      {/each}
      <button class="switch" onclick={onswitch} aria-label="Switch who is here" title="Switch · Ctrl/⌘ S"><Icon name="swap" size={18} /></button>
    </div>
  {/if}
  <div class="rows">
    {#each shown as r (r.channel.id)}
      <a class="row" class:on={r.channel.id === currentId} href="#/chat/{r.channel.id}" aria-current={r.channel.id === currentId ? 'page' : undefined}>
        <span class="disc" style="background: {core.adaptColor(r.disc.color, dark).ring}; color: {core.adaptColor(r.disc.color, dark).avatar_ink}">
          {#if r.disc.blob}<AvatarImage hash={r.disc.blob} glyph={r.disc.glyph} name={r.title} />{:else}{r.disc.glyph}{/if}
        </span>
        <span class="body">
          <span class="top">
            {#if r.icon}<Icon name={r.icon} size={16} />{/if}
            <span class="title">{r.title}</span>
            {#if r.muted}<span class="muted-icon" title="Muted"><Icon name="muted" size={14} /></span>{/if}
            {#if r.at}<time>{chatTime(r.at)}</time>{/if}
          </span>
          <span class="bottom">
            {#if r.who}<span class="who">{r.who}:</span>{/if}
            <span class="text">{r.text}</span>
            {#if r.unread}<span class="badge" class:muted={r.muted}>{r.unread > 999 ? '999+' : r.unread}</span>{/if}
          </span>
        </span>
      </a>
    {:else}
      <p class="none">{query.trim() ? 'No chats match.' : 'No chats here yet.'}</p>
    {/each}
  </div>
</section>

<style>
  .list { display: flex; flex-direction: column; min-height: 0; background: var(--bg); border-right: 1px solid var(--line); }
  .search-row { display: flex; align-items: center; gap: 8px; padding: 12px 8px 12px 12px; }
  .search { flex: 1; display: flex; align-items: center; gap: 8px; height: 37px; padding: 0 12px; border-radius: var(--r-full); background: var(--surface-2); color: var(--ink-3); }
  .search:focus-within { box-shadow: 0 0 0 2px var(--accent); }
  .search input { flex: 1; min-width: 0; border: 0; outline: 0; background: none; color: var(--ink); font: inherit; font-size: 15px; }
  .compose { width: 40px; height: 40px; display: grid; place-items: center; border: 0; border-radius: 50%; background: none; color: var(--ink-2); cursor: pointer; }
  .compose:hover, .compose.on { background: var(--surface-2); color: var(--ink); }
  .compose-panel { display: grid; gap: 8px; margin: 0 12px 8px; padding: 12px; border-radius: var(--r-md); background: var(--surface); }
  .compose-panel form { display: flex; gap: 8px; }
  .compose-panel input { flex: 1; min-width: 0; font: inherit; color: var(--ink); background: var(--surface-2); border: 1px solid var(--line); border-radius: var(--r-sm); padding: 6px 10px; }
  .compose-panel button { border: 0; border-radius: var(--r-full); padding: 6px 14px; background: var(--accent); color: var(--on-accent); font-weight: 600; cursor: pointer; }
  .compose-panel button:disabled { opacity: .5; }
  .compose-panel a { color: var(--accent); text-decoration: none; font-size: var(--fs-sm); }
  .here { display: flex; align-items: flex-start; gap: 6px; padding: 4px 12px 12px; overflow-x: auto; border-bottom: 1px solid var(--line); scrollbar-width: none; }
  .here-one { flex: none; width: 64px; display: grid; justify-items: center; gap: 4px; border: 0; background: none; color: var(--ink-2); cursor: pointer; padding: 0; font: inherit; }
  .ring { width: 60px; height: 60px; border-radius: 50%; display: grid; place-items: center; border: 2px solid transparent; }
  .ring.front { border-color: var(--accent); }
  .ring.cocon { border: 2px dashed var(--accent); }
  .face { width: 52px; height: 52px; border-radius: 50%; display: grid; place-items: center; overflow: hidden; font-weight: 700; font-size: 20px; }
  .here-name { max-width: 64px; font-size: 12px; line-height: 16px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .switch { flex: none; align-self: center; margin-top: -16px; width: 36px; height: 36px; display: grid; place-items: center; border-radius: 50%;
    border: 1.5px dashed var(--line); background: none; color: var(--accent); cursor: pointer; }
  .switch:hover { border-color: var(--accent); }
  .rows { flex: 1; overflow-y: auto; min-height: 0; }
  .row { display: flex; align-items: center; gap: 12px; padding: 2px 0 2px 12px; color: inherit; text-decoration: none; transition: background var(--t-fast) var(--ease); }
  .row:hover { background: var(--surface); }
  .row.on { background: var(--accent-soft); }
  .disc { flex: none; width: 52px; height: 52px; border-radius: 50%; display: grid; place-items: center; overflow: hidden; font-weight: 700; font-size: 20px; }
  .body { flex: 1; min-width: 0; display: grid; gap: 3px; padding: 12px 14px 12px 0; border-bottom: 1px solid var(--line); }
  .row.on .body, .row:has(+ .row.on) .body { border-bottom-color: transparent; }
  .top, .bottom { display: flex; align-items: center; gap: 5px; min-width: 0; }
  .top { color: var(--ink-3); }
  .title { flex: 1; min-width: 0; color: var(--ink); font-weight: 600; font-size: 15px; line-height: 20px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .muted-icon { display: grid; color: var(--ink-3); }
  time { flex: none; font-size: 12px; line-height: 16px; color: var(--ink-3); }
  .bottom { gap: 4px; font-size: 14px; line-height: 19px; }
  .who { flex: none; color: var(--ink); max-width: 45%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .text { flex: 1; min-width: 0; color: var(--ink-3); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .badge { flex: none; min-width: 22px; padding: 2px 7px; border-radius: 11px; background: var(--sun); color: var(--on-sun); font-size: 12px; line-height: 16px; font-weight: 700; text-align: center; }
  .badge.muted { background: var(--line); color: var(--ink); }
  .none { color: var(--ink-3); text-align: center; padding: 32px 16px; font-size: var(--fs-sm); }
</style>
