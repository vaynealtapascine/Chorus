import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { beforeAll, describe, expect, it } from 'vitest';
import { core, loadCore } from './core';
import type { MessageRow } from './data';
import { editedSegments, segmentMarkup, segmentRich } from './segments';

beforeAll(async () => {
  const wasmPath = fileURLToPath(new URL('./core/pkg/chorus_wasm_bg.wasm', import.meta.url));
  await loadCore(readFileSync(wasmPath));
});

describe('segmented message edits', () => {
  it('preserves each segment author and UTF-16 entity offset', () => {
    const composed = core.compose('🌌 **A😀**\n🔖=> _B_', [
      { member_id: 'sky', sigils: ['🌌'] }, { member_id: 'mark', sigils: ['🔖'] },
    ], {}, []);
    const message = { text: composed.rich.text, entities: composed.rich.entities, segments: composed.segments } as MessageRow;
    expect(segmentMarkup(message)).toEqual(['**A😀**', '*B*']);

    const result = editedSegments(['**A😀!**', '_B?_'], message.segments, {});
    expect(result.rich.text).toBe('A😀!\nB?');
    expect(result.segments).toEqual([
      { offset: 0, length: 4, authors: ['sky'] },
      { offset: 5, length: 2, authors: ['mark'] },
    ]);
    expect(result.rich.entities.map((e) => [e.type, e.offset, e.length])).toEqual([
      ['bold', 0, 4], ['italic', 5, 2],
    ]);
    expect(segmentRich({ ...message, text: result.rich.text, entities: result.rich.entities }, result.segments[1]))
      .toEqual({ text: 'B?', entities: [{ type: 'italic', offset: 0, length: 2 }] });
  });
});
