<script lang="ts">
  // The space part of chat info (D-098, D-099): a group's name and description, its people with
  // their roles, Add / Remove, Leave or Delete group; in a DM, the private chats kept to one of
  // our members; in our own space, who a channel is kept to.
  import { core } from '../core';
  import { channels, members, selfMember, type ChannelRow, type MemberRow, type SpaceRow } from '../data';
  import { canEditSpace, canInvite, existingPrivate, privateSides, roleIn } from '../chat-manage';
  import { canWriteAs } from '../posts';
  import { accountName, addToSpace, connections, leaveSpace, type SpaceAccount, type SpaceInfo } from '../spaces';
  import { sync, type Projection } from '../sync/client';
  import AvatarImage from './AvatarImage.svelte';
  import Icon from './Icon.svelte';

  let { projection, dark, space, channel, info, people, speaker, isShown, onchanged, oneditchannel, oneditgroup }: {
    projection: Projection; dark: boolean; space: SpaceRow; channel: ChannelRow | undefined; info: SpaceInfo | undefined;
    /** Names for members, ours and other accounts' (author cards). */
    people: Map<string, MemberRow>;
    /** Who speaks here now: the first choice for a new private chat. */
    speaker: string | null;
    /** Whether a chat may be shown now (chat-visible.ts). */
    isShown: (c: ChannelRow) => boolean;
    onchanged: () => void; oneditchannel?: () => void; oneditgroup?: () => void;
  } = $props();

  const scope = $derived(`space:${space.id}`);
  const me = sync.accountId;
  const role = $derived(roleIn(projection.rows.space_member, space.id, info, me));
  const editable = $derived(canEditSpace(space.kind, role));
  const owner = $derived(canInvite(space.kind, role));
  const roleOf = (account: string) => {
    if (account === info?.owner_account_id) return 'owner';
    const r = projection.rows.space_member?.[`${space.id}|${account}`]?.fields.role;
    return typeof r === 'string' ? r : 'member';
  };
  const ROLE_NAMES: Record<string, string> = { owner: 'Owner', admin: 'Admin', member: 'Member', read_only: 'Read-only' };
  const customRoles = $derived.by(() => {
    const raw = projection.rows.space?.[space.id]?.fields.roles;
    return Array.isArray(raw) ? raw.filter((r): r is { id: string; name: string } => !!r && typeof r === 'object' && typeof (r as { id?: unknown }).id === 'string') : [];
  });
  const roleLabel = (r: string) => ROLE_NAMES[r] ?? customRoles.find((c) => c.id === r)?.name ?? r;
  let error = $state('');

  // ── people ──
  let adding = $state<SpaceAccount[] | null>(null);
  let chosen = $state<string[]>([]);
  let busy = $state(false);
  async function startAdd() {
    error = '';
    try {
      const inside = new Set(info?.accounts.map((a) => a.id));
      adding = (await connections()).filter((a) => !inside.has(a.id));
      chosen = [];
    } catch { error = "Friends couldn't be loaded. Try again when you're online."; }
  }
  async function add() {
    if (!chosen.length || busy) return;
    busy = true; error = '';
    try { await addToSpace(space.id, chosen); adding = null; onchanged(); }
    catch (e) { error = e instanceof Error ? e.message : String(e); }
    finally { busy = false; }
  }
  function remove(a: SpaceAccount) {
    if (!confirm(`Remove ${accountName(a)} from ${space.name}? They stop seeing it; you can add them back later.`)) return;
    sync.create('space.leave', scope, space.id, { account_id: a.id });
    setTimeout(onchanged, 600);
  }
  function setRole(a: SpaceAccount, r: string) {
    sync.create('space.set_role', scope, space.id, { account_id: a.id, role: r });
  }
  async function leave() {
    const what = space.kind === 'dm' ? 'this conversation' : space.name;
    if (!confirm(`Leave ${what}? You can be added back later.`)) return;
    try { await leaveSpace(space.id); location.hash = '#/chat'; onchanged(); }
    catch (e) { error = e instanceof Error ? e.message : String(e); }
  }
  function deleteGroup() {
    if (!confirm(`Delete ${space.name} for everyone in it? Its chats leave everyone's list. This is kept in the history and can be restored.`)) return;
    sync.create('space.delete', scope, space.id, {});
    location.hash = '#/chat';
  }

  // ── private chats in a DM (D-098) ──
  const ours = $derived(new Set(members(projection).map((m) => m.id)));
  const own = (id: string) => ours.has(id);
  const writers = $derived(members(projection).filter((m) => !m.deleted && !m.archived && canWriteAs(projection, m.id, me)));
  const privates = $derived(space.kind === 'dm'
    ? channels(projection, space.id).filter((c) => !c.archived && c.kind === 'member_dm' && isShown(c))
    : []);
  let privateAs = $state('');
  function startPrivate() {
    const who = privateAs || speaker || writers[0]?.id;
    if (!who) return;
    const found = existingPrivate(channels(projection), space.id, who, own);
    if (found) { location.hash = `#/chat/${found.id}`; return; }
    const id = sync.newId();
    const m = people.get(who);
    sync.create('channel.create', scope, id, { space_id: space.id, kind: 'member_dm', name: m?.display_name ?? m?.name ?? 'Private', member_ids: [who] });
    location.hash = `#/chat/${id}`;
  }
  const nameOf = (id: string) => people.get(id)?.display_name ?? people.get(id)?.name ?? 'Someone';
  const kept = $derived(space.kind === 'internal' && channel?.kind === 'text' ? channel.member_ids ?? [] : []);
  const sides = $derived(channel ? privateSides(channel, space, own) : null);
  const isSystem = $derived(!selfMember(projection));
