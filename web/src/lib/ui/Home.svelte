<script lang="ts">
  import { core } from '../core';
  import { sync, type Projection } from '../sync/client';
  import { groups, selfMember } from '../data';
  import { doFrontSwitch, doSwitch, resumeFollowing } from '../front.svelte';
  import { frontLayers, frontList } from '../front-view';
  import FrontCard from './FrontCard.svelte';
  import Reviews from './Reviews.svelte';
  import LinkDevice from './LinkDevice.svelte';
  import type { FrontEntry, Member } from './types';
  import AvatarImage from './AvatarImage.svelte';
  import Icon from './Icon.svelte';

  let { projection, dark, onswitch }: { projection: Projection; dark: boolean; onswitch: (front?: string) => void } = $props();

  const members: Member[] = $derived(
    Object.entries(projection.rows.member ?? {})
      .filter(([, r]) => r.exists && !r.fields.deleted_at && !r.fields.archived_at && (!r.fields.created_by_account_id || r.fields.created_by_account_id === sync.accountId))
      .map(([id, r]) => ({
        id,
        name: String(r.fields.name ?? 'Unnamed'),
        pronouns: r.fields.pronouns as string | undefined,
        color: String(r.fields.color ?? '#A09184'),
        sigil: (r.fields.sigils as string[] | undefined)?.[0],
        avatarBlob: r.fields.avatar_blob as string | undefined,
      }))
      .sort((a, b) => a.name.localeCompare(b.name)),
  );
  const me = $derived(selfMember(projection));
  // the front card can show subsystems fronting as a unit, not only members
  const subjects: Member[] = $derived([
    ...members,
    ...groups(projection).map((g) => ({ id: g.id, name: g.name, color: g.color ?? '#A09184', sigil: '◌', avatarBlob: g.avatar_blob })),
  ]);
  // named fronts (D-080): Body plus the system's own fronts; legacy accounts have only Body
  const configured = $derived(!!projection.front_systems?.[sync.accountId]);
  const fronts = $derived(frontList(projection, sync.accountId));
  const layers = $derived(frontLayers(projection, sync.accountId));
  let selectedId = $state(sync.accountId);
  const selected = $derived(fronts.find((f) => f.id === selectedId) ?? fronts[0]);
  const front: FrontEntry[] = $derived(
    (selected?.entries ?? []).map((e) => ({ id: e.subject_id, level: e.layer_id, primary: e.is_primary })),
  );
  // the legacy timeline knows when Body's front began; named history is never folded on Home
  const fold = $derived(projection.fronts[sync.accountId]);
  const since = $derived.by(() => {
    if (configured) return null;
    const open = (fold?.intervals ?? []).filter((i) => i.end_at === null);
    return open.length ? Math.min(...open.map((i) => i.start_at)) : Date.now();
  });

  function switchTo(id: string) {
    const name = members.find((m) => m.id === id)?.name ?? 'someone';
    if (configured && selected) {
      doFrontSwitch(selected.id, [{ subject_type: 'member', subject_id: id, layer_id: layers[0]?.id ?? 'front', is_primary: true }], `Switched to ${name}`);
    } else {
      doSwitch([{ subject_type: 'member', subject_id: id, level: 'front', is_primary: true }], `Switched to ${name}`);
    }
  }

  let adding = $state(false);
  let newName = $state('');
  let newColor = $state('#C0694E');
  let newSigil = $state('');
  function addMember(e: SubmitEvent) {
    e.preventDefault();
    if (!newName.trim()) return;
    sync.create('member.create', sync.accountScope, sync.newId(), {
      name: newName.trim(),
      color: newColor,
      sigils: newSigil.trim() ? [newSigil.trim()] : [],
    });
    newName = '';
    newSigil = '';
    adding = false;
  }
</script>

