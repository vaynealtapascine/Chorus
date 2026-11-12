<script lang="ts">
  // New message (Figma F5.18) and New shared space (F5.10), as pages over the chat: things to start,
  // our members and our friends. One tap on a person opens that chat as the default speaker (the face
  // at the top changes who); holding or right-clicking a member picks several for a group; a friend's
  // lock starts a private chat as the speaker (D-098). A shared space is friends first, then a name (D-099).
  import { untrack } from 'svelte';
  import { core } from '../core';
  import { channels, members, selfMember, spaces, type MemberRow } from '../data';
  import { existingMemberDm, existingPrivate } from '../chat-manage';
  import { bodyPresence, frontLayers } from '../front-view';
  import { canWriteAs } from '../posts';
  import { memberHandles, shownHandle } from '../handles';
  import { accountName, connections, createShared, openDm, type SpaceAccount } from '../spaces';
  import { frontOf, refreshFront } from '../friend-fronts.svelte';
  import { friendLine, friendList, memberLine, memberList, selectedLabel, spaceName, toggle } from '../new-chat';
  import { longPress } from '../long-press';
  import { sync, type Projection } from '../sync/client';
  import AvatarImage from './AvatarImage.svelte';
  import ChannelEditor from './ChannelEditor.svelte';
  import Icon from './Icon.svelte';
  import Sheet from './Sheet.svelte';
  import EmptyState from './kit/EmptyState.svelte';
  import SectionLabel from './kit/SectionLabel.svelte';

  let { projection, dark, onclose, start = 'message' }: {
    projection: Projection; dark: boolean; onclose: () => void; start?: 'message' | 'space';
  } = $props();

  let page = $state<'message' | 'space' | 'channel'>(untrack(() => start));
  const self = $derived(selfMember(projection));
  const home = $derived(spaces(projection).find((s) => s.kind === 'internal'));
  const own = $derived(members(projection).filter((m) => !m.deleted && !m.archived && canWriteAs(projection, m.id, sync.accountId))
    .sort((a, b) => (a.display_name ?? a.name).localeCompare(b.display_name ?? b.name)));
  const nameOf = (m: MemberRow) => m.display_name ?? m.name;
  const systemName = $derived(String(projection.rows.system?.[sync.accountId]?.fields.name ?? '').trim());
  const ownHandles = $derived(memberHandles(projection, sync.accountId));

  // who is here now, the primary one first; a person writes as themself
  const presence = $derived(bodyPresence(projection, sync.accountId).filter((e) => e.subject_type === 'member'));
  const here = $derived([...presence].sort((a, b) => Number(!!b.is_primary) - Number(!!a.is_primary)).map((e) => e.subject_id));
  const layerNames = $derived(new Map(frontLayers(projection, sync.accountId).map((l) => [l.id, l.name])));
  let chosenSpeaker = $state<string | null>(null);
  const speaker = $derived(own.find((m) => m.id === chosenSpeaker) ?? (self ?? own.find((m) => m.id === here[0]) ?? own[0]));
  let speakerSheet = $state(false);

  let friends = $state<SpaceAccount[] | null>(null);
  let offline = $state(false);
  $effect(() => {
    connections().then((a) => (friends = a)).catch(() => { friends = []; offline = true; });
  });
  // who is fronting at each friend, as far as they share it
  $effect(() => { for (const a of friends ?? []) void refreshFront(a.id); });

  let searching = $state(false);
  let query = $state('');
  let picked = $state<string[]>([]);
  let chosenFriends = $state<string[]>([]);
  let naming = $state(false);
  let name = $state('');
  let busy = $state(false);
  let error = $state('');

  const people = $derived(self ? [] : memberList(own, speaker?.id ?? null, here, query, (m) => shownHandle(m, ownHandles)));
  const accounts = $derived(friendList(friends ?? [], query));
  const chosen = $derived(chosenFriends.flatMap((id) => (friends ?? []).filter((a) => a.id === id)));
  const go = (hash: string) => { location.hash = hash; onclose(); };
  const open = (id: string) => go(`#/chat/${id}`);

  function layerOf(m: MemberRow): string | undefined {
    const e = presence.find((x) => x.subject_id === m.id);
    return e ? layerNames.get(e.level)?.toLowerCase() : undefined;
  }
  function holdMember(id: string) { picked = toggle(picked, id); }

  /** Wait until a condition on the live projection (`sync.projection()`) holds, or give up. */
  function until(ok: () => boolean, ms = 15_000): Promise<boolean> {
    return new Promise((resolve) => {
      if (ok()) return resolve(true);
      const stop = sync.subscribe(() => { if (ok()) { stop(); clearTimeout(t); resolve(true); } });
      const t = setTimeout(() => { stop(); resolve(ok()); }, ms);
    });
  }

  /** A chat between our members: the speaker and whoever is picked (or tapped). */
  function membersChat(who: MemberRow[]) {
    if (!home || !speaker) return;
    const ids = [...new Set([speaker.id, ...who.map((m) => m.id)])];
    if (ids.length < 2) return;
    const found = existingMemberDm(channels(projection), home.id, ids);
    if (found) return open(found.id);
    const id = sync.newId();
    const label = ids.map((x) => own.find((m) => m.id === x)).filter((m): m is MemberRow => !!m).map(nameOf).join(' & ');
    sync.create('channel.create', `space:${home.id}`, id, { space_id: home.id, kind: 'member_dm', name: label, member_ids: [...ids].sort() });
    open(id);
  }

  async function messageFriend(a: SpaceAccount, keepPrivate: boolean) {
    if (busy) return;
    busy = true; error = '';
    try {
      const space = await openDm(a.id);
      const asMember = !self && keepPrivate ? speaker?.id : null;
      if (!asMember) return go(`#/chat/space:${space}`);
      // a private chat lives in the DM (D-098): once the DM has synced, reuse ours or make one
      const live = () => sync.projection() ?? projection;
      if (!(await until(() => spaces(live()).some((s) => s.id === space)))) { error = 'Still on its way. Try again.'; return; }
      const now = live();
      const ours = new Set(members(now).map((m) => m.id));
      const found = existingPrivate(channels(now), space, asMember, (id) => ours.has(id));
      if (found) return open(found.id);
      const id = sync.newId();
      sync.create('channel.create', `space:${space}`, id, { space_id: space, kind: 'member_dm', name: speaker ? nameOf(speaker) : 'Private', member_ids: [asMember] });
      open(id);
    } catch (e) {
      error = e instanceof Error ? e.message : String(e);
    } finally {
      busy = false;
    }
  }

  async function makeSpace() {
    if (busy || !name.trim() || !chosenFriends.length) return;
    busy = true; error = '';
    try {
      go(`#/chat/space:${await createShared(name.trim(), chosenFriends)}`);
    } catch (e) {
      error = e instanceof Error ? e.message : String(e);
      busy = false;
    }
  }
  function nameIt() { if (!name.trim()) name = spaceName(systemName, chosen); naming = true; }
  /** Back: clear a pick, then a search, then leave (a shared space goes back to the message page). */
  function back() {
    if (picked.length) picked = [];
    else if (searching) { searching = false; query = ''; }
    else if (page === 'space' && start !== 'space') page = 'message';
    else onclose();
  }
  const title = $derived(page === 'space' ? 'New shared space' : picked.length ? 'New group' : 'New message');
  const subtitle = $derived(page === 'space' ? selectedLabel(chosen) : picked.length ? `${picked.length} selected` : null);
  const face = (m: MemberRow) => core.adaptColor(m.color, dark);