</script>

{#if space.kind === 'shared'}
  {#if space.description}<p class="desc">{space.description}</p>{/if}
  {#if editable && oneditgroup}<button class="link" onclick={oneditgroup}><Icon name="edit" size={16} /> Edit group</button>{/if}

  <h3>{info?.accounts.length ?? 0} {info?.accounts.length === 1 ? 'person' : 'people'}</h3>
  <ul class="people" aria-label="People in {space.name}">
    {#if owner}
      <li><button class="add" onclick={() => void startAdd()}><span class="face plus"><Icon name="plus" size={18} /></span>Add people</button></li>
    {/if}
    {#if adding}
      <li class="adding">
        {#each adding as a (a.id)}
          <label><input type="checkbox" checked={chosen.includes(a.id)} onchange={(e) => (chosen = e.currentTarget.checked ? [...chosen, a.id] : chosen.filter((x) => x !== a.id))} /> {accountName(a)}</label>
        {:else}
          <p class="hint">Everyone you're connected to is already here. Follow someone on People to add them.</p>
        {/each}
        <div class="row-actions"><button class="ghost" onclick={() => (adding = null)}>Cancel</button><button class="primary" disabled={!chosen.length || busy} onclick={() => void add()}>Add</button></div>
      </li>
    {/if}
    {#each info?.accounts ?? [] as a (a.id)}
      {@const r = roleOf(a.id)}
      <li class="person">
        <span class="face">{accountName(a).replace('@', '')[0]?.toUpperCase()}</span>
        <span class="who">{accountName(a)}{#if a.id === me}<small> (you)</small>{/if}<small>{a.kind === 'person' ? 'person' : 'system'}</small></span>
        {#if editable && a.id !== info?.owner_account_id && a.id !== me}
          <select aria-label="Role of {accountName(a)}" value={r} onchange={(e) => setRole(a, e.currentTarget.value)}>
            <option value="member">Member</option><option value="read_only">Read-only</option><option value="admin">Admin</option>
            {#each customRoles as c (c.id)}<option value={c.id}>{c.name}</option>{/each}
          </select>
        {:else}
          <span class="role">{roleLabel(r)}</span>
        {/if}
        {#if owner && a.id !== me}<button class="x" aria-label="Remove {accountName(a)}" title="Remove from group" onclick={() => remove(a)}><Icon name="close" size={16} /></button>{/if}
      </li>
    {/each}
  </ul>
{:else if space.kind === 'dm'}
  {#if sides?.mine.length}
    <p class="note"><Icon name="lock" size={16} /> Private to {sides.mine.map(nameOf).join(' & ')}. It shows only while they're fronting or co-conscious, and only they write here. This is privacy within your system, not a lock: you all share this account.</p>
  {:else if sides?.theirs.length}
    <p class="note"><Icon name="lock" size={16} /> A private chat with {sides.theirs.map(nameOf).join(' & ')}: the rest of their system doesn't see it.</p>
  {/if}
  {#if privates.length}
    <h3>Private chats</h3>
    <ul class="people">
      {#each privates as c (c.id)}
        {@const s = privateSides(c, space, own)}
        <li><a class="person link-row" href="#/chat/{c.id}" class:on={c.id === channel?.id}><span class="face"><Icon name="lock" size={16} /></span>
          <span class="who">{s?.mine.length ? `As ${s.mine.map(nameOf).join(' & ')}` : `With ${s?.theirs.map(nameOf).join(' & ') ?? c.name}`}</span></a></li>
      {/each}
    </ul>
  {/if}
  {#if isSystem && writers.length && !sides}
    <div class="private-start">
      <span>Start a private chat as</span>
      <select aria-label="Private chat as" value={privateAs || speaker || ''} onchange={(e) => (privateAs = e.currentTarget.value)}>
        {#each writers as m (m.id)}<option value={m.id}>{m.display_name ?? m.name}</option>{/each}
      </select>
      <button class="primary" onclick={startPrivate}>Start</button>
    </div>
  {/if}
{:else if kept.length}
  <h3>Kept to</h3>
  <ul class="people">
    {#each kept as id (id)}
      {@const m = people.get(id)}
      {@const c = core.adaptColor(m?.color ?? '#A09184', dark)}
      <li class="person"><span class="face" style="background: {c.ring}; color: {c.avatar_ink}"><AvatarImage hash={m?.avatar_blob} glyph={m?.sigils[0] ?? nameOf(id)[0]} name={nameOf(id)} /></span><span class="who">{nameOf(id)}</span></li>
    {/each}
  </ul>
{/if}

{#if oneditchannel && channel?.kind !== 'thread' && !(space.kind === 'dm' && channel?.kind === 'text')}
  <button class="link" onclick={oneditchannel}><Icon name="edit" size={16} /> {channel?.kind === 'member_dm' ? 'Edit chat' : 'Edit channel'}</button>
{/if}
{#if error}<p class="error" role="alert">{error}</p>{/if}
{#if space.kind !== 'internal' && !info?.guest}
  <div class="leave">
    {#if space.kind === 'shared' && role === 'owner'}
      <button class="danger" onclick={deleteGroup}>Delete group</button>
    {:else}
      <button class="danger" onclick={() => void leave()}>{space.kind === 'dm' ? 'Leave conversation' : 'Leave group'}</button>
    {/if}
  </div>
{/if}

<style>
  h3 { margin: 0; padding: 12px 16px 4px; font-size: 12px; font-weight: 600; color: var(--accent); text-align: left; }
  .desc { margin: 4px 16px 8px; color: var(--ink-2); font-size: 14px; text-align: left; white-space: pre-wrap; overflow-wrap: anywhere; }
  .note { display: flex; gap: 8px; align-items: flex-start; margin: 8px 16px; padding: 10px; border-radius: var(--r-md); background: var(--surface-2); color: var(--ink-2); font-size: 13px; text-align: left; }
  .link { display: flex; align-items: center; gap: 8px; margin: 4px 8px; padding: 8px; border: 0; border-radius: var(--r-sm); background: none; color: var(--accent); font: inherit; font-size: 14px; cursor: pointer; }
  .link:hover { background: var(--surface-2); }
  .people { list-style: none; margin: 0; padding: 0 8px; display: grid; gap: 1px; }
  .person, .add { display: flex; align-items: center; gap: 10px; padding: 6px 8px; border-radius: var(--r-sm); color: var(--ink); text-decoration: none; text-align: left; }
  .add { width: 100%; border: 0; background: none; font: inherit; color: var(--accent); cursor: pointer; }
  .add:hover, .link-row:hover, .link-row.on { background: var(--surface-2); }
  .face { width: 34px; height: 34px; flex: none; border-radius: 50%; display: grid; place-items: center; overflow: hidden; background: var(--surface-3, var(--surface-2)); color: var(--ink-2); font-weight: 700; font-size: 14px; }
  .face.plus { background: var(--accent-soft); color: var(--accent); }
  .who { flex: 1; min-width: 0; display: grid; font-size: 14px; overflow-wrap: anywhere; }
  .who small { color: var(--ink-3); font-size: 12px; }
  .role { color: var(--ink-3); font-size: 12px; }
  select { font: inherit; font-size: 12px; color: var(--ink); background: var(--surface-2); border: 1px solid var(--line); border-radius: var(--r-sm); padding: 2px 4px; }
  .x { width: 28px; height: 28px; display: grid; place-items: center; border: 0; border-radius: 50%; background: none; color: var(--ink-3); cursor: pointer; }
  .x:hover { background: var(--surface-2); color: var(--danger); }
  .adding { display: grid; gap: 4px; padding: 8px; border-radius: var(--r-md); background: var(--surface-2); font-size: 14px; text-align: left; }
  .row-actions { display: flex; justify-content: flex-end; gap: 8px; }
  .ghost { border: 0; background: none; color: var(--accent); font: inherit; cursor: pointer; }
  .primary { border: 0; border-radius: var(--r-full); padding: 6px 14px; background: var(--accent); color: var(--on-accent); font: inherit; font-weight: 600; cursor: pointer; }
  .primary:disabled { opacity: .5; cursor: default; }
  .private-start { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin: 12px 16px; font-size: 13px; color: var(--ink-2); }
  .leave { padding: 16px; border-top: 1px solid var(--line); margin-top: 12px; }
  .danger { width: 100%; border: 0; border-radius: var(--r-md); padding: 10px; background: none; color: var(--danger); font: inherit; cursor: pointer; }
  .danger:hover { background: var(--surface-2); }
  .hint { margin: 0; color: var(--ink-3); font-size: 13px; }
  .error { margin: 8px 16px; color: var(--danger); font-size: 13px; }
</style>
