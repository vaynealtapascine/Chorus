import { pick, ACCENTS, DENSITIES, TEXT_SIZES, type Accent, type Density, type TextSize } from './appearance-prefs';

export type ThemeMode = 'dark' | 'light' | 'system';
const media = window.matchMedia('(prefers-color-scheme: dark)');
const read = (key: string) => { try { return localStorage.getItem(key); } catch { return null; } };
const write = (key: string, value: string) => { try { localStorage.setItem(key, value); } catch { /* usable without browser persistence */ } };
const mode = (v: string | null): ThemeMode => (v === 'light' || v === 'system' ? v : 'dark');

/** This device's look (F10.2): device-local, never synced. */
export const appearance = $state<{ mode: ThemeMode; dark: boolean; accent: Accent; textSize: TextSize; density: Density; memberColours: boolean }>({
  mode: mode(read('chorus.theme')), dark: true,
  accent: pick(ACCENTS, read('chorus.accent')), textSize: pick(TEXT_SIZES, read('chorus.text_size')),
  density: pick(DENSITIES, read('chorus.density')), memberColours: read('chorus.member_colours') !== 'false',
});
function apply() {
  const root = document.documentElement;
  appearance.dark = appearance.mode === 'dark' || (appearance.mode === 'system' && media.matches);
  root.dataset.theme = appearance.dark ? 'dark' : 'light';
  // teal, default and comfortable are the base look: no attribute
  const set = (name: string, value: string | null) => (value ? (root.dataset[name] = value) : delete root.dataset[name]);
  set('accent', appearance.accent === 'teal' ? null : appearance.accent);
  set('text', appearance.textSize === 'default' ? null : appearance.textSize);
  set('density', appearance.density === 'comfortable' ? null : appearance.density);
  set('names', appearance.memberColours ? null : 'plain');
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', appearance.dark ? '#15161A' : '#F7F6F2');
}
export function setTheme(next: ThemeMode) {
  appearance.mode = next;
  write('chorus.theme', next);
  apply();
}
export function setAppearance(change: Partial<Pick<typeof appearance, 'accent' | 'textSize' | 'density' | 'memberColours'>>) {
  Object.assign(appearance, change);
  write('chorus.accent', appearance.accent);
  write('chorus.text_size', appearance.textSize);
  write('chorus.density', appearance.density);
  write('chorus.member_colours', String(appearance.memberColours));
  apply();
}
media.addEventListener('change', apply);
window.addEventListener('storage', (e) => {
  if (e.key === 'chorus.theme') appearance.mode = mode(e.newValue);
  else if (e.key === 'chorus.accent') appearance.accent = pick(ACCENTS, e.newValue);
  else if (e.key === 'chorus.text_size') appearance.textSize = pick(TEXT_SIZES, e.newValue);
  else if (e.key === 'chorus.density') appearance.density = pick(DENSITIES, e.newValue);
  else if (e.key === 'chorus.member_colours') appearance.memberColours = e.newValue !== 'false';
  else return;
  apply();
});
apply();
