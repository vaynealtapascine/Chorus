// The audience bar over a shared space's composer (Figma F5.4/F5.16): "Everyone here", or an aside only
// our own system sees (`system_only`, SPEC §5.3). A singlet has no system to keep it to. Port of
// Android's data/ChatAside.kt.

export const offered = (spaceKind: string | undefined, person: boolean): boolean => spaceKind === 'shared' && !person;

const ours = (system: string | null | undefined) => system?.trim() || 'your system';

/** "Everyone here · 3 systems", "Everyone here · 2 systems · 1 person"; just "Everyone here" while unknown. */
export function everyone(accounts: { kind: string }[] | undefined): string {
  const list = accounts ?? [];
  const systems = list.filter((a) => a.kind === 'system').length;
  const people = list.length - systems;
  return [
    'Everyone here',
    systems ? `${systems} ${systems === 1 ? 'system' : 'systems'}` : '',
    people ? `${people} ${people === 1 ? 'person' : 'people'}` : '',
  ].filter(Boolean).join(' · ');
}

export const on = (system: string | null | undefined): string => `Aside · only ${ours(system)} will see this`;
export const placeholder = (system: string | null | undefined): string => `Aside to ${ours(system)}…`;

/** Beside an aside's author: ours just "aside"; another member's says who it stays with. */
export const tag = (own: boolean, system: string | null | undefined): string => (own ? 'aside' : `aside · only ${ours(system)}`);

/** After sending, an aside stays on (the bar shows it); any other audience goes back to everyone. */
export const afterSend = (audience: string): string => (audience === 'system_only' ? audience : 'all');
