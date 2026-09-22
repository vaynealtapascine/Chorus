import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { beforeAll, describe, expect, it } from 'vitest';
import { core, loadCore } from './index';

beforeAll(async () => {
  const wasmPath = fileURLToPath(new URL('./pkg/chorus_wasm_bg.wasm', import.meta.url));
  await loadCore(readFileSync(wasmPath));
});

describe('chorus-core in the browser build', () => {
  it('parses markup with names', () => {
    const r = core.parseMarkup('hi **@kai**', { mentions: { kai: { target_type: 'member', target_id: 'm1' } } });
    expect(r.text).toBe('hi @kai');
    expect(r.entities.map((e) => e.type).sort()).toEqual(['bold', 'mention']);
    expect(core.toMarkup(r)).toBe('hi **@kai**');
  });

  it('composes segments', () => {
    const c = core.compose('🌌 go\n🔖=> no', [{ member_id: 'sky', sigils: ['🌌'] }, { member_id: 'mark', sigils: ['🔖'] }], {}, ['def']);
    expect(c.segments.map((s) => s.authors)).toEqual([['sky'], ['mark']]);
  });

  it('reports feed errors with a position', () => {
    expect(() => core.feedParse('kind:banana')).toThrow(/"pos":0/);
  });
});
