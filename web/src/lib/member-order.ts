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
