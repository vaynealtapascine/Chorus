import '@fontsource-variable/figtree';
import '@fontsource-variable/fraunces/full.css';
import './lib/design/base.css';
import { mount } from 'svelte';
import App from './App.svelte';
import { loadCore } from './lib/core';
import { sync } from './lib/sync/client';

await loadCore();
await sync.start();
mount(App, { target: document.getElementById('app')! });

// offline app shell in production builds (plugins/service-worker.ts)
if (import.meta.env.PROD && 'serviceWorker' in navigator) {
  navigator.serviceWorker.register('/sw.js').catch((e) => console.warn('service worker failed', e));
}
