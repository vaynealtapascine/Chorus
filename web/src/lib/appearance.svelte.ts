export type ThemeMode = 'dark' | 'light' | 'system';
const media = window.matchMedia('(prefers-color-scheme: dark)');
const stored = (() => { try { return localStorage.getItem('chorus.theme'); } catch { return null; } })();
export const appearance = $state<{ mode: ThemeMode; dark: boolean }>({
  mode: stored === 'light' || stored === 'system' ? stored : 'dark', dark: true,
});
function apply() {
  appearance.dark = appearance.mode === 'dark' || (appearance.mode === 'system' && media.matches);
  document.documentElement.dataset.theme = appearance.dark ? 'dark' : 'light';
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', appearance.dark ? '#15161A' : '#F7F6F2');
}
export function setTheme(mode: ThemeMode) {
  appearance.mode = mode;
  try { localStorage.setItem('chorus.theme', mode); } catch { /* usable without browser persistence */ }
  apply();
}
media.addEventListener('change', apply);
window.addEventListener('storage', (e) => {
  if (e.key !== 'chorus.theme') return;
  appearance.mode = e.newValue === 'light' || e.newValue === 'system' ? e.newValue : 'dark';
  apply();
});
apply();
