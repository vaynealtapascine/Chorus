<script lang="ts">
  // New chat (Telegram's pencil; Android's New chat sheet): a channel in our own space, a chat
  // between our members, a DM with a friend (or a private chat with them for one member, D-098),
  // or a new group with friends (a shared space, D-099).
  import { untrack } from 'svelte';
  import { core } from '../core';
  import { channels, fuzzy, members, selfMember, spaces, type MemberRow } from '../data';
  import { existingMemberDm, existingPrivate } from '../chat-manage';
  import { bodyPresence } from '../front-view';
  import { canWriteAs } from '../posts';
  import { accountName, connections, createShared, openDm, type SpaceAccount } from '../spaces';
  import { sync, type Projection } from '../sync/client';
  import AvatarImage from './AvatarImage.svelte';
  import ChannelEditor from './ChannelEditor.svelte';
  import Icon from './Icon.svelte';

  let { projection, dark, onclose, start = 'choose' }: {
    projection: Projection; dark: boolean; onclose: () => void; start?: 'choose' | 'direct' | 'group';
  } = $props();

  let step = $state<'choose' | 'channel' | 'direct' | 'group'>(untrack(() => start));
  const self = $derived(selfMember(projection));
  const home = $derived(spaces(projection).find((s) => s.kind === 'internal'));
  const own = $derived(members(projection).filter((m) => !m.deleted && !m.archived && canWriteAs(projection, m.id, sync.accountId))
    .sort((a, b) => (a.display_name ?? a.name).localeCompare(b.display_name ?? b.name)));
  const nameOf = (m: MemberRow) => m.display_name ?? m.name;
  // fronting first, the primary one chosen to start with
  const fronting = $derived(bodyPresence(projection, sync.accountId).filter((e) => e.subject_type === 'member'));
  const senders = $derived([...own].sort((a, b) => rank(a.id) - rank(b.id)));
  function rank(id: string) { const i = fronting.findIndex((e) => e.subject_id === id); return i < 0 ? 999 : fronting[i].is_primary ? -1 : i; }
  let sender = $state<string | null>(null);
  const from = $derived(sender ?? self?.id ?? senders[0]?.id ?? null);

  let friends = $state<SpaceAccount[] | null>(null);
  let friendsError = $state('');
  $effect(() => {
    connections().then((a) => (friends = a)).catch(() => { friends = []; friendsError = "Friends couldn't be loaded. You need to be online to start a chat with someone."; });
  });

  let query = $state('');
  let picked = $state<string[]>([]);
  let keepPrivate = $state(false);
  let groupName = $state('');
  let groupWith = $state<string[]>([]);
  let busy = $state(false);
  let error = $state('');
  const match = (text: string) => !query.trim() || fuzzy(query, text) !== null;

  const go = (hash: string) => { location.hash = hash; onclose(); };

  /** Wait until a condition on the live projection (`sync.projection()`) holds, or give up. */
  function until(ok: () => boolean, ms = 15_000): Promise<boolean> {
    return new Promise((resolve) => {
      if (ok()) return resolve(true);
      const stop = sync.subscribe(() => { if (ok()) { stop(); clearTimeout(t); resolve(true); } });
      const t = setTimeout(() => { stop(); resolve(ok()); }, ms);
    });
  }

  function membersChat() {
    if (!home || !from) return;
    const people = [...new Set([from, ...picked])];
    if (people.length < 2) return;
    const found = existingMemberDm(channels(projection), home.id, people);
    if (found) return go(`#/chat/${found.id}`);
    const id = sync.newId();
    const name = people.map((id) => own.find((m) => m.id === id)).filter((m): m is MemberRow => !!m).map(nameOf).join(' & ');
    sync.create('channel.create', `space:${home.id}`, id, { space_id: home.id, kind: 'member_dm', name, member_ids: [...people].sort() });
    go(`#/chat/${id}`);
  }

  async function messageFriend(a: SpaceAccount) {
    if (busy) return;
    busy = true; error = '';
    try {
      const space = await openDm(a.id);
      const asMember = !self && keepPrivate ? from : null;
      if (!asMember) return go(`#/chat/space:${space}`);
      // a private chat lives in the DM (D-098): once the DM has synced, reuse ours or make one
      const live = () => sync.projection() ?? projection;
      if (!(await until(() => spaces(live()).some((s) => s.id === space)))) {
        error = 'The chat is still on its way. Try again in a moment.';
        return;
      }
      const now = live();
      const ours = new Set(members(now).map((m) => m.id));
      const found = existingPrivate(channels(now), space, asMember, (id) => ours.has(id));
      if (found) return go(`#/chat/${found.id}`);
      const id = sync.newId();
      const who = own.find((m) => m.id === asMember);
      sync.create('channel.create', `space:${space}`, id, { space_id: space, kind: 'member_dm', name: who ? nameOf(who) : 'Private', member_ids: [asMember] });
      go(`#/chat/${id}`);
    } catch (e) {
      error = e instanceof Error ? e.message : String(e);
    } finally {
      busy = false;
    }
  }

  async function makeGroup() {
    if (busy || !groupName.trim() || !groupWith.length) return;
    busy = true; error = '';
    try {
      go(`#/chat/space:${await createShared(groupName.trim(), groupWith)}`);
    } catch (e) {
      error = e instanceof Error ? e.message : String(e);
    } finally {
      busy = false;
    }
  }
  const senderName = $derived(own.find((m) => m.id === from) ? nameOf(own.find((m) => m.id === from)!) : 'them');
