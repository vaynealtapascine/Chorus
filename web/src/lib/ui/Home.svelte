<script lang="ts">
  import { core } from '../core';
  import { sync, type Projection } from '../sync/client';
  import { groups } from '../data';
  import { doSwitch } from '../front.svelte';
  import FrontCard from './FrontCard.svelte';
  import Reviews from './Reviews.svelte';
  import LinkDevice from './LinkDevice.svelte';
  import type { FrontEntry, Member } from './types';

  let { projection, dark, onswitch }: { projection: Projection; dark: boolean; onswitch: () => void } = $props();

  const members: Member[] = $derived(
    Object.entries(projection.rows.member ?? {})
      .filter(([, r]) => r.exists && !r.fields.deleted_at && !r.fields.archived_at)
      .map(([id, r]) => ({
        id,
        name: String(r.fields.name ?? 'Unnamed'),
        pronouns: r.fields.pronouns as string | undefined,
        color: String(r.fields.color ?? '#A09184'),
        sigil: (r.fields.sigils as string[] | undefined)?.[0],
      }))
      .sort((a, b) => a.name.localeCompare(b.name)),
  );
  // the front card can show subsystems fronting as a unit, not only members
  const subjects: Member[] = $derived([
    ...members,
    ...groups(projection).map((g) => ({ id: g.id, name: g.name, color: g.color ?? '#A09184', sigil: '◌' })),
  ]);
  const fold = $derived(projection.fronts[sync.accountId]);
  const front: FrontEntry[] = $derived(
    (fold?.current ?? []).map((e) => ({ id: e.subject_id, level: e.level, primary: e.is_primary })),
  );
  const since = $derived.by(() => {
    const open = (fold?.intervals ?? []).filter((i) => i.end_at === null);
    return open.length ? Math.min(...open.map((i) => i.start_at)) : Date.now();
  });

  function switchTo(id: string) {
    const name = members.find((m) => m.id === id)?.name ?? 'someone';
    doSwitch([{ subject_type: 'member', subject_id: id, level: 'front', is_primary: true }], `Switched to ${name}`);
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
  <Reviews {projection} />
  <button class="card-button" onclick={onswitch} aria-label="Change who's here">
    <FrontCard members={subjects} {front} {since} {dark} />
  </button>

  <section aria-label="Quick switch">
    <div class="section-head">
      <h2>Quick switch</h2>
      <button class="ghost" onclick={() => (adding = !adding)}>{adding ? 'Cancel' : 'Add member'}</button>
    </div>
    {#if adding}
      <form class="add" onsubmit={addMember}>
        <input bind:value={newSigil} placeholder="🌌" maxlength="8" aria-label="Sigil" class="sigil" />
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
        <button class="member" class:here style="--ring: {c.ring}; --tint: {c.tint}" onclick={() => switchTo(m.id)}>
          <span class="avatar" aria-hidden="true">{m.sigil ?? m.name[0]}</span>
          <span class="name" style="color: {c.name}">{m.name}</span>
        </button>
      {/each}
    </div>
  </section>
  <LinkDevice />
  <section class="data-links" aria-label="Data">
    <h2>Data</h2>
    <a href="#/trash">Trash · deleted items</a>
  </section>
</div>

<style>
  .home {
    display: grid;
    gap: var(--s-6);
  }
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
    color: var(--surface);
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
    background: var(--surface);
    border: 1px solid var(--line);
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
    background: var(--surface-2);
    box-shadow: 0 0 0 2px var(--ring);
  }
  .name {
    font-size: var(--fs-sm);
    font-weight: 500;
    text-align: center;
  }
</style>
