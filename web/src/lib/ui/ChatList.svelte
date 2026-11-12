<script lang="ts">
  // The desktop chat list (Figma F5.21): search, who is here now, then every conversation across
  // spaces, newest first, with its last message, time and unread count. Muted chats keep a grey badge.
  import { core } from '../core';
  import { chatMembers } from '../subsystems';
  import { channels, latestMessage, members, spaces, unread, fuzzy, type ChannelRow, type MemberRow, type SpaceRow } from '../data';
  import { ARCHIVED, chatPrefs, chatTime, discColor, dmFace, folderOf, groupsInto, HIDDEN, isSpaceEntry, movePin, orderChats, PINS, previewOf, privateVisible, stillArchived, togglePin, withSpaces, type Folder, type SpaceEntry } from '../chat-list';
  import { contains, holds, PREF as FOLDERS_PREF, setChat, toJson, withCustom, type Settings as FolderSettings } from '../chat-folders';
  import ChatFolders from './ChatFolders.svelte';
  import { actingOpts, bodyPresence, frontLayers } from '../front-view';
  import { authorCards, spaceTitle, type SpaceInfo } from '../spaces';
  import { privateSides } from '../chat-manage';
  import { sync, type Projection } from '../sync/client';
  import AvatarImage from './AvatarImage.svelte';
  import Icon from './Icon.svelte';
  import EmptyState from './kit/EmptyState.svelte';
  import { longPress } from '../long-press';

  let { projection, dark, currentId, folder, folders, foldersAt = null, onfolders, directory, person, onswitch, onnewchat, viewingAs = null,
    tabs = [], counts = {}, onfolder }: {
    projection: Projection; dark: boolean; currentId?: string; folder: string; directory: Map<string, SpaceInfo>;
    /** Chat folders (D-113): the settings, and the folders sheet ("" its list, "new:<chat>", a folder) or null. */
    folders: FolderSettings; foldersAt?: string | null; onfolders: (at: string | null) => void;
    person: boolean; onswitch: () => void;
    /** The pencil opens New chat (NewChat.svelte). */
    onnewchat: () => void; viewingAs?: string | null;
    /** Narrow screens have no folder rail: the folders as tabs over the list, with unread counts. */
    tabs?: { id: string; label: string }[]; counts?: Record<string, number>; onfolder?: (f: string) => void;
  } = $props();

  let query = $state('');
  // other accounts' members in our DMs, from author cards: whom a private chat is kept to (D-098)
  // and the person at the other end of each DM
  let cards = $state(new Map<string, MemberRow>());
  const needCards = $derived(spaces(projection).filter((s) => s.kind === 'dm').map((s) => s.id).sort().join(','));
  let cardsFor = '';
  $effect(() => {
    const want = needCards;
    if (!want || want === cardsFor) return;
    cardsFor = want;
    for (const id of want.split(',')) {
      authorCards(id).then((c) => { cards = new Map([...cards, ...c.members.map((m) => [m.id, m] as const)]); }).catch(() => {});
    }
  });
  const people = $derived(new Map(members(projection).map((m) => [m.id, m])));
  const nameOf = (id: string) => { const m = people.get(id); return m?.display_name ?? m?.name ?? 'Someone'; };
  const ss = $derived(spaces(projection));

  interface Row {
    channel: ChannelRow; space: SpaceRow | undefined; folder: Exclude<Folder, 'all'>; title: string; icon: string | null;
    at: number; unread: number; muted: boolean; who: string | null; text: string; disc: { glyph: string; color: string; blob?: string };
    id: string; pinned: boolean;
    /** archived for us (D-111) with nothing newer since: listed under Archived chats */
    stowed: boolean;
    /** in a shared space with several channels: listed under the space's entry, by its own name */
    grouped: boolean;
    /** whose a private chat with another account is on our side ("• Kai", D-098), so one member's
     * chat with someone isn't mistaken for another's */
    whose: string | null;
  }
  const organize = $derived(chatPrefs(projection.rows.pref ?? {}, sync.accountId));
  const rows: Row[] = $derived.by(() => {
    const all = channels(projection).filter((c) => !c.archived && c.kind !== 'thread');
    const prefs = projection.rows.pref ?? {};
    // members inside a fronting subsystem count as here (D-092)
    const switched = bodyPresence(projection, sync.accountId);
    const front = switched;
    const inside = chatMembers(projection, sync.accountId);
    const own = new Set(members(projection).map((m) => m.id));
    const list: Row[] = [];
    for (const c of all) {
      const space = ss.find((s) => s.id === c.space_id);
      // a member's private chats only while one of them is here (D-097)
      if (!privateVisible(c, space, front, viewingAs, organize.hidePrivate, (m) => own.has(m), switched, inside)) continue;
      // cleared for us: gone until something newer arrives
      const cleared = organize.hidden.get(c.id);
      if (cleared !== undefined && (latestMessage(projection, c.id)?.occurred_at ?? 0) <= cleared) continue;
      list.push(row(c, space, all, prefs, own));
    }
    return orderChats(list, organize.pins);
  });
  function row(c: ChannelRow, space: SpaceRow | undefined, all: ChannelRow[], prefs: NonNullable<Projection['rows']['pref']>, own: Set<string>): Row {
    {
      const f = folderOf(c, space);
      const siblings = all.filter((o) => o.space_id === c.space_id && o.kind !== 'thread').length;
      // a private chat in a DM (D-098): titled by their member on their side, by the friend on ours
      const sides = privateSides(c, space, (m) => own.has(m));
      const theirs = sides?.theirs.map((id) => cards.get(id)?.display_name ?? cards.get(id)?.name).filter(Boolean).join(' & ');
      const title = sides ? (theirs || (space ? spaceTitle(space, directory.get(space.id), sync.accountId) : c.name))
        : c.kind === 'member_dm' ? c.name
        : space?.kind === 'dm' ? spaceTitle(space, directory.get(space.id), sync.accountId)
        : space?.kind === 'shared' ? (siblings > 1 ? `${space.name} · ${c.name}` : space.name)
        : c.name;
      const latest = latestMessage(projection, c.id);
      // a DM between two of our own members already says who: show only the words
      const preview = previewOf(latest, (id) => (people.has(id) ? nameOf(id) : ''), c.kind !== 'member_dm');
      const notify = (prefs[`${sync.accountId}||notify_channel:${c.id}`] ?? prefs[`||notify_channel:${c.id}`])?.fields.value;
      const partner = c.kind === 'member_dm' && !sides ? people.get(c.member_ids?.[0] ?? '')
        : sides?.theirs.length ? cards.get(sides.theirs[0])
        : space?.kind === 'dm' ? dmFace(directory.get(space.id), sync.accountId, cards.values()) : undefined;
      const grouped = !sides && groupsInto(space, siblings);
      // a channel listed under its space wears its own initial, not the space's
      const letter = ((grouped ? c.name : title).match(/\p{L}|\p{N}/u)?.[0] ?? '#').toUpperCase();
      const disc = c.avatar_blob ? { glyph: c.icon ?? letter, color: c.color ?? discColor(c.id), blob: c.avatar_blob }
        : partner ? { glyph: partner.sigils[0] ?? partner.name[0] ?? '?', color: partner.color, blob: partner.avatar_blob ?? undefined }
        : { glyph: c.icon ?? letter, color: c.color ?? discColor(c.id) };
      return {
        channel: c, space, folder: f, title, icon: sides ? 'lock' : c.kind === 'member_dm' || space?.kind === 'dm' ? null : f === 'internal' ? (c.member_ids?.length ? 'lock' : 'hash') : 'globe',
        at: latest?.occurred_at ?? 0, unread: c.id === currentId ? 0 : unread(projection, c.id, sync.accountId),
        muted: notify === 'none', who: preview.who, text: preview.text, disc, id: c.id, pinned: organize.pins.includes(c.id),
        stowed: stillArchived(organize.archived.get(c.id), latest?.occurred_at),
        grouped,
        whose: sides?.mine.map(nameOf).join(' & ') || null,
      };
    }
  }

  // ⋯ / right-click: pin, delete (our own space) or clear (a DM with another account, a shared channel)
  let menuFor = $state<string | null>(null);
  let dragging = $state<string | null>(null);
  let dropOn = $state<string | null>(null);
  function savePins(pins: string[]) {
    const existing = new Set(channels(projection).map((c) => c.id));
    sync.create('pref.set', sync.accountScope, null, { device: '', key: PINS, value: [...new Set(pins.filter((p) => existing.has(p)))] });
  }
  function remove(r: Row) {
    menuFor = null;
    const own = r.space?.kind === 'internal';
    const what = r.channel.kind === 'member_dm' ? 'this chat' : r.space?.kind === 'dm' ? `your chat with ${r.title}` : `#${r.title}`;
    if (own) {
      if (!confirm(`Delete ${what}?`)) return;
      sync.create('channel.delete', `space:${r.channel.space_id}`, r.channel.id, {}, actingOpts(projection, sync.accountId));
    } else {
      if (!confirm(`Clear ${what}?`)) return;
      sync.create('pref.set', sync.accountScope, null, { device: '', key: `${HIDDEN}${r.channel.id}`, value: Math.max(Date.now(), r.at) });
    }
    if (r.pinned) savePins(organize.pins.filter((p) => p !== r.id));
    if (r.channel.id === currentId) location.hash = '#/chat';
  }
  // Mute is the notification level "none"; unmuting goes back to the default (D-100: DMs tell, other chats mention)
  function mute(r: Row) {
    menuFor = null;
    sync.create('pref.set', sync.accountScope, null, { device: '', key: `notify_channel:${r.channel.id}`, value: r.muted ? (r.space?.kind === 'dm' ? 'all' : 'mentions') : 'none' });
  }
  // archiving is when, so a newer message brings the chat back on every device (D-111)
  function archive(r: Row) {
    menuFor = null;
    sync.create('pref.set', sync.accountScope, null, { device: '', key: `${ARCHIVED}${r.channel.id}`, value: r.stowed ? null : Math.max(Date.now(), r.at) });
  }
  let showArchived = $state(false);
  const archivedCount = $derived(rows.filter((r) => r.stowed).length);
  const archiveView = $derived(showArchived && archivedCount > 0);
  // pinned chats are arranged by dragging one onto another
  function drop(onto: string) {
    if (dragging && dragging !== onto) savePins(movePin(organize.pins, dragging, onto));
    dragging = null; dropOn = null;
  }
  // a search looks through archived chats too
  const shown = $derived(rows.filter((r) => (query.trim() ? fuzzy(query, `${r.title} ${r.text}`) !== null
    : archiveView ? r.stowed : !r.stowed && contains(folders, folder, r.channel.id, r.folder, r.unread, r.muted))));
  // a shared space with several channels is one entry, like a Telegram forum; opening it lists
  // its channels by their own names (owner, 2026-11-06). Searches and Archived stay flat.
  let openSpace = $state<string | null>(null);
  const spaceRow = (r: Row) => (r.grouped && r.space ? { id: r.space.id, name: r.space.name } : null);
  const entries: (Row | SpaceEntry<Row>)[] = $derived(query.trim() || archiveView ? shown
    : orderChats(withSpaces(shown, spaceRow), organize.pins));
  const spaceChats = $derived.by(() => {
    if (!openSpace || query.trim() || archiveView) return null;
    const list = orderChats(rows.filter((r) => !r.stowed && r.space?.id === openSpace), organize.pins);
    // a space left with nothing listed (all archived, or gone) falls back to the whole list
    return list.length ? list : null;
  });
  const insideTitle = $derived(ss.find((s) => s.id === openSpace)?.name ?? '');
  // opening one of its channels from elsewhere (a link, search) shows the space's channels around it
  let seen: string | undefined;
  $effect(() => {
    if (currentId === seen) return;
    seen = currentId;
    const r = rows.find((x) => x.channel.id === currentId);
    if (r?.grouped) openSpace = r.space?.id ?? null;
  });
  function pinSpace(e: SpaceEntry<Row>) {
    menuFor = null;
    savePins(e.pinned ? organize.pins.filter((p) => !e.ids.includes(p)) : [...organize.pins, ...e.ids]);
  }
  function archiveSpace(e: SpaceEntry<Row>) {
    menuFor = null;
    for (const r of e.rows) sync.create('pref.set', sync.accountScope, null, { device: '', key: `${ARCHIVED}${r.channel.id}`, value: Math.max(Date.now(), r.at) });
  }
  const letter = (t: string) => (t.match(/\p{L}|\p{N}/u)?.[0] ?? '#').toUpperCase();

  // a chat's folders, from its menu: tick the folders of our own it belongs in
  let foldersFor = $state<string | null>(null);
  function toggleFolder(r: Row, id: string) {
    const f = folders.custom.find((c) => c.id === id);
    if (!f) return;
    const next = withCustom(folders, setChat(f, r.channel.id, r.folder, !holds(f, r.channel.id, r.folder)));
    sync.create('pref.set', sync.accountScope, null, { device: '', key: FOLDERS_PREF, value: toJson(next) });
  }

  // who is here now: the Body's presence, innermost layer first
  const layers = $derived(frontLayers(projection, sync.accountId));
  // members inside a fronting subsystem come with it (D-092, display only)
  // the front exactly as switched (D-102: a subsystem's members don't each join the strip)
  const expanded = $derived({ entries: bodyPresence(projection, sync.accountId), via: new Map<string, string>() });
  const here = $derived(expanded.entries.filter((e) => e.subject_type === 'member' && people.has(e.subject_id))
    .sort((a, b) => layers.findIndex((l) => l.id === a.level) - layers.findIndex((l) => l.id === b.level) || Number(b.is_primary) - Number(a.is_primary)));
  const layerName = (id: string) => layers.find((l) => l.id === id)?.name ?? '';
