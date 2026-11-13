<script lang="ts">
  // "Posting as Violet ▾" (F6.2, Android JournalAuthorsPicker): opens a sheet of our members' faces to
  // pick who is writing, one or several; the first picked is the main author.
  import Icon from '../Icon.svelte';
  import Face from '../Face.svelte';
  import Sheet from '../Sheet.svelte';
  import Button from '../kit/Button.svelte';
  import type { MemberRow } from '../../data';
  import type { Composer } from '../../journal-composer.svelte';
  import { whoOf } from './who';

  let { c, dark }: { c: Composer; dark: boolean } = $props();
  let open = $state(false);
  let filter = $state('');
  const names = $derived(c.authorMembers.map((m) => m.display_name ?? m.name));
  // a singlet (or a system of one) has no one else to choose
  const choosable = $derived(c.writers.length > 1 || c.authors.length !== c.authorMembers.length);
  const label = $derived(names.length ? `Posting as ${names.join(' & ')}` : 'Choose who is writing');
  const query = $derived(filter.trim().toLowerCase());
  const others = $derived(c.writers.filter((m) => !c.authors.includes(m.id) && (!query || (m.display_name ?? m.name).toLowerCase().includes(query) || m.name.toLowerCase().includes(query))));
  function toggle(m: MemberRow) {
    // the last author stays: a post needs someone writing it
    if (c.authors.includes(m.id)) { if (c.authors.length > 1) c.setAuthors(c.authors.filter((id) => id !== m.id)); }
    else c.setAuthors([...c.authors, m.id]);
  }
</script>

<button type="button" class="author" aria-haspopup="dialog" disabled={!choosable} onclick={() => { filter = ''; open = true; }}>
  <span class="name">{label}</span>{#if choosable}<span class="down" aria-hidden="true"><Icon name="chevron" size={14} /></span>{/if}
</button>

{#if open}
  <Sheet title="Who’s writing" onclose={() => (open = false)}>
    <div class="list" role="group" aria-label="Writing">
      {#each c.authorMembers as m, i (m.id)}
        <button type="button" class="pick on" aria-pressed="true" onclick={() => toggle(m)}>
          <Face who={whoOf(m)} size={36} {dark} />
          <span class="who"><strong>{m.display_name ?? m.name}</strong>{#if i === 0 && c.authorMembers.length > 1}<span>Main author</span>{/if}</span>
          <span class="tick" aria-hidden="true"><Icon name="check" size={18} /></span>
        </button>
      {/each}
    </div>
    {#if c.writers.length > c.authorMembers.length}
      {#if c.writers.length > 8}<input class="filter" bind:value={filter} placeholder="Search members" aria-label="Search members" />{/if}
      <div class="list" role="group" aria-label="Add a co-author">
        {#each others as m (m.id)}
          <button type="button" class="pick" aria-pressed="false" onclick={() => toggle(m)}>
            <Face who={whoOf(m)} size={36} {dark} />
            <span class="who"><strong>{m.display_name ?? m.name}</strong></span>
            <span class="tick" aria-hidden="true"><Icon name="plus" size={18} /></span>
          </button>
        {/each}
      </div>
    {/if}
    {#snippet footer()}<Button variant="primary" grow onclick={() => (open = false)}>Done</Button>{/snippet}
  </Sheet>
{/if}

<style>
  .author { display: inline-flex; align-items: center; gap: 6px; max-width: 100%; min-height: 32px; padding: 0 var(--s-3); border: 0; border-radius: var(--r-full);
    background: var(--accent-soft); color: var(--accent); font: inherit; font-size: var(--fs-sm); font-weight: 600; cursor: pointer; }
  .author:focus-visible, .pick:focus-visible, .filter:focus-visible { outline-offset: -2px; }
  .author:disabled { cursor: default; }
  .name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .down { display: inline-grid; transform: rotate(90deg); }
  .list { display: grid; }
  .list + .list, .filter + .list { border-top: 1px solid var(--line); padding-top: var(--s-1); }
  .pick { display: flex; align-items: center; gap: var(--s-3); min-height: 52px; padding: var(--s-1) var(--s-2); border: 0; border-radius: var(--r-row); background: none;
    color: var(--ink); font: inherit; text-align: left; cursor: pointer; }
  @media (hover: hover) { .pick:hover { background: var(--surface-2); filter: none; } }
  .who { flex: 1; min-width: 0; display: grid; }
  .who strong { font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .who span { color: var(--ink-3); font-size: var(--fs-xs); }
  .tick { display: grid; place-items: center; width: 28px; height: 28px; border-radius: 50%; color: var(--ink-3); }
  .pick.on .tick { background: var(--accent); color: var(--on-accent); }
  .filter { border: 0; border-radius: var(--r-row); background: var(--surface-2); color: var(--ink); padding: 10px var(--s-3); font: inherit; }
</style>
