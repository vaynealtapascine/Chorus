import { readdirSync } from 'node:fs';
import { resolve } from 'node:path';
import type { Plugin } from 'vite';

// `virtual:chorus-icons`: the names in public/icons, so the app can warm them up (Icon.svelte)
export function iconNames(): Plugin {
  const id = 'virtual:chorus-icons';
  let publicDir = '';
  return {
    name: 'chorus-icon-names',
    configResolved(config) { publicDir = config.publicDir; },
    resolveId(source) { return source === id ? `\0${id}` : undefined; },
    load(source) {
      if (source !== `\0${id}`) return undefined;
      const names = readdirSync(resolve(publicDir, 'icons')).filter((n) => n.endsWith('.svg')).map((n) => n.slice(0, -4));
      return `export default ${JSON.stringify(names.sort())};`;
    },
  };
}
