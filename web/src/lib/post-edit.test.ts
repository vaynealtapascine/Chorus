import { beforeAll, describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { initSync } from './core/pkg/chorus_wasm';
import { openPostEdit, updatePostEdit, parsePostEdit } from './post-edit';
import fixture from '../../../fixtures/ui_post_edit.json';

beforeAll(() => { initSync({ module: readFileSync(new URL('./core/pkg/chorus_wasm_bg.wasm', import.meta.url)) }); });
describe('post rich editing', () => {
  for (const c of fixture.cases) it(c.name, () => {
    const state = openPostEdit({ text: fixture.text, entities: fixture.entities });
    expect(parsePostEdit(updatePostEdit(state, c.draft, c.start, c.end)).entities.map((e) => e.target_id)).toEqual(c.ids);
  });
  const original = { text: '@Kai @Kai', entities: [
    { type: 'mention', offset: 0, length: 4, target_type: 'member', target_id: 'first' },
    { type: 'mention', offset: 5, length: 4, target_type: 'member', target_id: 'second' },
  ] };
  it('preserves unchanged identities and distinguishes identical selected tokens', () => {
    const state = openPostEdit(original);
    const removed = updatePostEdit(state, '@Kai', 0, 5);
    expect(parsePostEdit(removed).entities.map((e) => e.target_id)).toEqual(['second']);
    const retyped = updatePostEdit(state, state.markup, 0, 4);
    expect(parsePostEdit(retyped).entities.map((e) => e.target_id)).toEqual(['second']);
    const appended = updatePostEdit(state, `${state.markup}!`, state.markup.length, state.markup.length);
    expect(parsePostEdit(appended).entities.map((e) => e.target_id)).toEqual(['first', 'second']);
  });
});
