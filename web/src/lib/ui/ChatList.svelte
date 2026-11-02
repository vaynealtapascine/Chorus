<script lang="ts">
  // The desktop chat list (Figma F5.21): search, who is here now, then every conversation across
  // spaces, newest first, with its last message, time and unread count. Muted chats keep a grey badge.
  import { core } from '../core';
  import { channels, latestMessage, members, spaces, unread, fuzzy, type ChannelRow, type MemberRow, type SpaceRow } from '../data';
  import { ARCHIVED, chatPrefs, chatTime, discColor, folderOf, HIDDEN, movePin, orderChats, PINS, previewOf, privateVisible, stillArchived, togglePin, type Folder } from '../chat-list';
  import { bodyPresence, frontLayers } from '../front-view';
  import { authorCards, spaceTitle, type SpaceInfo } from '../spaces';
  import { privateSides } from '../chat-manage';
  import { sync, type Projection } from '../sync/client';
  import AvatarImage from './AvatarImage.svelte';
  import Icon from './Icon.svelte';

  let { projection, dark, currentId, folder, directory, person, onswitch, onnewchannel, onnewchat, viewingAs = null }: {
    projection: Projection; dark: boolean; currentId?: string; folder: Folder; directory: Map<string, SpaceInfo>;
    person: boolean; onswitch: () => void; onnewchannel?: (name: string) => void;
    /** The pencil opens New chat (NewChat.svelte); without it, the old inline form. */
    onnewchat?: () => void; viewingAs?: string | null;
  } = $props();

  let query = $state('');
  // names for other accounts' members a private chat is kept to (D-098), from author cards
  let cards = $state(new Map<string, MemberRow>());
  const needCards = $derived([...new Set(channels(projection).filter((c) => c.kind === 'member_dm' && c.member_ids?.length &&
    ss.find((s) => s.id === c.space_id)?.kind === 'dm' && c.member_ids.some((m) => !people.has(m))).map((c) => c.space_id))].sort().join(','));
  let cardsFor = '';
  $effect(() => {
    const want = needCards;
    if (!want || want === cardsFor) return;
    cardsFor = want;
    for (const id of want.split(',')) {
      authorCards(id).then((c) => { cards = new Map([...cards, ...c.members.map((m) => [m.id, m] as const)]); }).catch(() => {});
    }
  });
  let composing = $state(false);
  let newName = $state('');
  const people = $derived(new Map(members(projection).map((m) => [m.id, m])));
  const nameOf = (id: string) => { const m = people.get(id); return m?.display_name ?? m?.name ?? 'Someone'; };
  const ss = $derived(spaces(projection));

  interface Row {
    channel: ChannelRow; space: SpaceRow | undefined; folder: Exclude<Folder, 'all'>; title: string; icon: string | null;
    at: number; unread: number; muted: boolean; who: string | null; text: string; disc: { glyph: string; color: string; blob?: string };
    id: string; pinned: boolean;
    /** archived for us (D-111) with nothing newer since: listed under Archived chats */
    stowed: boolean;
  }
  const organize = $derived(chatPrefs(projection.rows.pref ?? {}, sync.accountId));
  const rows: Row[] = $derived.by(() => {
    const all = channels(projection).filter((c) => !c.archived && c.kind !== 'thread');
    const prefs = projection.rows.pref ?? {};
    // members inside a fronting subsystem count as here (D-092)
    const switched = bodyPresence(projection, sync.accountId);
    const front = switched;
    const own = new Set(members(projection).map((m) => m.id));
    const list: Row[] = [];
    for (const c of all) {
      const space = ss.find((s) => s.id === c.space_id);
      // a member's private chats only while one of them is here (D-097)
      if (!privateVisible(c, space, front, viewingAs, organize.hidePrivate, (m) => own.has(m), switched)) continue;
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
        : sides?.theirs.length ? cards.get(sides.theirs[0]) : undefined;
      const letter = (title.match(/\p{L}|\p{N}/u)?.[0] ?? '#').toUpperCase();
      const disc = c.avatar_blob ? { glyph: c.icon ?? letter, color: c.color ?? discColor(c.id), blob: c.avatar_blob }
        : partner ? { glyph: partner.sigils[0] ?? partner.name[0] ?? '?', color: partner.color, blob: partner.avatar_blob ?? undefined }
        : { glyph: c.icon ?? letter, color: c.color ?? discColor(c.id) };
      return {
        channel: c, space, folder: f, title, icon: sides ? 'lock' : c.kind === 'member_dm' || space?.kind === 'dm' ? null : f === 'internal' ? (c.member_ids?.length ? 'lock' : 'hash') : 'globe',
        at: latest?.occurred_at ?? 0, unread: c.id === currentId ? 0 : unread(projection, c.id, sync.accountId),
        muted: notify === 'none', who: preview.who, text: preview.text, disc, id: c.id, pinned: organize.pins.includes(c.id),
        stowed: stillArchived(organize.archived.get(c.id), latest?.occurred_at),
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
      if (!confirm(`Delete ${what}? It's deleted for everyone in your system, messages and all.`)) return;
      sync.create('channel.delete', `space:${r.channel.space_id}`, r.channel.id, {});
    } else {
      if (!confirm(`Clear ${what}? It leaves your chats and its messages leave your view, on all your devices. It comes back if someone writes again; they keep everything.`)) return;
      sync.create('pref.set', sync.accountScope, null, { device: '', key: `${HIDDEN}${r.channel.id}`, value: Math.max(Date.now(), r.at) });
    }
    if (r.pinned) savePins(organize.pins.filter((p) => p !== r.id));
    if (r.channel.id === currentId) location.hash = '#/chat';
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
    : archiveView ? r.stowed : !r.stowed && (folder === 'all' || r.folder === folder))));

  // who is here now: the Body's presence, innermost layer first
  const layers = $derived(frontLayers(projection, sync.accountId));
  // members inside a fronting subsystem come with it (D-092, display only)
  // the front exactly as switched (D-102: a subsystem's members don't each join the strip)
  const expanded = $derived({ entries: bodyPresence(projection, sync.accountId), via: new Map<string, string>() });
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

