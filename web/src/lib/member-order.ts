import type { MemberRow } from './data';

// D-090/D-091: mirrors Android MemberOrdering; compare code units, never locale collation.
function hex(s: string): string {
  return s.split('').map(c => c.charCodeAt(0).toString(16).padStart(4, '0')).join('');
}
function fold(s: string): string {
  return s.replace(/[A-Z]/g, c => String.fromCharCode(c.charCodeAt(0) + 32));
}
export function memberOrderKey(m: Pick<MemberRow, 'id' | 'name' | 'display_name' | 'sort_key'>): string {
  const stored = m.sort_key;
  const position = stored?.startsWith('~ui6:') && stored.length > 5 &&
    /^[\x20-\x7e]+$/.test(stored.slice(5)) && !stored.endsWith(' ')
    ? stored.slice(5) : hex(stored ?? fold(m.display_name ?? m.name));
  return `${position}!${hex(m.id)}`;
}
export function orderedMembers<T extends MemberRow>(members: T[]): T[] {
  return [...members].sort((a, b) => {
    const x = memberOrderKey(a), y = memberOrderKey(b);
    return x < y ? -1 : x > y ? 1 : a.id < b.id ? -1 : a.id > b.id ? 1 : 0;
  });
}

const PREFIX = '~ui6:';

/** A printable position strictly between two complete keys (Android MemberOrdering.between). */
export function between(lower: string | null, upper: string | null): string {
  if (lower !== null && upper !== null && !(lower < upper)) throw new Error('lower must be below upper');
  let out = '';
  let high = upper;
  for (let i = 0; ; i++) {
    const a = lower && i < lower.length ? lower.charCodeAt(i) : 32;
    const b = high && i < high.length ? high.charCodeAt(i) : 126;
    if (b - a > 1) return out + String.fromCharCode(Math.floor((a + b) / 2));
    out += String.fromCharCode(a);
    if (a < b) high = null;
  }
}

/** The sort key that puts [id] before [beforeId] (null: last) among [list], or null when nothing changes. */
export function moveKey<T extends MemberRow>(list: T[], id: string, beforeId: string | null): string | null {
  const ordered = orderedMembers(list);
  const from = ordered.findIndex((m) => m.id === id);
  if (from < 0 || beforeId === id) return null;
  const remaining = ordered.filter((m) => m.id !== id);
  const to = beforeId === null ? remaining.length : remaining.findIndex((m) => m.id === beforeId);
  if (to < 0) return null;
  const next = [...remaining];
  next.splice(to, 0, ordered[from]);
  if (next.every((m, i) => m.id === ordered[i].id)) return null;
  return PREFIX + between(to > 0 ? memberOrderKey(remaining[to - 1]) : null, to < remaining.length ? memberOrderKey(remaining[to]) : null);
}

/** What to write so [list] ends up in [target] order: a sort key for each member in [moved] that needs one. */
export function orderPatches<T extends MemberRow>(list: T[], target: string[], moved: Set<string>): [string, string][] {
  let current = list;
  const out: [string, string][] = [];
  for (let i = target.length - 1; i >= 0; i--) {
    const id = target[i];
    if (!moved.has(id)) continue;
    const key = moveKey(current, id, target[i + 1] ?? null);
    if (key === null) continue;
    out.push([id, key]);
    current = current.map((m) => (m.id === id ? { ...m, sort_key: key } : m));
  }
  return out;
}
