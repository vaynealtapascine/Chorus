<script lang="ts">
  // Reorder members (Android MemberReorder): drag the grips, then Apply writes a sort key for each
  // member that moved. Nothing is written until Apply; Cancel leaves the order as it was.
  import { untrack } from 'svelte';
  import { core } from '../core';
  import { router } from '../router.svelte';
  import { sync, type Projection } from '../sync/client';
  import { eligible } from '../member-directory';
  import { orderPatches } from '../member-order';
  import AvatarImage from './AvatarImage.svelte';
  import DragList from './kit/DragList.svelte';

  let { projection, dark }: { projection: Projection; dark: boolean } = $props();
  const list = $derived(eligible(projection, sync.accountId, false));
  let ids = $state<string[]>(untrack(() => eligible(projection, sync.accountId, false).map((m) => m.id)));
  let moved = $state(new Set<string>());
  const items = $derived(ids.map((id) => list.find((m) => m.id === id)).filter((m) => !!m));

  function reorder(next: string[]) {
    const was = ids;
    moved = new Set([...moved, ...next.filter((id, i) => was[i] !== id)]);
    ids = next;
  }
  function apply() {
    for (const [id, key] of orderPatches(list, ids, moved)) sync.create('member.set', sync.accountScope, id, { sort_key: key });
    router.go('/members');
  }
</script>

<section class="page">
  <header>
    <a class="back" href="#/members" aria-label="Back to members">‹</a>
    <h1>Reorder members</h1>
    <button class="apply" disabled={!moved.size} onclick={apply}>Apply</button>
  </header>
  <DragList {items} onreorder={reorder} label="Reorder members">
    {#snippet row(m)}
      {@const c = core.adaptColor(m.color, dark)}
      <span class="member" style="--ring: {c.ring}; --avatar-ink: {c.avatar_ink}">
        <span class="avatar" aria-hidden="true"><AvatarImage hash={m.avatar_blob} glyph={m.sigils[0] ?? m.name[0]} name={m.name} /></span>
        <span class="info"><strong>{m.display_name ?? m.name}</strong>{#if m.pronouns}<span>{m.pronouns}</span>{/if}</span>
      </span>
    {/snippet}
  </DragList>
</section>

<style>
  .page { display: grid; gap: 12px; align-content: start; }
  header { display: flex; align-items: center; gap: 8px; }
  .back { display: grid; place-items: center; width: 40px; height: 40px; font-size: 28px; color: var(--ink); text-decoration: none; }
  h1 { flex: 1; margin: 0; font-size: 20px; }
  .apply { min-height: 40px; border: 0; border-radius: 999px; padding: 0 18px; background: var(--accent); color: var(--on-accent); font: inherit; font-weight: 600; cursor: pointer; }
  .apply:disabled { background: var(--surface-2); color: var(--ink-3); cursor: default; }
  .member { display: flex; align-items: center; gap: 12px; flex: 1; min-width: 0; padding: 8px 8px 8px 0; }
  .avatar { width: 40px; height: 40px; display: grid; place-items: center; flex: none; border-radius: 50%; overflow: hidden; background: var(--ring); color: var(--avatar-ink); font-weight: 700; }
  .info { display: grid; min-width: 0; }
  .info strong { font-size: 16px; font-weight: 600; }
  .info span { color: var(--ink-3); font-size: 13px; }
</style>