<svelte:window onpointerdown={(e) => { if (menuFor && !(e.target as Element).closest?.('.row-menu, .more')) menuFor = null; }}
  onkeydown={(e) => { if (e.key === 'Escape') menuFor = null; }} />
<section class="list" aria-label="Chats">
  <div class="search-row">
    <label class="search"><Icon name="search" size={18} /><input bind:value={query} placeholder="Search" aria-label="Search chats" /></label>
    <button class="compose" class:on={composing} onclick={() => (onnewchat ? onnewchat() : (composing = !composing))} aria-label="New chat" aria-expanded={composing}><Icon name="edit" size={22} /></button>
  </div>
  {#if composing}
    <div class="compose-panel">
      {#if onnewchannel}
        <form onsubmit={create}><input bind:value={newName} placeholder="New channel name" aria-label="New channel name" /><button disabled={!newName.trim()}>Create</button></form>
      {/if}
      <a href="#/people" onclick={() => (composing = false)}>Message a friend…</a>
    </div>
  {/if}
  {#if archiveView}
    <button class="archive-bar" onclick={() => (showArchived = false)}><Icon name="back" size={18} /> Archived chats <span>A new message brings a chat back</span></button>
  {:else if archivedCount && !query.trim()}
    <button class="archive-bar" onclick={() => (showArchived = true)}><Icon name="archive" size={18} /> Archived chats ({archivedCount})</button>
  {/if}
  {#if !person && here.length && !archiveView}
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
      <div class="row-wrap" class:drop={dropOn === r.id} role="listitem"
        ondragover={(e) => { if (dragging && r.pinned) { e.preventDefault(); dropOn = r.id; } }}
        ondragleave={() => { if (dropOn === r.id) dropOn = null; }}
        ondrop={(e) => { e.preventDefault(); drop(r.id); }}>
      <a class="row" class:on={r.channel.id === currentId} class:pinned={r.pinned} href="#/chat/{r.channel.id}" aria-current={r.channel.id === currentId ? 'page' : undefined}
        draggable={r.pinned} ondragstart={(e) => { dragging = r.id; e.dataTransfer?.setData('text/plain', r.id); }} ondragend={() => { dragging = null; dropOn = null; }}
        oncontextmenu={(e) => { e.preventDefault(); menuFor = r.id; }}>
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
            {#if r.unread}<span class="badge" class:muted={r.muted}>{r.unread > 999 ? '999+' : r.unread}</span>
            {:else if r.pinned}<span class="pin" title="Pinned"><Icon name="pin" size={14} /></span>{/if}
          </span>
        </span>
      </a>
      <button class="more" aria-label="Chat actions for {r.title}" aria-haspopup="menu" aria-expanded={menuFor === r.id}
        onclick={() => (menuFor = menuFor === r.id ? null : r.id)}><Icon name="more" size={18} /></button>
      {#if menuFor === r.id}
        <div class="row-menu" role="menu">
          <button role="menuitem" onclick={() => { menuFor = null; savePins(togglePin(organize.pins, r.id)); }}><Icon name="pin" size={16} /> {r.pinned ? 'Unpin' : 'Pin to top'}</button>
          <button role="menuitem" onclick={() => archive(r)}><Icon name="archive" size={16} /> {r.stowed ? 'Unarchive' : 'Archive'}</button>
          <button role="menuitem" class="danger" onclick={() => remove(r)}><Icon name="delete" size={16} /> {r.space?.kind === 'internal' ? 'Delete chat' : 'Clear chat'}</button>
        </div>
      {/if}
      </div>
    {:else}
      <p class="none">{query.trim() ? 'No chats match.' : 'No chats here yet.'}</p>
    {/each}
  </div>
</section>

<style>
  /* min-width: 0 keeps a long "here now" strip (big subsystems) from widening the list past its column */
  .list { display: flex; flex-direction: column; min-height: 0; min-width: 0; overflow: hidden; background: var(--bg); border-right: 1px solid var(--line); }
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
  .archive-bar { display: flex; align-items: center; gap: 8px; margin: 0 12px 8px; padding: 8px 12px; border: 0; border-radius: var(--r-md);
    background: var(--surface); color: var(--ink-2); font: inherit; font-size: var(--fs-sm); cursor: pointer; text-align: left; }
  .archive-bar:hover { background: var(--surface-2); color: var(--ink); }
  .archive-bar span { color: var(--ink-3); margin-left: auto; font-size: 12px; }
  .row-wrap { position: relative; }
  .row-wrap.drop { box-shadow: inset 0 2px 0 var(--accent); }
  .row.pinned { background: var(--surface); }
  .row.pinned[draggable='true'] { cursor: grab; }
  .pin { color: var(--ink-3); display: inline-flex; }
  .more { position: absolute; right: 8px; top: 8px; width: 28px; height: 28px; display: none; place-items: center; border: 0; border-radius: 50%;
    background: var(--surface-2); color: var(--ink-2); cursor: pointer; }
  .row-wrap:hover .more, .more[aria-expanded='true'], .more:focus-visible { display: grid; }
  .row-menu { position: absolute; right: 8px; top: 40px; z-index: 5; display: grid; min-width: 160px; padding: 4px; border-radius: var(--r-md);
    background: var(--surface-2); box-shadow: 0 8px 24px rgb(0 0 0 / 0.35); }
  .row-menu button { display: flex; align-items: center; gap: 8px; padding: 8px 10px; border: 0; border-radius: var(--r-sm); background: none;
    color: var(--ink); font: inherit; text-align: left; cursor: pointer; }
  .row-menu button:hover { background: var(--surface-3, var(--surface)); }
  .row-menu .danger { color: var(--danger); }
  .compose-panel button:disabled { opacity: .5; }
  .compose-panel a { color: var(--accent); text-decoration: none; font-size: var(--fs-sm); }
  .here { min-width: 0; display: flex; align-items: flex-start; gap: 6px; padding: 4px 12px 12px; overflow-x: auto; border-bottom: 1px solid var(--line); scrollbar-width: none; }
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
  .row.on .body, .row-wrap:has(+ .row-wrap .row.on) .body { border-bottom-color: transparent; }
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
