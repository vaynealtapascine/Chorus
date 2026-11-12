import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { beforeAll, describe, expect, it } from 'vitest';
import { loadCore } from './core';
import { stagePlan } from './core/pkg/chorus_wasm.js';

// The stage plan contract (fixtures/stage/, D-144, D-145): the same cases Android's bridge runs.

const dir = fileURLToPath(new URL('../../../fixtures/stage/', import.meta.url));

beforeAll(async () => {
  await loadCore(readFileSync(fileURLToPath(new URL('./core/pkg/chorus_wasm_bg.wasm', import.meta.url))));
});

describe('stage plan fixtures through wasm', () => {
  for (const file of readdirSync(dir).filter((f) => f.endsWith('.json'))) {
    const fixture = JSON.parse(readFileSync(dir + file, 'utf8')) as { args: [unknown, unknown]; description: string; expect: unknown };
    it(`${file}: ${fixture.description}`, () => {
      const [items, definition] = fixture.args;
      expect(JSON.parse(stagePlan(JSON.stringify(items), JSON.stringify(definition)))).toEqual(fixture.expect);
    });
  }
});
