import './lib/appearance.svelte';
import './lib/design/base.css';
import { mount } from 'svelte';
import App from './App.svelte';
import { loadCore } from './lib/core';
import { sync } from './lib/sync/client';

import { pathTarget } from './lib/share-links';

// a shared link (D-109) loads the app at /post/<id> or /message/<id>: open what it points at
const shared = pathTarget(location.pathname);
if (shared) {
  history.replaceState(null, '', `/#/${shared.kind === 'post' ? 'journal/post' : 'message'}/${shared.id}`);
  // the router read the address when it was imported, before this
  dispatchEvent(new HashChangeEvent('hashchange'));
}

await loadCore();
await sync.start();
mount(App, { target: document.getElementById('app')! });
performance.mark('chorus:mounted');

// offline app shell in production builds (plugins/service-worker.ts)
if (import.meta.env.PROD && 'serviceWorker' in navigator) {
  navigator.serviceWorker.register('/sw.js').catch((e) => console.warn('service worker failed', e));
}
