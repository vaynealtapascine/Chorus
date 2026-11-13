<script lang="ts">
  // The composer's rows of tools (F6.2): who sees it (audience, bell, Saved), the formatting glyphs and
  // the attach glyphs. `docked` is the full composer's look (each glyph an equal share, as Android's
  // ToolGlyph); otherwise the inline boxes' compact icons.
  import type { Snippet } from 'svelte';
  import Icon from '../Icon.svelte';
  import AudienceChip from '../AudienceChip.svelte';
  import { active, lines, lineStyle, style, wordCount } from '../../format-text';
  import { MAX_FILES, type TextEditing } from '../../journal-composer.svelte';

  let { ed, part, docked = false, few = false, up = false, saved = false, toggle = false, bell = true, trailing }: {
    ed: TextEditing; part: 'audience' | 'format' | 'attach'; docked?: boolean;
    /** the quiet box's three icons only (image, poll, emoji) */
    few?: boolean;
    /** the audience menu opens upwards */
    up?: boolean;
    /** show "Saved" at the end of the audience row */
    saved?: boolean;
    /** a T that shows and hides the formatting row (the inline boxes) */
    toggle?: boolean;
    /** the bell chip beside the audience (the inline boxes place their own) */
    bell?: boolean;
    trailing?: Snippet;
  } = $props();
  const c = $derived(ed.c);
  const coarse = typeof matchMedia === 'function' && matchMedia('(pointer: coarse)').matches;
  const keepFocus = (e: Event) => e.preventDefault();
  const words = $derived(wordCount(c.draft.body));
  const lineNow = $derived(lineStyle(ed.current()));
  const on = (marker: string) => active(ed.current(), marker);
  const full = $derived(c.pending.length >= MAX_FILES);
  let picker = $state<HTMLInputElement | undefined>();
  let camera = $state<HTMLInputElement | undefined>();
  const take = (e: Event & { currentTarget: HTMLInputElement }) => { if (e.currentTarget.files) c.addFiles(e.currentTarget.files); e.currentTarget.value = ''; };
</script>

