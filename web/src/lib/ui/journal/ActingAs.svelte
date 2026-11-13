<script lang="ts">
  // The Journal app bar's "Posting as Violet": a sheet of our members' faces to choose who we act as
  // (reacting, and the composer's author); "Current front" follows the front.
  import { members, type MemberRow } from '../../data';
  import { canWriteAs } from '../../posts';
  import { bodyAuthors } from '../../front-view';
  import { sync, type Projection } from '../../sync/client';
  import { journalUi } from '../../journal-ui.svelte';
  import Icon from '../Icon.svelte';
  import Face from '../Face.svelte';
  import Sheet from '../Sheet.svelte';
  import { whoOf } from './who';

  let { projection, dark }: { projection: Projection; dark: boolean } = $props();
  let open = $state(false);
  const active = $derived(members(projection).filter((m) => canWriteAs(projection, m.id, sync.accountId)));
  const byId = $derived(new Map(members(projection).map((m) => [m.id, m] as [string, MemberRow])));
  const nameOf = (m: MemberRow | undefined) => m ? m.display_name ?? m.name : 'someone';
  const front = $derived(bodyAuthors(projection, sync.accountId)[0]);
  const shown = $derived(journalUi.as || front || active[0]?.id);
  const pick = (id: string) => { journalUi.as = id; open = false; };
</script>

<button type="button" class="as" aria-haspopup="dialog" onclick={() => (open = true)}><span class="name">Posting as {nameOf(byId.get(shown ?? ''))}</span><span class="down" aria-hidden="true"><Icon name="chevron" size={12} /></span></button>

{#if open}
  <Sheet title="Posting as" onclose={() => (open = false)}>
    <div class="list" role="radiogroup" aria-label="Posting as">
      <button type="button" class="pick" role="radio" aria-checked={!journalUi.as} onclick={() => pick('')}>
        <span class="face">{#if front}<Face who={whoOf(byId.get(front))} size={36} {dark} />{/if}</span>
        <span class="who"><strong>Current front</strong><span>{front ? nameOf(byId.get(front)) : 'No one fronting'}</span></span>
        <span class="tick" aria-hidden="true">{#if !journalUi.as}<Icon name="check" size={18} />{/if}</span>
      </button>
      {#each active as m (m.id)}
        <button type="button" class="pick" role="radio" aria-checked={journalUi.as === m.id} onclick={() => pick(m.id)}>
          <span class="face"><Face who={whoOf(m)} size={36} {dark} /></span>
          <span class="who"><strong>{nameOf(m)}</strong></span>
          <span class="tick" aria-hidden="true">{#if journalUi.as === m.id}<Icon name="check" size={18} />{/if}</span>
        </button>
      {/each}
    </div>
  </Sheet>
{/if}

<style>
  .as { display: inline-flex; align-items: center; gap: var(--s-1); max-width: 100%; min-height: 24px; padding: 0; border: 0; background: none; color: var(--ink-3); font: inherit; font-size: var(--fs-xs); cursor: pointer; }
  .as:hover { color: var(--ink); }
  .name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .down { display: inline-grid; transform: rotate(90deg); }
  .list { display: grid; padding: var(--s-1) var(--s-2) var(--s-3); }
  .pick { display: flex; align-items: center; gap: var(--s-3); min-height: 52px; padding: var(--s-1) var(--s-2); border: 0; border-radius: var(--r-row); background: none;
    color: var(--ink); font: inherit; text-align: left; cursor: pointer; }
  @media (hover: hover) { .pick:hover { background: var(--surface-2); } }
  .face { display: grid; place-items: center; width: 36px; height: 36px; flex: none; }
  .who { flex: 1; min-width: 0; display: grid; }
  .who strong { font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .who span { color: var(--ink-3); font-size: var(--fs-xs); }
  .tick { display: grid; place-items: center; width: 28px; height: 28px; border-radius: 50%; }
  .pick[aria-checked='true'] .tick { background: var(--accent); color: var(--on-accent); }
</style>