<div class="home">
  {#if me}
    <!-- a person account: no front to track and no members to manage (D-003) -->
    <section class="person" aria-label="You">
      <span class="avatar" aria-hidden="true"><AvatarImage hash={me.avatar_blob} glyph={me.name[0]} name={me.name} /></span>
      <div>
        <h1 class="display">Hi, {me.display_name ?? me.name}</h1>
        <p class="empty">Follow friends and chat with them from <a href="#/people">People</a> and <a href="#/chat">Chat</a>.</p>
      </div>
    </section>
  {:else}
  <Reviews {projection} />
  {#if fronts.length > 1}
    <div class="front-tabs" role="tablist" aria-label="Fronts">
      {#each fronts as f (f.id)}
        <button role="tab" aria-selected={f.id === selected?.id} class:on={f.id === selected?.id} onclick={() => (selectedId = f.id)}>
          {f.name}{#if f.entries.length}<span class="count">{f.entries.length}</span>{/if}
        </button>
      {/each}
    </div>
  {/if}
  {#if selected && selected.state !== 'independent'}
    <div class="follow-state" role="status">
      {#if selected.state === 'following'}<span>Follows {selected.sourceName ?? 'another front'}</span>
      {:else if selected.state === 'overridden'}<span>Changed by hand · not following {selected.sourceName ?? 'its source'}</span><button class="ghost" onclick={() => resumeFollowing(selected.id)}>Resume following</button>
      {:else}<span>{selected.issue === 'cycle' ? 'Following loops back on itself' : 'Its source is unavailable'} · showing nothing as current</span>{/if}
    </div>
  {/if}
  <div class="front-actions"><a href="#/history"><Icon name="history" size={18} />Front history</a>{#if !me}<a href="#/fronts"><Icon name="layers" size={18} />Fronts</a>{/if}<button class="switch-button" onclick={() => onswitch(selected?.id)}><Icon name="swap" size={18} />Switch</button></div>
  <button class="card-button" onclick={() => onswitch(selected?.id)} aria-label={`Change who's in ${selected?.name ?? 'front'}`}>
    <FrontCard members={subjects} {front} {since} {dark} {layers} title={configured ? selected?.name ?? 'Current front' : 'Current front'} />
  </button>

  <section aria-label="Quick switch">
    <div class="section-head">
      <h2>Quick switch</h2>
      <button class="ghost" onclick={() => (adding = !adding)}>{adding ? 'Cancel' : 'Add member'}</button>
    </div>
    {#if adding}
      <form class="add" onsubmit={addMember}>
        <input bind:value={newSigil} placeholder="🌌" aria-label="Sigil" class="sigil" />
        <input bind:value={newName} placeholder="Name" aria-label="Name" required />
        <input type="color" bind:value={newColor} aria-label="Colour" />
        <button>Add</button>
      </form>
    {/if}
    {#if members.length === 0 && !adding}
      <p class="empty">No members yet. Add the first one to start switching.</p>
    {/if}
    <div class="grid">
      {#each members as m (m.id)}
        {@const c = core.adaptColor(m.color, dark)}
        {@const here = front.some((f) => f.id === m.id)}
        <button class="member" class:here style="--ring: {c.ring}; --avatar-ink: {c.avatar_ink}; --tint: {c.tint}" onclick={() => switchTo(m.id)}>
          <span class="avatar" aria-hidden="true"><AvatarImage hash={m.avatarBlob} glyph={m.sigil ?? m.name[0]} name={m.name} /></span>
          <span class="name" style="color: {c.name}">{m.name}</span>
        </button>
      {/each}
    </div>
  </section>
  {/if}
  <details class="device-tools"><summary>Devices and data</summary><LinkDevice />
  <section class="data-links" aria-label="Data">
    <h2>Data</h2>
    <a href="#/trash">Trash · deleted items</a>
    <a href="#/data">API tokens · overlays</a>
  </section></details>
</div>

<style>
  .front-actions { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
  .front-actions > a + a { margin-right: auto; }
  .front-tabs { display: flex; gap: 8px; overflow-x: auto; padding-bottom: 2px; }
  .front-tabs button { min-height: 40px; padding: 0 14px; border-radius: var(--r-full); border: 1px solid var(--line); background: var(--surface); color: var(--ink-2); cursor: pointer; display: flex; gap: 6px; align-items: center; white-space: nowrap; }
  .front-tabs button.on { background: var(--accent-soft); border-color: var(--accent); color: var(--ink); font-weight: 600; }
  .front-tabs .count { font-size: var(--fs-xs); color: var(--ink-3); }
  .follow-state { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; justify-content: space-between; padding: 8px 12px; border-radius: var(--r-md); background: var(--surface); color: var(--ink-2); font-size: var(--fs-sm); }
  .front-actions a { display: flex; align-items: center; gap: 8px; color: var(--ink-2); text-decoration: none; font-size: var(--fs-sm); min-height: 48px; }
  .switch-button { display: flex; align-items: center; gap: 8px; padding: 12px 20px; min-height: 48px; border: 0; border-radius: var(--r-full); background: var(--accent); color: var(--on-accent); font-weight: 700; cursor: pointer; }
  .device-tools { margin-top: 16px; border-top: 1px solid var(--line); color: var(--ink-3); padding-block: 16px; }
  .device-tools summary { cursor: pointer; font-size: var(--fs-sm); padding-block: 8px; }
  .home {
    display: grid;
    gap: var(--s-6);
  }
  .data-links { display: grid; gap: var(--s-1); justify-items: start; }
  .person { display: flex; gap: var(--s-4); align-items: center; }
  .person .avatar { width: 56px; height: 56px; flex: none; border-radius: var(--r-full); overflow: hidden; }
  .person h1 { margin: 0; font-size: var(--fs-xl, 1.5rem); }
  .person a { color: var(--accent); }
  .data-links a { color: var(--accent); text-decoration: none; font-size: var(--fs-sm); }
  .card-button {
    all: unset;
    display: block;
    cursor: pointer;
    border-radius: var(--r-lg);
  }
  .card-button:focus-visible {
    outline: 2px solid color-mix(in oklab, var(--accent) 60%, transparent);
    outline-offset: 2px;
  }
  h2 {
    font-size: var(--fs-sm);
    font-weight: 600;
    color: var(--ink-2);
  }
  .section-head {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: var(--s-3);
  }
  .ghost {
    min-height: 44px;
    background: none;
    border: 0;
    color: var(--accent);
    cursor: pointer;
    font-size: var(--fs-sm);
  }
  .add {
    display: flex;
    gap: var(--s-2);
    margin-bottom: var(--s-3);
  }
  .add input {
    font: inherit;
    color: var(--ink);
    background: var(--surface);
    border: 1px solid var(--line);
    border-radius: var(--r-sm);
    padding: var(--s-2) var(--s-3);
    min-width: 0;
    flex: 1;
  }
  .add .sigil {
    flex: 0 0 3.5em;
    text-align: center;
  }
  .add input[type='color'] {
    flex: 0 0 44px;
    padding: 2px;
  }
  .add button {
    background: var(--accent);
    color: var(--on-accent);
    border: 0;
    border-radius: var(--r-full);
    padding: 0 var(--s-4);
    cursor: pointer;
  }
  .empty {
    color: var(--ink-3);
  }
  .grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(96px, 1fr));
    gap: var(--s-3);
  }
  .member {
    display: grid;
    justify-items: center;
    gap: var(--s-2);
    padding: var(--s-3) var(--s-2);
    background: transparent;
    border: 1px solid transparent;
    border-radius: var(--r-md);
    cursor: pointer;
    transition: background var(--t-fast) var(--ease);
  }
  .member:hover,
  .member.here {
    background: var(--tint);
  }
  .avatar {
    width: 48px;
    height: 48px;
    display: grid;
    place-items: center;
    font-size: 22px;
    border-radius: 50%;
    background: var(--ring);
    color: var(--avatar-ink);
  }
  .name {
    font-size: var(--fs-sm);
    font-weight: 500;
    text-align: center;
  }
</style>
