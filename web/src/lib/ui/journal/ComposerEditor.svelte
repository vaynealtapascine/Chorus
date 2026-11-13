<script lang="ts">
  // The composer's text: no box, no resize handle, grows with what is written; keys format and post
  // (Ctrl/⌘ B I K E, Shift+X, Enter continues a list, Ctrl/⌘ Enter posts); a selection shows the pill.
  import { tick } from 'svelte';
  import Icon from '../Icon.svelte';
  import { active, chord, enter } from '../../format-text';
  import { caretBox } from '../../caret-xy';
  import MentionPopup from './MentionPopup.svelte';
  import type { TextEditing } from '../../journal-composer.svelte';

  let { ed, placeholder, onpost, onfocus, autofocus = false, big = false, roomy = true, dark = true, disabled = false }: {
    ed: TextEditing; placeholder: string; onpost: () => void; onfocus?: () => void; autofocus?: boolean;
    /** the full composer's roomier field */
    big?: boolean;
    /** the full field's two-line minimum shrinks when a quote, files or a poll follow it */
    roomy?: boolean; dark?: boolean; disabled?: boolean;
  } = $props();
  const c = $derived(ed.c);
  const marks: [string, string, string][] = [['format-bold', 'Bold', '**'], ['format-italic', 'Italic', '_'], ['format-strike', 'Strikethrough', '~~'], ['format-code', 'Code', '`']];
  // a toolbar press must not take the selection away from the text
  const keepFocus = (e: Event) => e.preventDefault();

  // browsers without `field-sizing` grow the field from its scroll height
  const sizes = typeof CSS !== 'undefined' && CSS.supports?.('field-sizing', 'content');
  function fit() {
    if (sizes || !ed.el) return;
    ed.el.style.height = 'auto';
    ed.el.style.height = `${ed.el.scrollHeight}px`;
  }
  $effect(() => { void c.draft.body; void tick().then(fit); });
  $effect(() => { if (autofocus) ed.el?.focus(); });

  function keydown(e: KeyboardEvent) {
    if (e.isComposing) return;
    // the @ list takes plain arrows, Enter and Tab, and Escape closes only it (before the dialog sees it)
    if (ed.matches.length && !e.ctrlKey && !e.metaKey && !e.altKey) {
      if ((e.key === 'ArrowDown' || e.key === 'ArrowUp') && !e.shiftKey) { e.preventDefault(); ed.moveMention(e.key === 'ArrowDown' ? 1 : -1); return; }
      if ((e.key === 'Enter' || e.key === 'Tab') && !e.shiftKey) { e.preventDefault(); e.stopPropagation(); ed.pickMention(); return; }
      if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); ed.closeMention(); return; }
    }
    const what = chord(e);
    if (what === 'post') { e.preventDefault(); onpost(); return; }
    if (what === 'link') { e.preventDefault(); ed.openLink(); return; }
    if (what) { e.preventDefault(); ed.mark(what); return; }
    if (e.key === 'Enter' && !e.shiftKey && !e.ctrlKey && !e.metaKey && !e.altKey) {
      ed.track();
      const next = enter(ed.current());
      if (next) { e.preventDefault(); void ed.apply(next); }
    }
  }
  function paste(e: ClipboardEvent) {
    const files = [...(e.clipboardData?.files ?? [])];
    if (!files.length) return;
    e.preventDefault();
    c.addFiles(files);
  }

  // the pill sits over the selection's first line, or under its last when that is in the first two lines
  // (over them it would cover the author chip); measured on a hidden copy of the field
  let wrap = $state<HTMLDivElement | undefined>();
  let pillAt = $state<{ x: number; y: number; below: boolean } | null>(null);
  $effect(() => {
    if (!ed.picked || !ed.el || !wrap) { pillAt = null; return; }
    void c.draft.body;
    const el = ed.el, a = Math.min(ed.caret.start, ed.caret.end), b = Math.max(ed.caret.start, ed.caret.end);
    const box = caretBox(el, a, b);
    if (!box) { pillAt = null; return; }
    const below = box.top < box.line * 2;
    const last = below ? caretBox(el, b, b) ?? box : box;
    pillAt = { x: box.left + Math.min(box.width, 240) / 2 + el.offsetLeft, y: last.top + (below ? last.line : 0) + el.offsetTop, below };
  });
  const PILL = 296;
  const pillLeft = $derived(pillAt && wrap ? Math.max(0, Math.min(pillAt.x - PILL / 2, wrap.clientWidth - PILL)) : 0);
