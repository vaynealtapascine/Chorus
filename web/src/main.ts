import '@fontsource-variable/figtree';
import '@fontsource-variable/fraunces/full.css';
import './lib/design/base.css';
import { mount } from 'svelte';
import App from './App.svelte';
import { loadCore } from './lib/core';

await loadCore();
mount(App, { target: document.getElementById('app')! });
