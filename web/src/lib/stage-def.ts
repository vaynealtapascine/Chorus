// A stage's saved definition (SPEC §7, D-050, D-144, D-145) and the small pure helpers its screens
// share. What a stage shows is decided by core (`stagePlan`); these only edit the definition and
// describe it. Android's `StagePlan.kt` is the twin.

export type Style = 'chorus' | 'discord' | 'bubbles' | 'card' | 'transcript' | 'minimal';
export type Theme = 'auto' | 'light' | 'dark' | 'custom';
export type Width = 'phone' | 'square' | 'wide';
export type Palette = { bg: string; ink: string; accent: string };
export type Range = [number, number];

export interface Render {
  style: Style;
  theme: Theme;
  palette?: Palette;
  hide_dates?: boolean;
  hide_reactions?: boolean;
  hide_thread_previews?: boolean;
  /** a post's stage: like and repost counts (D-145) */
  hide_counts?: boolean;
  width: Width;
  blur_avatars: boolean;
  blur_attachments: boolean;
  hide_header: boolean;
  hide_reply_bars: boolean;
}

export type TimeDef =
  | { mode: 'real' }
  | { mode: 'hide' }
  | { mode: 'shift'; offset_ms: number }
  | { mode: 'start'; start: number }
  | { mode: 'manual'; at: Record<string, number> };

export interface Def {
  selected: string[];
  unselected: 'hidden' | 'context' | 'visible';
  only_members: string[] | null;
  reply_depth?: number | null;
  redact_names: boolean;
  fake_names: Record<string, { label: string; color?: string | null }>;
  time: TimeDef;
  /** per message: blacked-out `[start, end)` ranges of its text, in UTF-16 units */
  blackout?: Record<string, Range[]>;
  render: Render;
  channel_id?: string;
  post_id?: string;
}

export const STYLES: [Style, string][] = [
  ['chorus', 'Chorus'], ['discord', 'Discord-ish'], ['bubbles', 'Bubbles'], ['card', 'Card'], ['transcript', 'Transcript'], ['minimal', 'Minimal'],
];
export const WIDTH_PX: Record<Width, number> = { phone: 390, square: 600, wide: 820 };
export const DEFAULT_PALETTE: Palette = { bg: '#F4EFE6', ink: '#2B211C', accent: '#247D73' };

export const styleLabel = (style: Style) => STYLES.find(([id]) => id === style)?.[1] ?? 'Chorus';

export const freshRender = (): Render => ({
  style: 'chorus', theme: 'auto', width: 'phone', blur_avatars: false, blur_attachments: false, hide_header: false, hide_reply_bars: false,
  hide_reactions: false, hide_thread_previews: false, hide_dates: false, hide_counts: false,
});

export const fresh = (): Def => ({
  selected: [], unselected: 'context', only_members: null, redact_names: false, fake_names: {}, time: { mode: 'real' }, blackout: {}, render: freshRender(),
});

/** A saved definition as a working one: fields a newer or older client left out take their defaults. */
export function loadDef(saved: unknown): Def {
  const copy = JSON.parse(JSON.stringify(saved ?? {})) as Partial<Def>;
  const base = fresh();
  const def: Def = { ...base, ...copy, render: { ...base.render, ...(copy.render ?? {}) } };
  def.blackout = copy.blackout ?? {};
  if (def.render.theme === 'custom' && !def.render.palette) def.render.palette = { ...DEFAULT_PALETTE };
  return def;
}

/** What a stage hides, for its subtitle and its saved row. */
export const redacted = (d: Def) =>
  d.redact_names || d.render.blur_avatars || d.time.mode === 'hide' || d.render.hide_header || d.render.blur_attachments || Object.keys(d.blackout ?? {}).length > 0;

export const count = (n: number, one: string, many = `${one}s`) => `${n} ${n === 1 ? one : many}`;

