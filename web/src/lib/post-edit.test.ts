import { beforeAll, describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { initSync } from './core/pkg/chorus_wasm';
import { openPostEdit, updatePostEdit, parsePostEdit } from './post-edit';
import fixture from '../../../fixtures/ui_post_edit.json';
import { core } from './core';

beforeAll(() => { initSync({ module: readFileSync(new URL('./core/pkg/chorus_wasm_bg.wasm', import.meta.url)) }); });
describe('post rich editing', () => {
  for (const c of fixture.cases) it(c.name, () => {
    const state = openPostEdit({ text: fixture.text, entities: fixture.entities });
    expect(parsePostEdit(updatePostEdit(state, c.draft, c.start, c.end)).entities.map((e) => e.target_id)).toEqual(c.ids);
  });
  it('retains mentions only where the core lexer recognizes the complete token', () => {
    const spans = (rich: ReturnType<typeof parsePostEdit>) => rich.entities.filter((e) => e.type === 'mention').map(({ offset, length }) => ({ offset, length }));
    for (const c of fixture.cases.filter((c) => c.start === c.end)) {
      const state = openPostEdit({ text: fixture.text, entities: fixture.entities });
      const retained = parsePostEdit(updatePostEdit(state, c.draft, c.start, c.end));
      const lexed = core.parseMarkup(c.draft, { mentions: { Kai: { target_type: 'member', target_id: 'directory' } } });
      expect(spans(retained), c.name).toEqual(spans(lexed));
    }
  });
  const original = { text: '@Kai @Kai', entities: [
    { type: 'mention', offset: 0, length: 4, target_type: 'member', target_id: 'first' },
    { type: 'mention', offset: 5, length: 4, target_type: 'member', target_id: 'second' },
  ] };
  for (const token of ['🪴', '👩🏽‍💻']) {
    for (const [inputType, caret, after, id] of [
      ['deleteContentBackward', token.length, 0, 'second'],
      ['deleteContentForward', 0, 0, 'second'],
      ['deleteContentBackward', token.length * 2, token.length, 'first'],
      ['deleteContentForward', token.length, token.length, 'first'],
    ] as const) it(`${inputType} at ${caret} preserves the surviving ${token} occurrence`, () => {
      const state = openPostEdit({ text: token.repeat(2), entities: [
        { type: 'custom_emoji', offset: 0, length: token.length, emoji_id: 'first' },
        { type: 'custom_emoji', offset: token.length, length: token.length, emoji_id: 'second' },
      ] });
      expect(state.markup).toBe(token.repeat(2));
      expect(parsePostEdit(updatePostEdit(state, token, caret, caret, inputType, after)).entities.map((e) => e.emoji_id)).toEqual([id]);
    });
  }
  it('does not guess identities without an actual replacement range', () => {
    const state = openPostEdit(original);
    expect(parsePostEdit(updatePostEdit(state, '@Kai')).entities).toEqual([]);
  });
  it('preserves unchanged identities and distinguishes identical selected tokens', () => {
    const state = openPostEdit(original);
    const removed = updatePostEdit(state, '@Kai', 0, 5);
    expect(parsePostEdit(removed).entities.map((e) => e.target_id)).toEqual(['second']);
    const retyped = updatePostEdit(state, state.markup, 0, 4);
    expect(parsePostEdit(retyped).entities.map((e) => e.target_id)).toEqual(['second']);
    const appended = updatePostEdit(state, `${state.markup}!`, state.markup.length, state.markup.length);
    expect(parsePostEdit(appended).entities.map((e) => e.target_id)).toEqual(['first', 'second']);
  });

  it('opens and saves headings unchanged, and keeps a literal "# " line literal (D-134)', () => {
    const rich = { text: 'Saturday\nbody\n# not a heading', entities: [{ type: 'heading', level: 1, offset: 0, length: 8 }] };
    const state = openPostEdit(rich);
    expect(state.markup).toBe('# Saturday\nbody\n\\# not a heading');
    expect(parsePostEdit(state)).toEqual(rich);
    const typed = parsePostEdit(updatePostEdit(state, `## Sunday\n${state.markup}`, 0, 0));
    expect(typed.entities.filter((e) => e.type === 'heading').map((e) => [e.level, e.offset, e.length])).toEqual([[2, 0, 6], [1, 7, 8]]);
  });
});
