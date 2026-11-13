import { readdirSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { beforeAll, describe, expect, it } from 'vitest';
import { core, loadCore, type ReadMark, type TickMsg } from './core';
import { ReadReceipts } from './read-receipts';

beforeAll(async () => {
  await loadCore(readFileSync(fileURLToPath(new URL('./core/pkg/chorus_wasm_bg.wasm', import.meta.url))));
});

describe('ReadReceipts', () => {
  it('holds a fetch per channel and replaces it on the next one', () => {
    const r = new ReadReceipts();
    r.replace('c1', [{ account_id: 'acct', message_id: 'm2', at: 20 }]);
    expect(r.marks('c1')).toEqual([{ member: 'acct', at: 20, id: 'm2' }]);
    expect(r.marks('c2')).toEqual([]);
    r.replace('c1', []);
    expect(r.marks('c1')).toEqual([]);
  });

  it('only moves a reader forward, equal times by id', () => {
    const r = new ReadReceipts();
    r.receive({ t: 'read', channel: 'c', account: 'a', message_id: 'm2', at: 20 });
    r.receive({ t: 'read', channel: 'c', account: 'a', message_id: 'm1', at: 10 });
    r.receive({ t: 'read', channel: 'c', account: 'a', message_id: 'm1', at: 20 });
    expect(r.marks('c')).toEqual([{ member: 'a', at: 20, id: 'm2' }]);
    r.receive({ t: 'read', channel: 'c', account: 'a', message_id: 'm3', at: 20 });
    r.receive({ t: 'read', channel: 'c', account: 'b', message_id: 'm1', at: 5 });
    expect(r.marks('c')).toEqual([{ member: 'a', at: 20, id: 'm3' }, { member: 'b', at: 5, id: 'm1' }]);
  });

  it('forgets a channel, or everything', () => {
    const r = new ReadReceipts();
    r.receive({ t: 'read', channel: 'c', account: 'a', message_id: 'm', at: 1 });
    r.receive({ t: 'read', channel: 'd', account: 'a', message_id: 'm', at: 1 });
    r.drop('c');
    expect(r.marks('c')).toEqual([]);
    expect(r.marks('d')).toHaveLength(1);
    r.clear();
    expect(r.marks('d')).toEqual([]);
  });
});

// The conformance fixtures (fixtures/ticks, SYNC §9.1): the web calls the same core as the server.
describe('ticks agree with core (fixtures/ticks)', () => {
  const dir = fileURLToPath(new URL('../../../fixtures/ticks/', import.meta.url));
  for (const file of readdirSync(dir).filter((f) => f.endsWith('.json'))) {
    const fx = JSON.parse(readFileSync(dir + file, 'utf8')) as { fn: string; args: unknown[]; expect: unknown };
    if (fx.fn !== 'ticks_for') continue;
    it(file, () => {
      const [msgs, members, foreign] = fx.args as [TickMsg[], ReadMark[], ReadMark[]];
      expect(core.ticksFor(msgs, members, foreign)).toEqual(fx.expect);
    });
  }
});
