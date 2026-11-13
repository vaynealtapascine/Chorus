<script lang="ts">
  // `@` suggestions under the caret's line: our members' faces, names and handles (as the chat's list).
  // Fixed to the viewport so no scrolling middle or cell clips it; flips above the line near the bottom.
  import Face from '../Face.svelte';
  import { caretBox } from '../../caret-xy';
  import { members } from '../../data';
  import { whoOf } from './who';
  import type { TextEditing } from '../../journal-composer.svelte';

  let { ed, dark }: { ed: TextEditing; dark: boolean } = $props();
  const ROW = 48;
  const byId = $derived(new Map(ed.c.projection ? members(ed.c.projection).map((m) => [m.id, m]) : []));
  let pos = $state<{ left: number; top: number; width: number } | null>(null);

  function place() {
    const el = ed.el, at = ed.at, n = ed.matches.length;
    if (!el || !at || !n) { pos = null; return; }
    const box = caretBox(el, at.from, at.from);
    if (!box) return;
    const r = el.getBoundingClientRect();
    const view = window.visualViewport;
    const bottom = (view ? view.offsetTop + view.height : innerHeight) - 8;
    const width = Math.min(300, innerWidth - 16), height = n * ROW + 8;
    const below = r.top + box.top + box.line + 4;
    pos = {
      width, left: Math.max(8, Math.min(r.left + box.left, innerWidth - width - 8)),
      top: below + height > bottom && r.top + box.top - 4 - height > 8 ? r.top + box.top - 4 - height : below,
    };
  }
  $effect(() => { void ed.c.draft.body; void ed.at; void ed.matches.length; place(); });
  $effect(() => {
    if (!ed.matches.length) return;
    addEventListener('scroll', place, true);
    addEventListener('resize', place);
    return () => { removeEventListener('scroll', place, true); removeEventListener('resize', place); };
  });
</script>

{#if pos && ed.matches.length}
  <div id={ed.listId} class="popup" role="listbox" aria-label="Mention suggestions" style="left: {pos.left}px; top: {pos.top}px; width: {pos.width}px">
    {#each ed.matches as s, i (s.id)}
      <button type="button" id="{ed.listId}-{i}" class="row" class:on={i === ed.index} role="option" aria-selected={i === ed.index} tabindex="-1"
        onmousedown={(e) => e.preventDefault()} onmousemove={() => (ed.index = i)} onclick={() => ed.pickMention(s)}>
        <Face who={whoOf(byId.get(s.id))} size={32} {dark} />
        <strong>{s.name}</strong><small>@{s.handle}</small>
      </button>
    {/each}
  </div>
{/if}

<style>
  .popup { position: fixed; z-index: 60; display: grid; box-sizing: border-box; padding: var(--s-1); border: 1px solid var(--line); border-radius: var(--r-control); background: var(--surface);
    box-shadow: var(--shadow-menu); animation: chorus-pop-in var(--t-fast) var(--ease); }
  .row { display: flex; align-items: center; gap: var(--s-3); min-height: 48px; padding: 0 var(--s-2); border: 0; border-radius: var(--r-sm); background: none; color: var(--ink); font: inherit; text-align: left; cursor: pointer; }
  .row.on { background: var(--surface-2); }
  strong { flex: none; max-width: 55%; font-size: var(--fs-md); font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  small { flex: 1; min-width: 0; color: var(--ink-3); font-size: var(--fs-sm); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
</style>
