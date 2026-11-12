<script lang="ts">
  // F6.10's Votes list: each of our members and what they chose, or that they haven't voted, with
  // Nudge for those still to vote (D-143). Own account only (D-081, D-086).
  import { closedAt, nudge, nudgedRecently, ownChoices, type Poll } from '../polls';
  import { sync, type Projection } from '../sync/client';
  import { actors, faceOf, isDark } from '../many-members';
  import Face from './Face.svelte';

  let { projection, parentId, poll, voter }: { projection: Projection; parentId: string; poll: Poll; voter: string | null | undefined } = $props();
  const ours = $derived(actors(projection, sync.accountId));
  let now = $state(Date.now());
  const closes = $derived(closedAt(projection, parentId, poll));
  const open = $derived(closes == null || now < closes);
  const dark = isDark();
  const choiceText = (id: string) => ownChoices(projection, parentId, id).map((c) => poll.options.find((o) => o.id === c)?.text).filter(Boolean).join(', ');
  function ping(id: string) { now = Date.now(); nudge(parentId, id, voter); }
</script>

{#if ours.length > 1}
  <section class="votes" aria-label="Votes">
    <h2>Votes</h2>
    {#each ours as m (m.id)}
      {@const text = choiceText(m.id)}
      {@const sent = nudgedRecently(projection, parentId, m.id, now)}
      <div class="row">
        <Face who={faceOf(m)} size={40} {dark} />
        <div class="who"><strong>{m.display_name ?? m.name}</strong><span>{text || 'Hasn’t voted yet'}</span></div>
        {#if m.id === voter}<span class="you">You</span>
        {:else if !text && open}<button type="button" class="nudge" disabled={sent} aria-label="Nudge {m.display_name ?? m.name}" onclick={() => ping(m.id)}>{sent ? 'Nudged' : 'Nudge'}</button>{/if}
      </div>
    {/each}
  </section>
{/if}

<style>
  .votes { display: grid; padding: var(--s-2) 0; }
  h2 { margin: 0; padding: var(--s-2) var(--s-5); color: var(--accent); font-size: var(--fs-sm); font-weight: 600; }
  .row { display: flex; align-items: center; gap: 14px; padding: var(--s-2) var(--s-5); }
  .who { flex: 1; min-width: 0; display: grid; }
  .who strong { font-size: var(--fs-md); font-weight: 600; }
  .who span { color: var(--ink-3); font-size: var(--fs-sm); }
  .you { padding: 5px var(--s-3); border-radius: var(--r-full); background: var(--accent-soft); color: var(--accent); font-size: var(--fs-sm); font-weight: 500; }
  .nudge { padding: 7px 14px; border: 1px solid var(--line); border-radius: var(--r-full); background: none; color: var(--accent); font: inherit; font-size: var(--fs-sm); font-weight: 500; cursor: pointer; }
  .nudge:disabled { color: var(--ink-3); cursor: default; }
</style>