</script>

<svelte:window onpointerdown={(e) => { if (menuFor && !(e.target as Element).closest?.('.row-menu, .more')) menuFor = null; }}
  onkeydown={(e) => { if (e.key === 'Escape') menuFor = null; }} />
<section class="list" aria-label="Chats">
  <div class="search-row">
    <label class="search"><Icon name="search" size={18} /><input bind:value={query} placeholder="Search" aria-label="Search chats" /></label>
    <button class="compose" onclick={onnewchat} aria-label="New chat"><Icon name="edit" size={22} /></button>
  </div>
  {#if archiveView}
    <button class="archive-bar" onclick={() => (showArchived = false)}><Icon name="back" size={18} /> Archived chats</button>
  {:else if archivedCount && !query.trim()}
    <button class="archive-bar" onclick={() => (showArchived = true)}><Icon name="archive" size={18} /> Archived chats ({archivedCount})</button>
  {/if}
  {#if !person && folders.showHere && here.length && !archiveView}
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
  {#if tabs.length > 1 && !archiveView && !spaceChats && !query.trim()}
    <div class="folder-tabs" role="tablist" aria-label="Chat folders">
      {#each tabs as t (t.id)}
        <button role="tab" aria-selected={folder === t.id} class:on={folder === t.id} onclick={() => onfolder?.(t.id)}>
          {t.label}{#if counts[t.id]}<span class="count">{counts[t.id] > 99 ? '99+' : counts[t.id]}</span>{/if}</button>
      {/each}
    </div>
  {/if}
  {#if spaceChats}
    {@const c = core.adaptColor(discColor(openSpace ?? ''), dark)}
    <div class="space-head">
      <button class="back" onclick={() => (openSpace = null)} aria-label="Back to chats"><Icon name="back" size={20} /></button>
      <span class="disc small" style="background: {c.ring}; color: {c.avatar_ink}">{letter(insideTitle)}</span>
      <span class="space-name"><strong>{insideTitle}</strong><small>{spaceChats.length} {spaceChats.length === 1 ? 'channel' : 'channels'}</small></span>
    </div>
  {/if}
  <div class="rows">
    {#if spaceChats}
      {#each spaceChats as r (r.channel.id)}{@render chatRow(r, true)}{/each}
    {:else}
    {#each entries as e (e.id)}
      {#if isSpaceEntry(e)}
        {@const latest = e.rows[0]}
        {@const c = core.adaptColor(discColor(e.spaceId), dark)}
        <div class="row-wrap" role="listitem">
          <button class="row space-entry" class:pinned={e.pinned} class:compact={folders.compact} onclick={() => (openSpace = e.spaceId)}
            oncontextmenu={(ev) => { ev.preventDefault(); menuFor = e.id; }} use:longPress={{ enabled: true, onpress: () => (menuFor = e.id) }}
            aria-label="{e.title}, {e.rows.length} channels{e.unread ? `, ${e.unread} unread` : ''}">
            <span class="disc forum" style="background: {c.ring}; color: {c.avatar_ink}">{letter(e.title)}</span>
            <span class="body">
              <span class="top">
                <Icon name="globe" size={16} />
                <span class="title">{e.title}</span>
                {#if e.muted}<span class="muted-icon" title="Muted"><Icon name="muted" size={14} /></span>{/if}
                {#if e.at}<time>{chatTime(e.at)}</time>{/if}
              </span>
              <span class="bottom">
                <span class="topic"># {latest.channel.name}</span>
                {#if latest.who}<span class="who">{latest.who}:</span>{/if}
                <span class="text">{latest.text}</span>
                {#if e.unread}<span class="badge" class:muted={e.muted}>{e.unread > 999 ? '999+' : e.unread}</span>
                {:else if e.pinned}<span class="pin" title="Pinned"><Icon name="pin" size={14} /></span>{/if}
              </span>
            </span>
          </button>
          <button class="more" aria-label="Actions for {e.title}" aria-haspopup="menu" aria-expanded={menuFor === e.id}
            onclick={() => (menuFor = menuFor === e.id ? null : e.id)}><Icon name="more" size={18} /></button>
          {#if menuFor === e.id}
            <div class="row-menu" role="menu">
              <button role="menuitem" onclick={() => { menuFor = null; openSpace = e.spaceId; }}><Icon name="chat" size={16} /> Show channels</button>
              <button role="menuitem" onclick={() => pinSpace(e)}><Icon name="pin" size={16} /> {e.pinned ? 'Unpin' : 'Pin to top'}</button>
              <button role="menuitem" onclick={() => archiveSpace(e)}><Icon name="archive" size={16} /> Archive all</button>
            </div>
          {/if}
        </div>
      {:else}{@render chatRow(e, false)}{/if}
    {:else}
      <EmptyState icon={query.trim() ? 'search' : 'chat'} label={query.trim() ? 'No matches' : 'No chats yet'} />
    {/each}
    {/if}
  </div>
</section>
{#snippet chatRow(r: Row, inSpace: boolean)}
      <div class="row-wrap" class:drop={dropOn === r.id} role="listitem"
        ondragover={(e) => { if (dragging && r.pinned) { e.preventDefault(); dropOn = r.id; } }}
        ondragleave={() => { if (dropOn === r.id) dropOn = null; }}
        ondrop={(e) => { e.preventDefault(); drop(r.id); }}>
      <a class="row" class:on={r.channel.id === currentId} class:pinned={r.pinned} class:compact={folders.compact} href="#/chat/{r.channel.id}" aria-current={r.channel.id === currentId ? 'page' : undefined}
        draggable={r.pinned} ondragstart={(e) => { dragging = r.id; e.dataTransfer?.setData('text/plain', r.id); }} ondragend={() => { dragging = null; dropOn = null; }}
        oncontextmenu={(e) => { e.preventDefault(); foldersFor = null; menuFor = r.id; }}
        use:longPress={{ enabled: true, onpress: () => { foldersFor = null; menuFor = r.id; } }}>
        <span class="disc" style="background: {core.adaptColor(r.disc.color, dark).ring}; color: {core.adaptColor(r.disc.color, dark).avatar_ink}">
          {#if r.disc.blob}<AvatarImage hash={r.disc.blob} glyph={r.disc.glyph} name={r.title} />{:else}{r.disc.glyph}{/if}
        </span>
        <span class="body">
          <span class="top">
            {#if r.icon}<Icon name={r.icon} size={16} />{/if}
            <span class="title" class:fit={r.whose}>{inSpace ? r.channel.name : r.title}</span>
            {#if r.whose}<span class="whose">• {r.whose}</span>{/if}
            {#if r.muted}<span class="muted-icon" title="Muted"><Icon name="muted" size={14} /></span>{/if}
            {#if r.at}<time>{chatTime(r.at)}</time>{/if}
          </span>
          <span class="bottom">
            {#if r.who}<span class="who">{r.who}:</span>{/if}
            <span class="text">{r.text}</span>
            {#if r.unread}<span class="badge" class:muted={r.muted}>{r.unread > 999 ? '999+' : r.unread}</span>
            {:else if r.pinned}<span class="pin" title="Pinned"><Icon name="pin" size={14} /></span>{/if}
          </span>
        </span>
      </a>
      <button class="more" aria-label="Chat actions for {r.title}" aria-haspopup="menu" aria-expanded={menuFor === r.id}
        onclick={() => { foldersFor = null; menuFor = menuFor === r.id ? null : r.id; }}><Icon name="more" size={18} /></button>
      {#if menuFor === r.id}
        <div class="row-menu" role="menu">
          <button role="menuitem" onclick={() => mute(r)}><Icon name="muted" size={16} /> {r.muted ? 'Unmute' : 'Mute'}</button>
          <button role="menuitem" onclick={() => { menuFor = null; savePins(togglePin(organize.pins, r.id)); }}><Icon name="pin" size={16} /> {r.pinned ? 'Unpin' : 'Pin to top'}</button>
          <button role="menuitem" onclick={() => archive(r)}><Icon name="archive" size={16} /> {r.stowed ? 'Unarchive' : 'Archive'}</button>
          {#if foldersFor === r.id}
            {#each folders.custom as f (f.id)}
              <button role="menuitemcheckbox" aria-checked={holds(f, r.channel.id, r.folder)} onclick={() => toggleFolder(r, f.id)}>
                <span class="tick">{holds(f, r.channel.id, r.folder) ? '✓' : ''}</span> {f.name || 'Folder'}</button>
            {/each}
            <button role="menuitem" onclick={() => { menuFor = null; foldersFor = null; onfolders(`new:${r.channel.id}`); }}><Icon name="plus" size={16} /> New folder…</button>
          {:else}
            <button role="menuitem" onclick={() => (folders.custom.length ? (foldersFor = r.id) : (menuFor = null, onfolders(`new:${r.channel.id}`)))}><Icon name="folder" size={16} /> Folders…</button>
          {/if}
          <button role="menuitem" class="danger" onclick={() => remove(r)}><Icon name="delete" size={16} /> {r.space?.kind === 'internal' ? 'Delete chat' : 'Clear chat'}</button>
        </div>
      {/if}
      </div>
{/snippet}
{#if foldersAt !== null}
  <ChatFolders settings={folders} {person} startWith={foldersAt || null} onclose={() => onfolders(null)}
    chats={rows.map((r) => ({ id: r.channel.id, title: r.title, kind: r.folder, own: r.space?.kind === 'internal' }))} />
{/if}

<style>
  /* min-width: 0 keeps a long "here now" strip (big subsystems) from widening the list past its column */
  .list { display: flex; flex-direction: column; min-height: 0; min-width: 0; overflow: hidden; background: var(--bg); border-right: 1px solid var(--line); }
  .search-row { display: flex; align-items: center; gap: var(--s-2); padding: var(--s-3) var(--s-2) var(--s-3) var(--s-3); }
  .search { flex: 1; display: flex; align-items: center; gap: var(--s-2); height: 37px; padding: 0 var(--s-3); border-radius: var(--r-full); background: var(--surface-2); color: var(--ink-3); }
  .search:focus-within { box-shadow: 0 0 0 2px var(--accent); }
  .search input { flex: 1; min-width: 0; border: 0; outline: 0; background: none; color: var(--ink); font: inherit; font-size: var(--fs-md); }
  .compose { width: 40px; height: 40px; display: grid; place-items: center; border: 0; border-radius: 50%; background: none; color: var(--ink-2); cursor: pointer; }
  .compose:hover { background: var(--surface-2); color: var(--ink); }
  .archive-bar { display: flex; align-items: center; gap: var(--s-2); margin: 0 var(--s-3) var(--s-2); padding: var(--s-2) var(--s-3); border: 0; border-radius: var(--r-md);
    background: var(--surface); color: var(--ink-2); font: inherit; font-size: var(--fs-sm); cursor: pointer; text-align: left; }
  .archive-bar:hover { background: var(--surface-2); color: var(--ink); }
  .row-wrap { position: relative; }
  .row-wrap.drop { box-shadow: inset 0 2px 0 var(--accent); }
  .row.pinned { background: var(--surface); }
  .row.compact { padding-top: var(--s-1); padding-bottom: var(--s-1); }
  .row.compact .disc { width: 40px; height: 40px; font-size: var(--fs-base); }
  .tick { display: inline-block; width: 16px; text-align: center; color: var(--accent); }
  .row.pinned[draggable='true'] { cursor: grab; }
  .pin { color: var(--ink-3); display: inline-flex; }
  .more { position: absolute; right: 8px; top: 8px; width: 28px; height: 28px; display: none; place-items: center; border: 0; border-radius: 50%;
    background: var(--surface-2); color: var(--ink-2); cursor: pointer; }
  .row-wrap:hover .more, .more[aria-expanded='true'], .more:focus-visible { display: grid; }
  .row-menu { position: absolute; right: 8px; top: 40px; z-index: 5; display: grid; min-width: 160px; padding: var(--s-1); border-radius: var(--r-md);
    background: var(--surface-2); box-shadow: var(--shadow-menu); }
  .row-menu button { display: flex; align-items: center; gap: var(--s-2); padding: var(--s-2) 10px; border: 0; border-radius: var(--r-sm); background: none;
    color: var(--ink); font: inherit; text-align: left; cursor: pointer; }
  .row-menu button:hover { background: var(--surface-3); }
  .row-menu .danger { color: var(--danger); }
  .here { min-width: 0; display: flex; align-items: flex-start; gap: 6px; padding: var(--s-1) var(--s-3) var(--s-3); overflow-x: auto; border-bottom: 1px solid var(--line); scrollbar-width: none; }
  .here-one { flex: none; width: 64px; display: grid; justify-items: center; gap: var(--s-1); border: 0; background: none; color: var(--ink-2); cursor: pointer; padding: 0; font: inherit; }
  .ring { width: 60px; height: 60px; border-radius: 50%; display: grid; place-items: center; border: 2px solid transparent; }
  .ring.front { border-color: var(--accent); }
  .ring.cocon { border: 2px dashed var(--accent); }
  .face { width: 52px; height: 52px; border-radius: 50%; display: grid; place-items: center; overflow: hidden; font-weight: 700; font-size: 20px; }
  .here-name { max-width: 64px; font-size: var(--fs-xs); line-height: 16px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .switch { flex: none; align-self: center; margin-top: -16px; width: 36px; height: 36px; display: grid; place-items: center; border-radius: 50%;
    border: 1.5px dashed var(--line); background: none; color: var(--accent); cursor: pointer; }
  .switch:hover { border-color: var(--accent); }
  .rows { flex: 1; overflow-y: auto; min-height: 0; }
  /* a held finger opens the chat's menu, not the browser's link preview */
  .row { display: flex; align-items: center; gap: var(--s-3); padding: 2px 0 2px var(--s-3); color: inherit; text-decoration: none; transition: background var(--t-fast) var(--ease);
    -webkit-touch-callout: none; user-select: none; }
  .row:hover { background: var(--surface); }
  .row.on { background: var(--accent-soft); }
  .disc { flex: none; width: 52px; height: 52px; border-radius: 50%; display: grid; place-items: center; overflow: hidden; font-weight: 700; font-size: 20px; }
  .body { flex: 1; min-width: 0; display: grid; gap: 3px; padding: var(--s-3) 14px var(--s-3) 0; border-bottom: 1px solid var(--line); }
  .row.on .body, .row-wrap:has(+ .row-wrap .row.on) .body { border-bottom-color: transparent; }
  .top, .bottom { display: flex; align-items: center; gap: 5px; min-width: 0; }
  .top { color: var(--ink-3); }
  .title { flex: 1; min-width: 0; color: var(--ink); font-weight: 600; font-size: var(--fs-md); line-height: 20px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .title.fit { flex: 0 1 auto; }
  .whose { flex: 1 1 0; min-width: 40px; color: var(--ink-3); font-size: var(--fs-sm); line-height: 18px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .muted-icon { display: grid; color: var(--ink-3); }
  time { flex: none; font-size: var(--fs-xs); line-height: 16px; color: var(--ink-3); }
  .bottom { gap: var(--s-1); font-size: 14px; line-height: 19px; }
  .who { flex: none; color: var(--ink); max-width: 45%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .text { flex: 1; min-width: 0; color: var(--ink-3); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .badge { flex: none; min-width: 22px; padding: 2px 7px; border-radius: 11px; background: var(--sun); color: var(--on-sun); font-size: var(--fs-xs); line-height: 16px; font-weight: 700; text-align: center; }
  .badge.muted { background: var(--line); color: var(--ink); }
  /* a space's entry is a button styled as a row; its disc is rounded square, as Telegram's forums */
  button.row { width: 100%; border: 0; background: none; font: inherit; text-align: left; cursor: pointer; }
  .disc.forum { border-radius: var(--r-md); }
  .topic { flex: none; max-width: 40%; color: var(--accent); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .space-head { display: flex; align-items: center; gap: 10px; padding: var(--s-1) var(--s-3) 10px var(--s-1); border-bottom: 1px solid var(--line); }
  .space-head .back { width: 40px; height: 40px; display: grid; place-items: center; border: 0; border-radius: 50%; background: none; color: var(--ink-2); cursor: pointer; }
  .space-head .back:hover { background: var(--surface-2); color: var(--ink); }
  .disc.small { width: 36px; height: 36px; font-size: var(--fs-md); border-radius: var(--r-row); }
  .space-name { display: grid; min-width: 0; }
  .space-name strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .space-name small { color: var(--ink-3); font-size: var(--fs-xs); }
  .folder-tabs { display: none; gap: var(--s-1); padding: 0 var(--s-2) var(--s-2); overflow-x: auto; scrollbar-width: none; border-bottom: 1px solid var(--line); }
  .folder-tabs button { flex: none; display: inline-flex; align-items: center; gap: 6px; padding: 6px var(--s-3); border: 0; border-radius: var(--r-full);
    background: none; color: var(--ink-3); font: inherit; font-size: var(--fs-sm); cursor: pointer; }
  .folder-tabs button.on { background: var(--accent-soft); color: var(--accent); font-weight: 600; }
  .folder-tabs .count { min-width: 18px; padding: 0 5px; border-radius: 9px; background: var(--line); color: var(--ink); font-size: 11px; line-height: 16px; text-align: center; }
  @media (max-width: 1023px) { .folder-tabs { display: flex; } .list { border-right: 0; } }
</style>
