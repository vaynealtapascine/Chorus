// Erasing a signed-out browser's kept copy (D-152, D-153). This module is the only place that
// does it; SyncClient.eraseKept is its only caller, and decides when (a `wipe_at` that has passed,
// or an enrolment the person confirmed that doesn't pick the copy up).
import { wipe } from './persist';
import { forgetBlobs } from './blobs';

/** A browser preference, not the account's: kept. */
const KEEP = new Set(['chorus.theme']);
/** Set in this tab's session after an erasure, so onboarding can say why it is there. */
const ERASED = 'chorus.erased-signed-out';

function forget(store: Storage | undefined, keep: (key: string) => boolean): void {
  if (!store) return;
  try {
    for (const key of Object.keys(store)) if (key.startsWith('chorus') && !keep(key)) store.removeItem(key);
  } catch {
    // storage unavailable: nothing kept there
  }
}

const storage = (name: 'localStorage' | 'sessionStorage'): Storage | undefined => {
  try { return globalThis[name]; } catch { return undefined; }
};

/** What this tab keeps for itself: drafts and the chat folder (sessionStorage is per tab, so every
 * tab of the browser runs this when it follows an erasure). */
export function forgetTab(): void {
  forget(storage('sessionStorage'), () => false);
  try { storage('sessionStorage')?.setItem(ERASED, '1'); } catch { /* the notice is a nicety */ }
}

/** Erase this browser's copy for the account: the ops, metadata, snapshot, device record and key,
 * device settings and staged uploads (IndexedDB), the offline file cache, the unsaved-ops journal
 * and per-account settings (localStorage), this tab's drafts, and the push subscription. */
export async function eraseKeptCopy(): Promise<void> {
  await wipe();
  await forgetBlobs();
  forget(storage('localStorage'), (key) => KEEP.has(key));
  forgetTab();
  try {
    const reg = await navigator.serviceWorker?.getRegistration();
    await (await reg?.pushManager?.getSubscription())?.unsubscribe();
  } catch {
    // no push here; the server dropped the subscription with the device anyway
  }
}

/** This tab's last page erased a signed-out copy (onboarding says it was signed out). */
export function erasedHere(): boolean {
  try { return storage('sessionStorage')?.getItem(ERASED) === '1'; } catch { return false; }
}

/** Enrolled again: nothing more to say. */
export function forgetErased(): void {
  try { storage('sessionStorage')?.removeItem(ERASED); } catch { /* nothing to forget */ }
}
