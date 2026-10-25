import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { beforeAll, describe, expect, it } from 'vitest';
import { core, loadCore, type FrontHistoryInterval, type FrontPresence, type FrontSystem, type SpeakerContext } from './index';

interface Fixture { fn: string; args: unknown[]; expect: unknown }
const fixtureRoot = new URL('../../../../fixtures/', import.meta.url);
const fixtures = ['front_system', 'front_history', 'front_effective'].flatMap((area) => {
  const directory = new URL(`${area}/`, fixtureRoot);
  return readdirSync(directory).filter((file) => file.endsWith('.json')).sort().map((file) => ({
    name: `${area}/${file}`,
    data: JSON.parse(readFileSync(new URL(file, directory), 'utf8')) as Fixture,
  }));
});

beforeAll(async () => {
  await loadCore(readFileSync(fileURLToPath(new URL('./pkg/chorus_wasm_bg.wasm', import.meta.url))));
});

describe('named-front conformance through the actual wasm bridge', () => {
  for (const { name, data } of fixtures) {
    it(name, () => {
      const args = data.args;
      let got: unknown;
      switch (data.fn) {
        case 'fold_front':
          got = core.foldFront(typeof args[0] === 'string' ? JSON.parse(args[0]) as unknown[] : args[0] as unknown[]);
          break;
        case 'resolve_front_system':
          got = core.resolveFrontSystem(args[0] as FrontSystem);
          break;
        case 'fold_front_system':
          got = core.foldFrontSystem(args[0] as string, args[1] as unknown[]);
          break;
        case 'front_effective_history':
          got = core.frontEffectiveHistory(args[0] as string, args[1] as string, args[2] as unknown[]);
          break;
        case 'named_front_daily':
          got = core.namedFrontDaily(args[0] as FrontHistoryInterval[], args[1] as number, args[2] as [number, number][]);
          break;
        case 'read_readers_for_front':
          got = core.readReadersForFront(args[0] as boolean, args[1] as FrontPresence[], args[2] as string[]);
          break;
        case 'default_speaker_for_front':
          got = core.defaultSpeakerForFront(args[0] as SpeakerContext, args[1] as FrontSystem, args[2] as string, args[3] as string);
          break;
        default: throw new Error(`Unsupported front fixture: ${data.fn}`);
      }
      expect(got).toEqual(data.expect);
    });
  }

  it('keeps following when saved history is amended, undone or restored', () => {
    const fixture = fixtures.find((f) => f.name === 'front_history/resume_keeps_saved_history.json')!.data;
    const account = fixture.args[0] as string;
    const ops = structuredClone(fixture.args[1]) as { id: string; kind: string; entity_id: string; [key: string]: unknown }[];
    const changed = ops.find((o) => o.kind === 'front.change')!;
    ops.push({ id: '0192f8c2-0000-7000-8000-000000000006', kind: 'front.change', entity_id: changed.entity_id,
      scope: `account:${account}`, hlc: '000000001770-0000-00000001', device_at: 6000,
      payload: { action: 'amend', target_op_id: changed.id, note: 'A history correction' } });
    const corrected = core.foldFrontSystem(account, ops);
    expect(corrected.resolved[changed.entity_id].state).toBe('following');
    expect(corrected.histories[changed.entity_id].switches[0].note).toBe('A history correction');
    for (const [index, action] of ['retract', 'unretract'].entries()) {
      const time = 7000 + index * 1000;
      ops.push({ id: `0192f8c2-0000-7000-8000-${(7 + index).toString().padStart(12, '0')}`,
        kind: 'front.change', entity_id: changed.entity_id, scope: `account:${account}`,
        hlc: `${time.toString(16).padStart(12, '0')}-0000-00000001`, device_at: time,
        payload: { action, target_op_id: changed.id } });
      const result = core.foldFrontSystem(account, ops);
      expect(result.resolved[changed.entity_id].state).toBe('following');
      expect(result.resolved[changed.entity_id].effective).toEqual(corrected.resolved[changed.entity_id].effective);
      expect(result.histories[changed.entity_id].switches[0].retracted).toBe(action === 'retract');
    }
  });
});
