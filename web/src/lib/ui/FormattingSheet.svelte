<script lang="ts">
  // The Formatting sheet (F6.11, Android FormattingDrawer): the paragraph style, the marks, the blocks.
  import Sheet from './Sheet.svelte';
  import Icon from './Icon.svelte';
  import { active, fence, inFence, inline, lines, lineStyle, spoilerBlock, style, type LineStyle, type Sel } from '../format-text';

  let { value, onchange, onlink, onclose }: { value: Sel; onchange: (v: Sel) => void; onlink: () => void; onclose: () => void } = $props();
  const current = $derived(lineStyle(value));
  const inPre = $derived(inFence(value));
  const styles: [string, string, LineStyle | 'code'][] = [['Body', 'body', ''], ['Heading', 'h1', 'heading1'], ['Subheading', 'h2', 'heading2'], ['Quote', 'quote', 'quote'], ['Code', 'code', 'code']];
  const marks: [string, string, string][] = [['format-bold', 'Bold', '**'], ['format-italic', 'Italic', '_'], ['format-strike', 'Strikethrough', '~~'], ['format-code', 'Code', '`'], ['eye-off', 'Spoiler', '||']];
  const isOn = (to: LineStyle | 'code') => (to === 'code' ? inPre : !inPre && current === to);
</script>

<Sheet title="Formatting" {onclose}>
  <div class="styles" role="radiogroup" aria-label="Paragraph style">
    {#each styles as [label, cls, to] (cls)}
      <button type="button" class="s {cls}" role="radio" aria-checked={isOn(to)} onclick={() => onchange(to === 'code' ? fence(value) : style(value, to))}>{label}</button>
    {/each}
  </div>
  <div class="marks">
    {#each marks as [icon, label, marker] (marker)}
      <button type="button" aria-label={label} aria-pressed={active(value, marker)} onclick={() => onchange(inline(value, marker))}><Icon name={icon} size={22} /></button>
    {/each}
    <button type="button" aria-label="Link" onclick={onlink}><Icon name="link" size={22} /></button>
  </div>
  <div class="blocks">
    <button type="button" onclick={() => onchange(lines(value, '• '))}><Icon name="format-list-ul" size={18} />Bullets</button>
    <button type="button" onclick={() => onchange(lines(value, '', true))}><Icon name="format-list-ol" size={18} />Numbers</button>
    <button type="button" onclick={() => onchange(spoilerBlock(value))}><Icon name="eye-off" size={18} />Spoiler block</button>
  </div>
  {#snippet footer()}<button type="button" class="done" onclick={onclose}>Done</button>{/snippet}
</Sheet>

<style>
  .styles, .marks, .blocks { display: flex; gap: 6px; }
  button { flex: 1; min-height: 44px; border: 0; border-radius: 12px; background: var(--surface-2); color: var(--ink); font: inherit; font-size: 13px; font-weight: 500; cursor: pointer; display: inline-flex; align-items: center; justify-content: center; gap: 6px; }
  .marks button { min-height: 48px; color: var(--ink-2); }
  .styles button[aria-checked='true'], .marks button[aria-pressed='true'] { background: var(--accent-soft); color: var(--accent); box-shadow: inset 0 0 0 1px var(--accent); }
  .h1, .h2 { font-weight: 700; }
  .h1 { font-size: 15px; }
  .quote { font-style: italic; }
  .code { font-family: ui-monospace, monospace; }
  .done { flex: none; margin-left: auto; padding: 0 20px; background: var(--accent); color: var(--on-accent); font-weight: 600; }
</style>