{#snippet glyph(icon: string, label: string, act: () => void, pressed?: boolean, disabled = false, keep = false)}
  <button type="button" class="glyph" class:on={pressed} aria-label={label} title={label} aria-pressed={pressed} {disabled}
    onmousedown={keep ? keepFocus : undefined} onclick={act}><Icon name={icon} size={docked ? 22 : 20} /></button>
{/snippet}

{#if part === 'audience'}
  <div class="row chips">
    <AudienceChip label="This post" value={c.audience} own buckets={c.chipBuckets} onchange={(a) => c.setAudience(a)} {up}
      defaultAudience={c.replyTo ? undefined : c.accountDefault} onusedefault={(v) => c.useByDefault(v)} />
    {#if bell}<button type="button" class="chip" class:on={!!c.draft.alerts} aria-label="Notifications" title="Notifications" onclick={() => (ed.sheet = 'alerts')}>
      <Icon name={c.draft.alerts === 'none' ? 'bell-off' : 'bell'} size={14} />{c.draft.alerts === 'none' ? 'Silent' : c.draft.alerts === 'delayed' ? 'Delayed' : ''}</button>{/if}
    {#if saved}<span class="saved">{c.busy || c.error || !c.isOccupied ? '' : 'Saved'}</span>{/if}
    {@render trailing?.()}
  </div>
{:else if part === 'format'}
  <div class="row tools" class:docked role="toolbar" aria-label="Formatting">
    {@render glyph('format-text', 'Formatting', () => (ed.sheet = 'format'), undefined, false, true)}
    {@render glyph('format-bold', 'Bold', () => ed.mark('**'), on('**'), false, true)}
    {@render glyph('format-italic', 'Italic', () => ed.mark('_'), on('_'), false, true)}
    {@render glyph('format-strike', 'Strikethrough', () => ed.mark('~~'), on('~~'), false, true)}
    <!-- H cycles body, heading, subheading, as Android's -->
    {@render glyph('format-heading', 'Heading', () => ed.act((v) => style(v, lineStyle(v) === 'heading1' ? 'heading2' : lineStyle(v) === 'heading2' ? '' : 'heading1')), lineNow.startsWith('heading'), false, true)}
    {@render glyph('format-list-ul', 'Bulleted list', () => ed.act((v) => lines(v, '• ')), undefined, false, true)}
    {@render glyph('format-list-ol', 'Numbered list', () => ed.act((v) => lines(v, '', true)), undefined, false, true)}
    {@render glyph('format-quote', 'Quote', () => ed.act((v) => style(v, lineStyle(v) === 'quote' ? '' : 'quote')), lineNow === 'quote', false, true)}
    {@render glyph('format-code', 'Code', () => ed.mark('`'), on('`'), false, true)}
    {@render glyph('link', 'Link', ed.openLink, undefined, false, true)}
    {@render glyph('eye-off', 'Spoiler', () => ed.mark('||'), on('||'), false, true)}
  </div>
{:else}
  <div class="row tools attach" class:docked>
    {@render glyph('image', 'Attach photos or files', () => picker?.click(), undefined, full)}
    {#if coarse && (!few || docked)}{@render glyph('camera', 'Take a photo', () => camera?.click(), undefined, full)}{/if}
    {@render glyph('poll', 'Poll', () => (c.draft.pollOn = !c.draft.pollOn), c.draft.pollOn)}
    {@render glyph('smile', 'Emoji', () => (ed.sheet = 'emoji'))}
    {#if !few}
      {@render glyph('alert', 'Content warning', ed.toggleCw, ed.showCw)}
      {@render glyph('hash', 'Tags and mood', () => (ed.tagsOpen = !ed.tagsOpen), ed.showTags)}
    {/if}
    {#if toggle}<button type="button" class="glyph t" class:on={ed.formatRow} aria-pressed={ed.formatRow} aria-label="Show formatting" title="Formatting"
      onmousedown={keepFocus} onclick={() => (ed.formatRow = !ed.formatRow)}><Icon name="format-text" size={20} /></button>{/if}
    {#if docked}<span class="words">{words} {words === 1 ? 'word' : 'words'}</span>{/if}
    {@render trailing?.()}
    <input bind:this={picker} class="sr-label" type="file" multiple tabindex="-1" aria-hidden="true" onchange={take} />
    <input bind:this={camera} class="sr-label" type="file" accept="image/*" capture="environment" tabindex="-1" aria-hidden="true" onchange={take} />
  </div>
{/if}

<style>
  .row { display: flex; align-items: center; min-width: 0; }
  .chips { gap: var(--s-2); min-height: 44px; flex-wrap: wrap; }
  .chip { display: inline-flex; align-items: center; gap: var(--s-1); min-height: 26px; min-width: 32px; justify-content: center; padding: 0 10px; border: 0; border-radius: var(--r-full);
    background: var(--surface-2); color: var(--ink-2); font: inherit; font-size: var(--fs-xs); font-weight: 500; cursor: pointer; }
  .chip.on { background: var(--accent-soft); color: var(--accent); }
  .glyph:focus-visible, .chip:focus-visible { outline-offset: -2px; }
  .saved { margin-left: auto; color: var(--ink-3); font-size: var(--fs-sm); }
  .tools { gap: 0; }
  .glyph { flex: none; display: grid; place-items: center; width: 40px; height: 40px; border: 0; border-radius: var(--r-sm); background: none; color: var(--ink-2); cursor: pointer;
    transition: background var(--t-fast) var(--ease), color var(--t-fast) var(--ease); }
  .attach .glyph { color: var(--accent); }
  .attach .glyph.t:not(.on) { color: var(--ink-2); }
  .glyph:disabled { opacity: .4; }
  .glyph.on { background: var(--accent-soft); color: var(--accent); }
  @media (hover: hover) { .glyph:not(:disabled):not(.on):hover { background: var(--surface-2); filter: none; } }
  /* the full composer: every glyph an equal share of the width (Android ToolGlyph) */
  .docked .glyph { flex: 1 1 0; width: auto; min-width: 0; height: 44px; margin: 0 1px; }
  .docked.attach .glyph { flex: 1 1 0; max-width: 56px; }
  .words { flex: 2 1 0; text-align: right; padding-right: var(--s-3); color: var(--ink-3); font-size: var(--fs-sm); white-space: nowrap; }
  .sr-label { position: absolute; width: 1px; height: 1px; clip-path: inset(50%); overflow: hidden; white-space: nowrap; }
</style>
