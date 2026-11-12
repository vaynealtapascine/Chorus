// How this device draws Chorus (F10.2): the choices, their stored words and the one-line summary.
// The words match Android's `chorus.appearance` prefs (lower-cased), which are device-local there too.
export const ACCENTS = [
  { id: 'teal', label: 'Teal' }, { id: 'sunflower', label: 'Sunflower' }, { id: 'lime', label: 'Lime' },
  { id: 'lavender', label: 'Lavender' }, { id: 'sky', label: 'Sky' },
] as const;
export const TEXT_SIZES = [{ id: 'default', label: 'Default' }, { id: 'large', label: 'Large' }, { id: 'larger', label: 'Larger' }] as const;
export const DENSITIES = [{ id: 'comfortable', label: 'Comfortable' }, { id: 'compact', label: 'Compact' }] as const;

export type Accent = (typeof ACCENTS)[number]['id'];
export type TextSize = (typeof TEXT_SIZES)[number]['id'];
export type Density = (typeof DENSITIES)[number]['id'];

/** A stored word read back as one of the choices; anything else is the default. */
export function pick<T extends { id: string }>(choices: readonly T[], stored: string | null | undefined): T['id'] {
  const word = stored?.toLowerCase();
  return (choices.find((c) => c.id === word) ?? choices[0]).id;
}

const labelOf = (choices: readonly { id: string; label: string }[], id: string) => choices.find((c) => c.id === id)?.label ?? '';

/** What Appearance's row says: "Dark · teal · comfortable" (F10.1). */
export function appearanceSummary(mode: string, accent: Accent, density: Density): string {
  return [mode === 'system' ? 'Device' : mode[0].toUpperCase() + mode.slice(1), labelOf(ACCENTS, accent).toLowerCase(), labelOf(DENSITIES, density).toLowerCase()].join(' · ');
}
