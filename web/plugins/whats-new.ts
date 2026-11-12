import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import type { Plugin } from 'vite';

// `whats-new.txt` in the build: android/whats-new.txt without its # comment lines, which the update
// sheet reads (F10.5). Written on build only, so every deploy path that builds the web app has it.
export function whatsNew(): Plugin {
  return {
    name: 'chorus-whats-new',
    apply: 'build',
    generateBundle() {
      const file = resolve(import.meta.dirname, '../../android/whats-new.txt');
      if (!existsSync(file)) return;
      const lines = readFileSync(file, 'utf8').split(/\r?\n/).map((l) => l.trim()).filter((l) => l && !l.startsWith('#'));
      this.emitFile({ type: 'asset', fileName: 'whats-new.txt', source: `${lines.join('\n')}\n` });
    },
  };
}