/** "+1 h 15 min", "−30 min", "0 min". */
export function shiftLabel(minutes: number): string {
  if (minutes === 0) return '0 min';
  const m = Math.abs(minutes);
  const parts = [m >= 60 ? `${Math.floor(m / 60)} h` : '', m % 60 ? `${m % 60} min` : ''].filter(Boolean);
  return `${minutes < 0 ? '−' : '+'}${parts.join(' ')}`;
}

// ─── black-outs (F8.6) ───

/** Sorted, overlapping and touching ranges joined. */
export function mergeRanges(ranges: readonly Range[]): Range[] {
  const out: Range[] = [];
  for (const [a, b] of [...ranges].filter(([x, y]) => y > x).sort((p, q) => p[0] - q[0] || p[1] - q[1])) {
    const last = out[out.length - 1];
    if (last && a <= last[1]) last[1] = Math.max(last[1], b);
    else out.push([a, b]);
  }
  return out;
}

const wordChar = (c: string | undefined) => !!c && /[\p{L}\p{N}]/u.test(c);

/** The word around `index` in `text` as `[start, end)`, or null on a space. */
export function wordAt(text: string, index: number): Range | null {
  if (!text) return null;
  let k = Math.min(Math.max(index, 0), text.length - 1);
  if (!wordChar(text[k])) {
    if (k > 0 && wordChar(text[k - 1])) k--;
    else return null;
  }
  let a = k;
  while (a > 0 && wordChar(text[a - 1])) a--;
  let b = k + 1;
  while (b < text.length && wordChar(text[b])) b++;
  return [a, b];
}

/** A tap at `index`: gives back the black-outs over it, else blacks out the word (nothing on a space). */
export function tapBlackout(text: string, own: readonly Range[], index: number): Range[] {
  const hit = own.filter(([a, b]) => index >= a && index < b);
  if (hit.length) return own.filter((r) => !hit.includes(r));
  if (mergeRanges(own).some(([a, b]) => index >= a && index < b)) return [...own];
  const word = wordAt(text, index);
  return word ? [...own, word] : [...own];
}

/** A press and drag from `from` to `to`: blacks out what it passes over, whole words at both ends. */
export function dragBlackout(text: string, own: readonly Range[], from: number, to: number): Range[] {
  const lo = Math.min(from, to);
  const hi = Math.max(from, to);
  const start = wordAt(text, lo)?.[0] ?? lo;
  const end = wordAt(text, hi)?.[1] ?? hi + 1;
  return [...own, [start, Math.min(end, text.length)]];
}

/** `own` as the definition keeps it: a message with none has no entry. */
export function withBlackout(d: Def, id: string, ranges: readonly Range[]): Def['blackout'] {
  const next = { ...(d.blackout ?? {}) };
  if (ranges.length) next[id] = ranges.map(([a, b]) => [a, b] as Range);
  else delete next[id];
  return next;
}

/** The text with every blacked-out character replaced, so what is hidden is not in the page at all. */
export const maskText = (text: string, ranges: readonly Range[]): string =>
  mergeRanges(ranges).reduce((out, [a, b]) => out.slice(0, a) + out.slice(a, b).replace(/\S/g, '█') + out.slice(b), text);

/** The pieces of `text` for drawing: plain and black runs. While `reveal` is off (a capture) a black run holds only
 * blocks, never the words. */
export function blackParts(text: string, ranges: readonly Range[], reveal: boolean): { text: string; black: boolean }[] {
  const parts: { text: string; black: boolean }[] = [];
  let at = 0;
  for (const [from, to] of mergeRanges(ranges)) {
    const a = Math.max(at, Math.min(from, text.length));
    const b = Math.min(Math.max(to, a), text.length);
    if (a > at) parts.push({ text: text.slice(at, a), black: false });
    if (b > a) parts.push({ text: reveal ? text.slice(a, b) : text.slice(a, b).replace(/\S/g, '█'), black: true });
    at = Math.max(at, b);
  }
  if (at < text.length) parts.push({ text: text.slice(at), black: false });
  return parts;
}
