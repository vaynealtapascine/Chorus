// Install Chorus (F10.8): Chromium offers `beforeinstallprompt`; iOS Safari only has Share > Add to Home Screen.
interface InstallEvent extends Event { prompt(): Promise<void> }
const DISMISSED = 'chorus.install_dismissed';
const read = () => { try { return localStorage.getItem(DISMISSED) === '1'; } catch { return false; } };

export const installPrompt = $state<{ event: InstallEvent | null; dismissed: boolean }>({ event: null, dismissed: read() });

const standalone = () => typeof matchMedia === 'function' && matchMedia('(display-mode: standalone)').matches || (globalThis.navigator as { standalone?: boolean } | undefined)?.standalone === true;
const ios = () => /iphone|ipad|ipod/i.test(globalThis.navigator?.userAgent ?? '');

/** What the card offers: the browser's own install, a line for iOS, or nothing. */
export function installKind(): 'prompt' | 'ios' | null {
  if (standalone() || installPrompt.dismissed) return null;
  return installPrompt.event ? 'prompt' : ios() ? 'ios' : null;
}
export async function install() {
  const event = installPrompt.event;
  installPrompt.event = null;
  await event?.prompt();
}
export function dismissInstall() {
  installPrompt.dismissed = true;
  try { localStorage.setItem(DISMISSED, '1'); } catch { /* remembered for this visit only */ }
}
if (typeof addEventListener === 'function') {
  addEventListener('beforeinstallprompt', (e) => { e.preventDefault(); installPrompt.event = e as InstallEvent; });
  addEventListener('appinstalled', () => { installPrompt.event = null; });
}