</script>

<svelte:window onkeydown={(e) => { if (e.key === 'Escape' && page !== 'channel' && !speakerSheet && !naming) back(); }} />
{#if page === 'channel' && home}
  <ChannelEditor {projection} {dark} spaceId={home.id} spaceKind="internal" onclose={onclose} onsaved={(id) => (location.hash = `#/chat/${id}`)} />
{:else}
<div class="page" role="dialog" aria-modal="true" aria-label={title}>
  <div class="column">
    <header>
      <button class="icon" onclick={back} aria-label="Back"><Icon name="back" size={22} /></button>
      <span class="titles"><h2>{title}</h2>{#if subtitle}<span>{subtitle}</span>{/if}</span>
      {#if page === 'message' && !self && own.length > 1 && speaker}
        {@const c = face(speaker)}
        <button class="icon" onclick={() => (speakerSheet = true)} aria-label="Speaking as {nameOf(speaker)}. Change" title="Speaking as {nameOf(speaker)}">
          <span class="avatar sm" style="background: {c.ring}; color: {c.avatar_ink}"><AvatarImage hash={speaker.avatar_blob} glyph={speaker.sigils[0] ?? speaker.name[0]} name={nameOf(speaker)} /></span>
        </button>
      {/if}
      <button class="icon" onclick={() => { searching = !searching; if (!searching) query = ''; }} aria-label={searching ? 'Close search' : 'Search'}><Icon name={searching ? 'close' : 'search'} size={22} /></button>
    </header>

    {#if page === 'space' && chosen.length}
      <div class="chips">
        {#each chosen as a (a.id)}
          <button class="chip" onclick={() => (chosenFriends = toggle(chosenFriends, a.id))} aria-label="Remove {accountName(a)}">
            <span class="avatar xs"><AvatarImage hash={a.avatar_blob} glyph={accountName(a).replace('@', '')[0]?.toUpperCase() ?? '?'} name={accountName(a)} /></span>{accountName(a)}<Icon name="close" size={14} />
          </button>
        {/each}
      </div>
    {/if}
    {#if searching || page === 'space'}
      <label class="search"><Icon name="search" size={18} />
        <!-- svelte-ignore a11y_autofocus -->
        <input bind:value={query} autofocus={searching} placeholder={self || page === 'space' ? (self ? 'Search friends' : 'Search friends’ systems') : 'Search members and friends'} aria-label="Search" /></label>
    {/if}

    <div class="scroll">
      {#if page === 'message'}
        {#if !picked.length && !query.trim()}
          {#if !self && home}{@render action('users', 'New internal group', () => (page = 'channel'))}{/if}
          {@render action('globe', 'New shared space', () => (page = 'space'))}
          {@render action('user-plus', self ? 'Message someone new' : 'Message another system', () => go('#/people'))}
        {/if}
        {#if !self}
          <SectionLabel label={systemName || 'Members'} />
          {#each people as m (m.id)}
            {@const here = presence.some((e) => e.subject_id === m.id)}
            {@render contact(nameOf(m), memberLine(layerOf(m), m.pronouns), here, picked.length ? picked.includes(m.id) : null,
              () => (picked.length ? holdMember(m.id) : membersChat([m])), () => holdMember(m.id), face(m), m.avatar_blob, m.sigils[0] ?? m.name[0])}
          {/each}
        {/if}
        {#if !picked.length}
          <SectionLabel label={self ? 'Friends' : 'Friends’ systems'} />
          {#each accounts as a (a.id)}
            {@render contact(accountName(a), friendLine(a, frontOf(a.id)?.map((m) => m.name)), false, null, () => void messageFriend(a, false), undefined, undefined, a.avatar_blob,
              accountName(a).replace('@', '')[0]?.toUpperCase() ?? '?', !self && speaker ? { label: `Private chat with ${accountName(a)} as ${nameOf(speaker)}`, run: () => void messageFriend(a, true) } : undefined)}
          {/each}
          {#if !accounts.length}
            {#if offline}<EmptyState icon="cloud-off" label="Offline" />
            {:else if friends && !query.trim()}<EmptyState icon="friends" label="No friends yet" action="Friends" href="#/people" />{/if}
          {/if}
        {/if}
      {:else}
        <SectionLabel label={self ? 'Friends' : 'Friends’ systems'} />
        {#each accounts as a (a.id)}
          {@render contact(accountName(a), a.kind === 'system' ? 'a system' : 'one person', false, chosenFriends.includes(a.id),
            () => (chosenFriends = toggle(chosenFriends, a.id)), undefined, undefined, a.avatar_blob, accountName(a).replace('@', '')[0]?.toUpperCase() ?? '?')}
        {/each}
        {#if !accounts.length}
          {#if offline}<EmptyState icon="cloud-off" label="Offline" />
          {:else if friends && !query.trim()}<EmptyState icon="friends" label="No friends yet" action="Friends" href="#/people" />{/if}
        {/if}
      {/if}
      {#if error}<p class="error" role="alert">{error}</p>{/if}
    </div>

    {#if page === 'message' && picked.length}
      <button class="next" disabled={busy} onclick={() => membersChat(own.filter((m) => picked.includes(m.id)))} aria-label="Start group message"><Icon name="chevron-right" size={24} /></button>
    {:else if page === 'space' && chosen.length}
      <button class="next" disabled={busy} onclick={nameIt} aria-label="Name the space"><Icon name="chevron-right" size={24} /></button>
    {/if}
  </div>
</div>
{/if}

{#snippet action(icon: string, label: string, run: () => void)}
  <button class="row action" onclick={run}><span class="disc"><Icon name={icon} size={20} /></span><span class="label">{label}</span></button>
{/snippet}
{#snippet contact(label: string, line: string | null, accent: boolean, selected: boolean | null, run: () => void, hold: (() => void) | undefined,
  c: { ring: string; avatar_ink: string } | undefined, blob: string | null | undefined, glyph: string, trailing?: { label: string; run: () => void })}
  <div class="row-wrap">
    <button class="row" role={selected === null ? undefined : 'checkbox'} aria-checked={selected ?? undefined} disabled={busy} onclick={run}
      oncontextmenu={(e) => { if (hold) { e.preventDefault(); hold(); } }} use:longPress={{ enabled: !!hold, onpress: () => hold?.() }}>
      <span class="avatar" style={c ? `background: ${c.ring}; color: ${c.avatar_ink}` : ''}><AvatarImage hash={blob} {glyph} name={label} /></span>
      <span class="who"><strong>{label}</strong>{#if line}<small class:accent>{line}</small>{/if}</span>
      {#if selected !== null}<span class="check" class:on={selected}>{#if selected}<Icon name="check" size={16} />{/if}</span>{/if}
    </button>
    {#if trailing}<button class="lock" onclick={trailing.run} disabled={busy} aria-label={trailing.label} title={trailing.label}><Icon name="lock" size={18} /></button>{/if}
  </div>
{/snippet}

{#if speakerSheet}
  <Sheet title="Send message as…" onclose={() => (speakerSheet = false)}>
    <div class="senders" role="radiogroup" aria-label="Speaking as">
      {#each own as m (m.id)}
        {@const c = face(m)}
        <button class="row" role="radio" aria-checked={m.id === speaker?.id} onclick={() => { chosenSpeaker = m.id; picked = picked.filter((p) => p !== m.id); speakerSheet = false; }}>
          <span class="avatar" style="background: {c.ring}; color: {c.avatar_ink}"><AvatarImage hash={m.avatar_blob} glyph={m.sigils[0] ?? m.name[0]} name={nameOf(m)} /></span>
          <span class="who"><strong>{nameOf(m)}</strong>{#if memberLine(layerOf(m), m.pronouns)}<small>{memberLine(layerOf(m), m.pronouns)}</small>{/if}</span>
          {#if m.id === speaker?.id}<span class="check on"><Icon name="check" size={16} /></span>{/if}
        </button>
      {/each}
    </div>
  </Sheet>
{/if}
{#if naming}
  <Sheet title="Name" subtitle={selectedLabel(chosen)} onclose={() => { if (!busy) naming = false; }}>
    <!-- svelte-ignore a11y_autofocus -->
    <input class="name" bind:value={name} maxlength="80" autofocus aria-label="Name" onkeydown={(e) => { if (e.key === 'Enter') void makeSpace(); }} />
    {#if error}<p class="error" role="alert">{error}</p>{/if}
    {#snippet footer()}<button class="create" disabled={busy || !name.trim() || !chosen.length} onclick={() => void makeSpace()}>Create</button>{/snippet}
  </Sheet>
{/if}

<style>
  .page { position: fixed; inset: 0; z-index: 35; background: var(--bg); color: var(--ink); display: grid; justify-items: center; }
  .column { position: relative; width: min(100%, 640px); height: 100%; display: flex; flex-direction: column; min-height: 0; background: var(--bg); }
  @media (min-width: 641px) { .column { border-inline: 1px solid var(--line); } }
  header { display: flex; align-items: center; gap: 4px; padding: 6px 8px; }
  .titles { flex: 1; min-width: 0; display: grid; }
  h2 { margin: 0; font-size: 19px; font-weight: 600; line-height: 24px; }
  .titles span { color: var(--ink-3); font-size: 13px; }
  .icon { width: 44px; height: 44px; display: grid; place-items: center; border: 0; border-radius: 50%; background: none; color: var(--ink-2); cursor: pointer; }
  .icon:hover { background: var(--surface-2); }
  .search { display: flex; align-items: center; gap: 8px; margin: 4px 16px 8px; padding: 0 14px; min-height: 44px; border-radius: var(--r-full); background: var(--surface-2); color: var(--ink-3); }
  .search:focus-within { box-shadow: 0 0 0 2px var(--accent); }
  .search input { flex: 1; min-width: 0; border: 0; outline: 0; background: none; color: var(--ink); font: inherit; font-size: 15px; }
  .chips { display: flex; flex-wrap: wrap; gap: 8px; padding: 4px 16px 0; }
  .chip { display: inline-flex; align-items: center; gap: 6px; min-height: 36px; padding: 0 10px 0 4px; border: 0; border-radius: 20px; background: var(--surface-2); color: var(--ink);
    font: inherit; font-size: 13px; font-weight: 600; cursor: pointer; }
  .scroll :global(.section-label) { padding-inline: 16px; }
  .scroll { flex: 1; min-height: 0; overflow-y: auto; padding-bottom: 96px; }
  .row-wrap { position: relative; }
  .row { display: flex; align-items: center; gap: 14px; width: 100%; min-height: 56px; padding: 8px 16px; border: 0; background: none; color: var(--ink); font: inherit; text-align: left; cursor: pointer; user-select: none; -webkit-touch-callout: none; }
  .row:hover:not(:disabled) { background: var(--surface); }
  .row:disabled { cursor: default; }
  .row-wrap .row { padding-right: 60px; }
  .row.action { min-height: 60px; }
  .disc { width: 40px; height: 40px; flex: none; display: grid; place-items: center; border-radius: 50%; background: var(--accent-soft); color: var(--accent); }
  .label { color: var(--accent); font-size: 15px; font-weight: 500; }
  .avatar { width: 40px; height: 40px; flex: none; border-radius: 50%; display: grid; place-items: center; overflow: hidden; background: var(--surface-3, var(--surface-2)); color: var(--ink-2); font-weight: 700; }
  .avatar :global(img) { width: 100%; height: 100%; object-fit: cover; }
  .avatar.sm { width: 32px; height: 32px; font-size: 14px; }
  .avatar.xs { width: 26px; height: 26px; font-size: 12px; }
  .who { flex: 1; min-width: 0; display: grid; }
  .who strong { font-size: 15px; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .who small { color: var(--ink-3); font-size: 12px; line-height: 16px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .who small.accent { color: var(--accent); }
  .check { width: 26px; height: 26px; flex: none; display: grid; place-items: center; border-radius: 50%; border: 1.5px solid var(--ink-3); color: var(--on-accent); }
  .check.on { background: var(--accent); border-color: var(--accent); }
  .row[role='radio'] .check { border: 0; }
  .lock { position: absolute; right: 8px; top: 50%; translate: 0 -50%; width: 44px; height: 44px; display: grid; place-items: center; border: 0; border-radius: 50%; background: none; color: var(--ink-3); cursor: pointer; }
  .lock:hover:not(:disabled) { background: var(--surface-2); color: var(--ink); }
  .next { position: absolute; right: 16px; bottom: calc(16px + env(safe-area-inset-bottom)); width: 56px; height: 56px; display: grid; place-items: center; border: 0; border-radius: 50%;
    background: var(--accent); color: var(--on-accent); box-shadow: var(--shadow-pop); cursor: pointer; }
  .next:disabled { background: var(--surface-3, var(--surface-2)); color: var(--ink-3); box-shadow: none; cursor: default; }
  .senders { display: grid; }
  .error { margin: 8px 16px; color: var(--danger); font-size: 14px; }
  .name { font: inherit; font-size: 16px; color: var(--ink); background: var(--surface-2); border: 1px solid var(--line); border-radius: 12px; padding: 12px 14px; }
  .create { width: 100%; min-height: 44px; border: 0; border-radius: 14px; background: var(--accent); color: var(--on-accent); font: inherit; font-size: 15px; font-weight: 600; cursor: pointer; }
  .create:disabled { background: var(--surface-3, var(--surface-2)); color: var(--ink-3); cursor: default; }
</style>
