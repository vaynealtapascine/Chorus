// A new build of the app is ready (F10.5): the service worker installs it in the background and
// takes over at once, but this page keeps running the old code until it reloads. The sheet asks.
import { parseWhatsNew } from './whats-new';

export const updates = $state<{ ready: boolean; later: boolean; checking: boolean; notes: string[] }>({ ready: false, later: false, checking: false, notes: [] });
let registration: ServiceWorkerRegistration | null = null;

async function loadNotes() {
  try {
    const r = await fetch('/whats-new.txt', { cache: 'no-cache' });
    const type = r.headers.get('content-type') ?? '';
    updates.notes = r.ok && type.startsWith('text/plain') ? parseWhatsNew(await r.text()) : [];
  } catch { updates.notes = []; }
}

export function watchUpdates(reg: ServiceWorkerRegistration) {
  registration = reg;
  reg.addEventListener('updatefound', () => {
    const worker = reg.installing;
    if (!worker) return;
    // with no controller yet this is the first install (the worker claims the page when it
    // activates, which must not look like an update)
    const update = !!navigator.serviceWorker.controller;
    worker.addEventListener('statechange', () => {
      if (update && (worker.state === 'installed' || worker.state === 'activated') && !updates.ready) {
        updates.ready = true;
        updates.later = false;
        void loadNotes();
      }
    });
  });
}

/** Look for a new build now (Settings > Updates). */
export async function checkForUpdate() {
  updates.checking = true;
  try { await registration?.update(); } catch { /* offline */ } finally { updates.checking = false; }
}

export const installUpdate = () => location.reload();
