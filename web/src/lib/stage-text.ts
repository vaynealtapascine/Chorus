// Stage black-outs (UI21 F8.6): core hands back each message's blacked-out ranges, sorted and merged,
// in UTF-16 code units, which is how JavaScript indexes strings.

export interface StagePart {
  text: string;
  black: boolean;
}

/** `text` cut into plain and blacked-out runs; ranges past the end are clamped. */
export function blackoutParts(text: string, ranges: readonly (readonly [number, number])[]): StagePart[] {
  const parts: StagePart[] = [];
  let at = 0;
  for (const [from, to] of ranges) {
    const a = Math.max(at, Math.min(from, text.length));
    const b = Math.min(Math.max(to, a), text.length);
    if (a > at) parts.push({ text: text.slice(at, a), black: false });
    if (b > a) parts.push({ text: text.slice(a, b), black: true });
    at = Math.max(at, b);
  }
  if (at < text.length) parts.push({ text: text.slice(at), black: false });
  return parts;
}

/** A custom theme's palette from core as CSS custom properties for the stage canvas. */
export function paletteStyle(palette: Record<string, string | boolean> | undefined): string {
  if (!palette) return '';
  const vars: [string, string][] = [['bg', '--bg'], ['surface', '--surface'], ['surface2', '--surface-2'], ['surface3', '--surface-3'],
    ['line', '--line'], ['ink', '--ink'], ['ink2', '--ink-2'], ['ink3', '--ink-3'], ['accent', '--accent']];
  return vars.flatMap(([k, v]) => (typeof palette[k] === 'string' ? [`${v}: ${palette[k]}`] : [])).join('; ');
}
