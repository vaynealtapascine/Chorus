<script lang="ts">
  // D-142: hold (or right-click) an action to do it as several of our members at once. Each member
  // has a round check, ticked at first for those who have it; Everyone · Fronting · Nobody pick
  // quickly. Save hands `onapply` what changed, in the list's order (Android's ManyMembersDrawer).
  import type { Snippet } from 'svelte';
  import '../design/friends.css';
  import type { MemberRow } from '../data';
  import { faceOf, memberChange, type MemberChange } from '../many-members';
  import Face from './Face.svelte';
  import Icon from './Icon.svelte';
  import Sheet from './Sheet.svelte';

  let { title, subtitle = null, members, had, fronting, line, onclose, onapply, extra }: {
    title: string; subtitle?: string | null; members: MemberRow[]; had: Set<string>; fronting: Set<string>;
    /** A quiet second line under a name (a voter's other choice); else "Fronting" for who fronts. */
    line?: (id: string) => string | null;
    onclose: () => void; onapply: (change: MemberChange) => void; extra?: Snippet;
  } = $props();

  // the page's theme (appearance.svelte.ts sets it), read here so tests can render without a window
  const dark = typeof document === 'undefined' || document.documentElement.dataset.theme !== 'light';
  const order = $derived(members.map((m) => m.id));
  // the sheet is opened fresh each time, so the first ticks are read once
  // svelte-ignore state_referenced_locally
  let chosen = $state(new Set(had));
  const change = $derived(memberChange(order, had, chosen));
  const count = $derived(change.add.length + change.remove.length);
  const here = $derived(order.filter((id) => fronting.has(id)));
  const same = (ids: string[]) => ids.length === chosen.size && ids.every((id) => chosen.has(id));
  function toggle(id: string) {
    const next = new Set(chosen);
    if (!next.delete(id)) next.add(id);
    chosen = next;
  }
  const who = faceOf;
</script>

<Sheet {title} {subtitle} {onclose}>
  <div class="picks">
    <button class:on={same(order)} onclick={() => (chosen = new Set(order))}>Everyone</button>
    {#if here.length && here.length < order.length}<button class:on={same(here)} onclick={() => (chosen = new Set(here))}>Fronting</button>{/if}
    <button class:on={chosen.size === 0} onclick={() => (chosen = new Set())}>Nobody</button>
  </div>
  <ul>
    {#each members as m (m.id)}
      {@const on = chosen.has(m.id)}
      {@const sub = line?.(m.id) ?? (fronting.has(m.id) ? 'Fronting' : null)}
      <li><button role="checkbox" aria-checked={on} onclick={() => toggle(m.id)}>
        <Face who={who(m)} size={36} {dark} />
        <span class="name"><strong>{m.display_name ?? m.name}</strong>{#if sub}<small>{sub}</small>{/if}</span>
        <span class="check" class:on>{#if on}<Icon name="check" size={16} />{/if}</span>
      </button></li>
    {/each}
  </ul>
  {#if extra}{@render extra()}{/if}
  {#snippet footer()}
    <button class="fr-wide" disabled={!count} onclick={() => onapply(change)}>{count ? `Save · ${count} ${count === 1 ? 'change' : 'changes'}` : 'Save'}</button>
  {/snippet}
</Sheet>

<style>
  .picks { display: flex; gap: 8px; }
  .picks button { font: inherit; font-size: 13px; font-weight: 500; padding: 6px 12px; border-radius: 8px; border: 1px solid var(--line);
    background: none; color: var(--ink-2); cursor: pointer; }
  .picks button.on { border-color: var(--accent); background: var(--accent-soft); color: var(--accent); }
  ul { list-style: none; margin: 0; padding: 0; border-radius: 14px; background: var(--surface-2); overflow: hidden; }
  li + li { border-top: 1px solid var(--line); }
  li button { width: 100%; min-height: 56px; display: flex; align-items: center; gap: 14px; padding: 8px 12px; border: 0; background: none;
    color: var(--ink); font: inherit; text-align: left; cursor: pointer; }
  .name { flex: 1; min-width: 0; display: grid; }
  .name strong, .name small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .name strong { font-size: 15px; font-weight: 600; }
  .name small { color: var(--ink-3); font-size: 12px; }
  .check { width: 26px; height: 26px; flex: none; display: grid; place-items: center; border-radius: 50%; border: 1.5px solid var(--ink-3); color: var(--on-accent); }
  .check.on { border-color: var(--accent); background: var(--accent); }
</style>
