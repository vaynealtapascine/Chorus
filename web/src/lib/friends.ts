// Plural friends, seen from outside (D-093): who an account we follow shows as fronting, and how a
// shared space's accounts are counted when some are singlets. Pure, so Android's FriendFronts and
// these lines stay in step (android/.../data/FriendFronts.kt).

export interface FrontMember { name: string; color: string | null }
export interface ViewIn { entries?: { level: string; name: string; color?: string | null }[]; shared?: boolean }

/** The front layer of a follower view, as already revealed; nothing when it isn't shared. */
export function frontMembers(view: ViewIn | null): FrontMember[] {
  if (!view || view.shared === false) return [];
  return (view.entries ?? []).filter((e) => e.level === 'front' && e.name).map((e) => ({ name: e.name, color: e.color ?? null }));
}

/** "Kai is fronting", "Kai & Wren are fronting", "Kai, Wren & 2 more are fronting"; null when nobody is shown. */
export function frontingLine(names: string[]): string | null {
  if (!names.length) return null;
  if (names.length === 1) return `${names[0]} is fronting`;
  if (names.length === 2) return `${names[0]} & ${names[1]} are fronting`;
  return `${names[0]}, ${names[1]} & ${names.length - 2} more are fronting`;
}

/** "3 systems", "2 people", "2 systems · 1 person": a shared space's accounts, singlets included. */
export function accountsLabel(accounts: { kind: string }[]): string {
  const systems = accounts.filter((a) => a.kind === 'system').length;
  const people = accounts.length - systems;
  return [
    systems ? `${systems} ${systems === 1 ? 'system' : 'systems'}` : '',
    people ? `${people} ${people === 1 ? 'person' : 'people'}` : '',
  ].filter(Boolean).join(' · ');
}