</script>

<div class="body-wrap" bind:this={wrap}>
  {#if ed.picked}<div class="pill" class:below={pillAt?.below} role="toolbar" aria-label="Format the selection" style="left: {pillLeft}px; top: {pillAt ? pillAt.y : 0}px">
    {#each marks as [icon, label, marker] (marker)}<button type="button" class:on={active(ed.current(), marker)} aria-label={label} title={label} onmousedown={keepFocus} onclick={() => ed.mark(marker)}><Icon name={icon} size={18} /></button>{/each}
    <button type="button" aria-label="Link" title="Link" onmousedown={keepFocus} onclick={ed.openLink}><Icon name="link" size={18} /></button>
    <button type="button" class:on={active(ed.current(), '||')} aria-label="Spoiler" title="Spoiler" onmousedown={keepFocus} onclick={() => ed.mark('||')}><Icon name="eye-off" size={18} /></button>
    <button type="button" aria-label="Formatting" title="Formatting" onmousedown={keepFocus} onclick={() => (ed.sheet = 'format')}><Icon name="format-text" size={18} /></button>
  </div>{/if}
  <!-- svelte-ignore a11y_role_supports_aria_props_implicit -->
  <textarea bind:this={ed.el} bind:value={c.draft.body} class:big class:roomy rows="1" {placeholder} aria-label="Post text" {disabled}
    aria-autocomplete="list" aria-expanded={ed.matches.length > 0} aria-controls={ed.matches.length ? ed.listId : undefined} aria-activedescendant={ed.matches.length ? `${ed.listId}-${ed.index}` : undefined}
    onselect={ed.track} onkeyup={ed.track} onclick={ed.track} oninput={() => { ed.track(); fit(); if (c.restored && !c.draft.lostFiles) c.restored = false; }} onfocus={() => { ed.track(); onfocus?.(); }} onblur={ed.track}
    onkeydown={keydown} onpaste={paste}></textarea>
  <MentionPopup {ed} {dark} />
</div>

<style>
  .body-wrap { position: relative; min-width: 0; }
  textarea { display: block; width: 100%; box-sizing: border-box; margin: 0; padding: 6px 0; border: 0; outline: none; background: transparent; color: var(--ink);
    font: inherit; font-size: var(--fs-base); line-height: 24px; resize: none; overflow: hidden; field-sizing: content; min-height: 36px; }
  textarea::placeholder { color: var(--ink-3); }
  textarea.big { min-height: 60px; }
  textarea.big.roomy { min-height: 120px; }
  .pill { position: absolute; z-index: 4; transform: translateY(calc(-100% - 6px)); display: flex; width: 296px; box-sizing: border-box; gap: 2px; padding: 2px var(--s-1);
    border-radius: var(--r-control); background: var(--surface-2); box-shadow: var(--shadow-menu); animation: chorus-pop-in var(--t-fast) var(--ease); }
  .pill button { flex: 1; display: grid; place-items: center; height: 40px; border: 0; border-radius: var(--r-sm); background: none; color: var(--ink-2); cursor: pointer; }
  .pill.below { transform: translateY(6px); }
  .pill button:focus-visible { outline-offset: -2px; }
  .pill button.on { background: var(--accent-soft); color: var(--accent); }
</style>
