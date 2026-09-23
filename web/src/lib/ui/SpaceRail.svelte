<script lang="ts">
  // The spaces rail (SPEC §5 "Spaces rail"): home first, then shared spaces, then DMs. Each links
  // to the space's first channel; shared spaces and DMs can be left from here.
  import type { ChannelRow, SpaceRow } from '../data';
  import { leaveSpace, spaceTitle, type SpaceInfo } from '../spaces';

  let {
    spaces,
    channels,
    currentId,
    directory,
    me,
  }: { spaces: SpaceRow[]; channels: ChannelRow[]; currentId?: string; directory: Map<string, SpaceInfo>; me: string } =
    $props();

  const order = { internal: 0, shared: 1, dm: 2 } as const;
  const sorted = $derived([...spaces].sort((a, b) => order[a.kind] - order[b.kind]));
  const current = $derived(spaces.find((s) => s.id === currentId));
  let error = $state('');

  function first(spaceId: string): string {
    const c = channels.find((c) => c.space_id === spaceId && !c.archived && c.kind !== 'thread');
    return c ? c.id : `space:${spaceId}`;
  }

  async function leave() {
    if (!current || !confirm(`Leave ${spaceTitle(current, directory.get(current.id), me)}? You can be added back later.`)) return;
    error = '';
    try {
      await leaveSpace(current.id);
      location.hash = '#/chat';
    } catch (e) {
      error = e instanceof Error ? e.message : String(e);
    }
  }
</script>

{#if sorted.length > 1}
  <nav class="rail" aria-label="Spaces">
    {#each sorted as s (s.id)}
      <a href="#/chat/{first(s.id)}" class:on={s.id === currentId} class={s.kind}>
        {#if s.kind === 'dm'}<span aria-hidden="true">✉ </span>{/if}{spaceTitle(s, directory.get(s.id), me)}
      </a>
    {/each}
  </nav>
{/if}
<p class="space">
  {current ? spaceTitle(current, directory.get(current.id), me) : 'No spaces yet'}
  {#if current && current.kind !== 'internal' && !(current.kind === 'shared' && directory.get(current.id)?.owner_account_id === me)}
    <button class="leave" onclick={leave}>Leave</button>
  {/if}
</p>
{#if current && current.kind !== 'internal'}
  {@const others = directory.get(current.id)?.accounts.filter((a) => a.id !== me) ?? []}
  {#if others.length}<p class="with">with {others.map((a) => a.display_name ?? `@${a.handle}`).join(', ')}</p>{/if}
{/if}
{#if error}<p class="error" role="alert">{error}</p>{/if}

<style>
  .rail { display: grid; gap: 2px; margin-bottom: var(--s-3); padding-bottom: var(--s-3); border-bottom: 1px solid var(--line); }
  .rail a { color: var(--ink-2); text-decoration: none; padding: var(--s-1) var(--s-2); border-radius: var(--r-sm); font-size: var(--fs-sm); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .rail a.on { background: var(--surface-2); color: var(--ink); font-weight: 600; }
  .rail a.internal { font-weight: 600; }
  .space {
    display: flex; justify-content: space-between; align-items: baseline; gap: var(--s-2);
    font-size: var(--fs-xs); color: var(--ink-3); text-transform: uppercase; letter-spacing: 0.06em; margin: 0 0 var(--s-2);
  }
  .leave { background: none; border: 0; color: var(--ink-3); cursor: pointer; font-size: var(--fs-xs); padding: 0; text-transform: none; letter-spacing: 0; }
  .with { margin: 0 0 var(--s-2); color: var(--ink-3); font-size: var(--fs-sm); }
  .error { color: var(--danger); font-size: var(--fs-sm); margin: 0; }
  @media (max-width: 640px) {
    .rail { display: flex; margin: 0; padding: 0 var(--s-2) 0 0; border-bottom: 0; border-right: 1px solid var(--line); }
    .space, .with { display: none; }
  }
</style>
