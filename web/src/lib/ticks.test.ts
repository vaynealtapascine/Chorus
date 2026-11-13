import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { beforeAll, describe, expect, it } from 'vitest';
import { loadCore } from './core';
import { ownTicks } from './ticks';

beforeAll(async () => {
  await loadCore(readFileSync(fileURLToPath(new URL('./core/pkg/chorus_wasm_bg.wasm', import.meta.url))));
});

const msg = (id: string, at: number, account_id: string | null, authors = ['kai'], deleted = false) => ({ id, occurred_at: at, account_id, authors, deleted });

describe('ownTicks', () => {
  it('gives only our own, undeleted messages a tick', () => {
    const t = ownTicks([msg('a', 1, 'me'), msg('b', 2, 'them'), msg('c', 3, 'me', ['kai'], true), msg('d', 4, null)], 'me', [], []);
    expect([...t]).toEqual([['a', 'saved'], ['d', 'pending']]);
  });

  it('reads on a non-author member or on another account', () => {
    const own = [msg('a', 10, 'me', ['kai']), msg('b', 20, 'me', ['kai']), msg('c', 30, 'me', ['kai'])];
    const t = ownTicks(own, 'me', [{ member: 'rin', at: 10, id: 'a' }, { member: 'kai', at: 99, id: 'z' }], [{ member: 'friend', at: 20, id: 'b' }]);
    expect([...t.values()]).toEqual(['read', 'read', 'saved']);
  });

  it('does nothing for a chat with none of ours', () => {
    expect(ownTicks([msg('a', 1, 'them')], 'me', [], []).size).toBe(0);
  });
});
