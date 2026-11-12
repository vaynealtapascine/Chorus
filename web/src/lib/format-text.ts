/** The composer's formatting actions on a text selection: markup only, in the syntax chorus-core's
 * `parseMarkup` reads (a port of Android's `EditorFormatting`). Pure: text and a selection in, out. */
export interface Sel { text: string; start: number; end: number }

export type LineStyle = '' | 'quote' | 'heading1' | 'heading2';

const lo = (v: Sel) => Math.min(v.start, v.end);
const hi = (v: Sel) => Math.max(v.start, v.end);

/** Is the selection wrapped in exactly this marker? (the toolbar button shows pressed) */
export function active(v: Sel, marker: string): boolean {
  const a = lo(v), b = hi(v);
  return a >= marker.length && v.text.slice(a - marker.length, a) === marker && v.text.slice(b).startsWith(marker);
}

/** Wrap the selection in a marker, or take the marker off when it is already there. */
export function inline(v: Sel, marker: string): Sel {
  const a = lo(v), b = hi(v), n = marker.length;
  if (active(v, marker)) return { text: v.text.slice(0, a - n) + v.text.slice(a, b) + v.text.slice(b + n), start: a - n, end: b - n };
  return { text: v.text.slice(0, a) + marker + v.text.slice(a, b) + marker + v.text.slice(b), start: a + n, end: b + n };
}

function lineSpan(v: Sel): [number, number] {
  const start = v.text.lastIndexOf('\n', Math.max(lo(v) - 1, -1)) + 1;
  const nl = v.text.indexOf('\n', hi(v));
  return [start, nl >= 0 ? nl : v.text.length];
}

/** Prefix every selected line (`• `, or `1. `, `2. ` … when numbered). */
export function lines(v: Sel, prefix: string, numbered = false): Sel {
  const [start, end] = lineSpan(v);
  const out = v.text.slice(start, end).split('\n').map((line, i) => (numbered ? `${i + 1}. ` : prefix) + line);
  const joined = out.join('\n');
  return { text: v.text.slice(0, start) + joined + v.text.slice(end), start, end: start + joined.length };
}

const PREFIXES = ['## ', '# ', '>> ', '> '];
const prefixOf = (line: string) => PREFIXES.find((p) => line.startsWith(p)) ?? '';

/** The paragraph style of the caret's line. */
export function lineStyle(v: Sel): LineStyle {
  const start = v.text.lastIndexOf('\n', Math.max(lo(v) - 1, -1)) + 1;
  const line = v.text.slice(start).split('\n')[0];
  if (line.startsWith('## ')) return 'heading2';
  if (line.startsWith('# ')) return 'heading1';
  if (line.startsWith('> ') || line.startsWith('>> ')) return 'quote';
  return '';
}

/** Set the style of the selected lines; a heading is one line, so only the caret's. */
export function style(v: Sel, to: LineStyle): Sel {
  const wanted = { '': '', quote: '> ', heading1: '# ', heading2: '## ' }[to];
  const [start, end] = lineSpan(v);
  const ls = v.text.slice(start, end).split('\n');
  const touched = wanted.startsWith('#') ? 1 : ls.length;
  let first = 0, all = 0;
  const out = ls.map((line, i) => {
    if (i >= touched) return line;
    const old = prefixOf(line);
    const delta = wanted.length - old.length;
    if (i === 0) first = delta;
    all += delta;
    return wanted + line.slice(old.length);
  }).join('\n');
  const text = v.text.slice(0, start) + out + v.text.slice(end);
  const move = (at: number, d: number) => Math.min(Math.max(at + d, start), text.length);
  const a = lo(v), b = hi(v);
  return { text, start: move(a, first), end: move(b, b === a ? first : all) };
}

/** Is the caret inside a ``` block? */
export function inFence(v: Sel): boolean {
  return (v.text.slice(0, lo(v)).match(/(^|\n)```/g) ?? []).length % 2 === 1;
}

/** Wrap the selected lines in a code block, or unwrap the block around the caret. */
export function fence(v: Sel): Sel {
  if (inFence(v)) {
    const open = v.text.lastIndexOf('```', lo(v) - 1);
    const close = v.text.indexOf('```', hi(v));
    if (open < 0 || close < 0) return v;
    const nl = v.text.indexOf('\n', open);
    const openEnd = (nl >= 0 && nl <= close ? nl : open + 3) + 1;
    const inner = v.text.slice(Math.min(openEnd, close), close).replace(/\n$/, '');
    let after = close + 3;
    if (v.text[after] === '\n') after++;
    after = Math.min(after, v.text.length);
    const text = v.text.slice(0, open) + inner + (after < v.text.length ? '\n' : '') + v.text.slice(after);
    return { text, start: open, end: open + inner.length };
  }
  const [start, end] = lineSpan(v);
  const inner = v.text.slice(start, end);
  return { text: v.text.slice(0, start) + '```\n' + inner + '\n```' + v.text.slice(end), start: start + 4, end: start + 4 + inner.length };
}

/** Wrap the selected lines in one spoiler. */
export function spoilerBlock(v: Sel): Sel {
  const [start, end] = lineSpan(v);
  const inner = v.text.slice(start, end);
  return { text: v.text.slice(0, start) + '||' + inner + '||' + v.text.slice(end), start: start + 2, end: start + 2 + inner.length };
}

/** A link address the markup can carry, or why not. */
export function linkProblem(url: string): string | null {
  if (!/^(https:\/\/|http:\/\/|mailto:)/.test(url)) return 'Use an https, http or mailto link.';
  if (/[\s)\\]/.test(url)) return 'Use a link without spaces or closing brackets.';
  return null;
}

/** `[selection](url)` (the address itself when nothing is selected), with the label selected. */
export function link(v: Sel, url: string): Sel {
  const a = lo(v), b = hi(v);
  const label = v.text.slice(a, b) || url;
  return { text: v.text.slice(0, a) + `[${label}](${url})` + v.text.slice(b), start: a + 1, end: a + 1 + label.length };
}

/** Put text at the caret (an emoji), leaving the caret after it. */
export function insert(v: Sel, add: string): Sel {
  const a = lo(v), b = hi(v);
  return { text: v.text.slice(0, a) + add + v.text.slice(b), start: a + add.length, end: a + add.length };
}

export const wordCount = (text: string) => text.split(/\s+/).filter(Boolean).length;