</script>

<svelte:window onkeydown={(e) => { if (e.key === 'Escape' && step !== 'channel') onclose(); }} />
{#if step === 'channel' && home}
  <ChannelEditor {projection} {dark} spaceId={home.id} spaceKind="internal" onclose={onclose} onsaved={(id) => (location.hash = `#/chat/${id}`)} />
{:else}
<div class="scrim" onclick={onclose} role="presentation"></div>
<div class="sheet" role="dialog" aria-modal="true" aria-label="New chat">
  <div class="top">
    {#if step !== 'choose'}<button class="icon-btn" onclick={() => { step = 'choose'; error = ''; }} aria-label="Back"><Icon name="chevron" size={20} /></button>{/if}
    <h2>{step === 'direct' ? 'New message' : step === 'group' ? 'New group' : 'New chat'}</h2>
    <button class="ghost" onclick={onclose}>Cancel</button>
  </div>

  {#if step === 'choose'}
    <div class="choices">
      {#if home && !self}
        <button class="choice" onclick={() => (step = 'channel')}><span class="ci"><Icon name="hash" size={22} /></span>
          <span><strong>New channel</strong><small>A place in your system for a topic, plans or notes</small></span></button>
      {/if}
      <button class="choice" onclick={() => (step = 'direct')}><span class="ci"><Icon name="chat" size={22} /></span>
        <span><strong>New message</strong><small>{self ? 'Write to a friend' : 'Between your members, or to a friend'}</small></span></button>
      <button class="choice" onclick={() => (step = 'group')}><span class="ci"><Icon name="users" size={22} /></span>
        <span><strong>New group</strong><small>A shared space with friends you follow or who follow you</small></span></button>
    </div>
  {:else if step === 'direct'}
    {#if !self && senders.length}
      <div class="as">
        <span class="label">Writing as</span>
        <div class="chips">
          {#each senders.slice(0, 12) as m (m.id)}
            {@const c = core.adaptColor(m.color, dark)}
            <button class="chip" class:on={m.id === from} aria-pressed={m.id === from} onclick={() => { sender = m.id; picked = picked.filter((p) => p !== m.id); }}>
              <span class="avatar sm" style="background: {c.ring}; color: {c.avatar_ink}"><AvatarImage hash={m.avatar_blob} glyph={m.sigils[0] ?? m.name[0]} name={m.name} /></span>{nameOf(m)}
            </button>
          {/each}
        </div>
      </div>
    {/if}
    <input class="search" bind:value={query} placeholder="Search" aria-label="Search members and friends" />
    {#if !self}
      <h3>Your members</h3>
      <div class="list">
        {#each own.filter((m) => m.id !== from && match(`${nameOf(m)} ${m.name} ${m.sigils.join(' ')}`)) as m (m.id)}
          {@const c = core.adaptColor(m.color, dark)}
          <label class="person">
            <input type="checkbox" checked={picked.includes(m.id)} onchange={(e) => (picked = e.currentTarget.checked ? [...picked, m.id] : picked.filter((p) => p !== m.id))} />
            <span class="avatar" style="background: {c.ring}; color: {c.avatar_ink}"><AvatarImage hash={m.avatar_blob} glyph={m.sigils[0] ?? m.name[0]} name={m.name} /></span>
            <span style="color: {c.name}">{nameOf(m)}</span>
          </label>
        {:else}<p class="hint">No members match.</p>{/each}
      </div>
      {#if picked.length}
        <button class="primary" onclick={membersChat}>{picked.length === 1 ? 'Start chat' : `Start group of ${picked.length + 1}`}</button>
      {/if}
    {/if}
    <h3>Friends</h3>
    {#if !self}
      <label class="private"><input type="checkbox" bind:checked={keepPrivate} />
        <span><strong>Private to {senderName}</strong><small>Only {senderName} sees and writes in it, while fronting or co-conscious. The rest of your system doesn't.</small></span></label>
    {/if}
    <div class="list">
      {#if friends === null}<p class="hint">Loading…</p>{/if}
      {#each (friends ?? []).filter((a) => match(`${accountName(a)} ${a.handle ?? ''}`)) as a (a.id)}
        <button class="person account" disabled={busy} onclick={() => void messageFriend(a)}>
          <span class="avatar">{accountName(a).replace('@', '')[0]?.toUpperCase()}</span>
          <span class="nm">{accountName(a)}{#if a.handle && a.display_name}<small>@{a.handle}</small>{/if}</span>
          {#if !self && keepPrivate}<Icon name="lock" size={16} />{/if}
        </button>
      {:else}
        {#if friends}<p class="hint">{friendsError || 'Follow someone on People to message them.'}</p>{/if}
      {/each}
    </div>
  {:else}
    <label class="field">Group name<input bind:value={groupName} maxlength="80" placeholder="Book club" /></label>
    <h3>Who's in it</h3>
    <div class="list">
      {#each friends ?? [] as a (a.id)}
        <label class="person">
          <input type="checkbox" checked={groupWith.includes(a.id)} onchange={(e) => (groupWith = e.currentTarget.checked ? [...groupWith, a.id] : groupWith.filter((x) => x !== a.id))} />
          <span class="avatar">{accountName(a).replace('@', '')[0]?.toUpperCase()}</span>
          <span>{accountName(a)}</span>
        </label>
      {:else}
        <p class="hint">{friends === null ? 'Loading…' : friendsError || 'Follow someone on People first: groups are with people you follow or who follow you.'}</p>
      {/each}
    </div>
    <p class="hint">Everyone in the group sees who is speaking. You can add and remove people later from the group's info.</p>
    <button class="primary" disabled={busy || !groupName.trim() || !groupWith.length} onclick={() => void makeGroup()}>Create group</button>
  {/if}
  {#if error}<p class="error" role="alert">{error}</p>{/if}
</div>
{/if}

<style>
  .scrim { position: fixed; inset: 0; background: rgb(0 0 0 / 0.45); z-index: 30; }
  .sheet { position: fixed; z-index: 31; inset: auto 0 0 0; max-height: 90vh; overflow: auto; margin: 0 auto; max-width: 480px;
    background: var(--surface); color: var(--ink); border-radius: var(--r-lg) var(--r-lg) 0 0; box-shadow: var(--shadow-pop);
    padding: 16px 16px calc(16px + env(safe-area-inset-bottom)); display: grid; gap: 10px; align-content: start; animation: rise var(--t-med, 220ms) var(--ease); }
  @media (min-width: 641px) { .sheet { inset: 8vh 0 auto 0; border-radius: var(--r-lg); } }
  @keyframes rise { from { transform: translateY(16px); opacity: 0; } }
  @media (prefers-reduced-motion: reduce) { .sheet { animation: none; } }
  .top { display: flex; align-items: center; gap: 8px; }
  h2 { flex: 1; margin: 0; font-size: var(--fs-lg); }
  h3 { margin: 6px 0 0; font-size: 12px; font-weight: 600; color: var(--accent); }
  .ghost { border: 0; background: none; color: var(--accent); font: inherit; cursor: pointer; }
  .icon-btn { width: 32px; height: 32px; display: grid; place-items: center; border: 0; border-radius: 50%; background: none; color: var(--ink-2); cursor: pointer; transform: rotate(180deg); }
  .choices { display: grid; gap: 4px; }
  .choice { display: flex; align-items: center; gap: 14px; padding: 10px 8px; border: 0; border-radius: var(--r-md); background: none; color: var(--ink); text-align: left; font: inherit; cursor: pointer; }
  .choice:hover { background: var(--surface-2); }
  .choice span:last-child { display: grid; gap: 2px; }
  .choice small, .private small, .nm small { color: var(--ink-3); font-size: var(--fs-xs); }
  .ci { width: 44px; height: 44px; flex: none; border-radius: 50%; display: grid; place-items: center; background: var(--accent-soft); color: var(--accent); }
  .as { display: grid; gap: 6px; }
  .label { font-size: 12px; color: var(--ink-3); }
  .chips { display: flex; gap: 6px; overflow-x: auto; padding-bottom: 2px; scrollbar-width: none; }
  .chip { flex: none; display: inline-flex; align-items: center; gap: 6px; padding: 4px 10px 4px 4px; border: 1px solid var(--line); border-radius: var(--r-full); background: none; color: var(--ink); font: inherit; font-size: 14px; cursor: pointer; }
  .chip.on { border-color: var(--accent); background: var(--accent-soft); }
  .search, .field input { font: inherit; font-size: 15px; color: var(--ink); background: var(--surface-2); border: 1px solid var(--line); border-radius: var(--r-full); padding: 8px 14px; }
  .field { display: grid; gap: 6px; font-size: var(--fs-sm); color: var(--ink-2); }
  .field input { border-radius: var(--r-sm); }
  .list { display: grid; gap: 1px; max-height: 300px; overflow: auto; }
  .person { display: flex; align-items: center; gap: 12px; padding: 6px 8px; border: 0; border-radius: var(--r-sm); background: none; color: var(--ink); font: inherit; text-align: left; cursor: pointer; }
  .person:hover { background: var(--surface-2); }
  .person.account .nm { flex: 1; display: grid; }
  .avatar { width: 36px; height: 36px; flex: none; border-radius: 50%; display: grid; place-items: center; overflow: hidden; background: var(--surface-3, var(--surface-2)); color: var(--ink-2); font-weight: 700; }
  .avatar.sm { width: 26px; height: 26px; font-size: 12px; }
  .private { display: flex; gap: 10px; align-items: flex-start; padding: 8px; border-radius: var(--r-md); background: var(--surface-2); cursor: pointer; }
  .private span { display: grid; gap: 2px; }
  .hint { margin: 4px 8px; color: var(--ink-3); font-size: var(--fs-sm); }
  .error { margin: 0; color: var(--danger); font-size: var(--fs-sm); }
  .primary { justify-self: end; border: 0; border-radius: var(--r-full); padding: 9px 20px; background: var(--accent); color: var(--on-accent); font: inherit; font-weight: 600; cursor: pointer; }
  .primary:disabled { opacity: .5; cursor: default; }
</style>
