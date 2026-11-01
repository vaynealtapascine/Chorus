import { beforeAll, describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { core, loadCore } from './core';
import { editDraft, inputDraft, markupDraft, parseComposer, updateDraft, mentionAt, mentionChoices, replaceSelection } from './chat-composer';
beforeAll(async () => { await loadCore(readFileSync(new URL('./core/pkg/chorus_wasm_bg.wasm', import.meta.url))); });

describe('chat composer', () => {
  it('keeps duplicate member and group names bound to separate stable IDs through core', () => {
    const choices = mentionChoices([{ id: 'member-1', name: 'Kai' }, { id: 'member-2', name: 'Kai' }], [{ id: 'group-1', name: 'Kai' }]);
    expect(new Set(choices.map((m) => m.token)).size).toBe(4);
    const names = { mentions: Object.fromEntries(choices.map((m) => [m.token.toLowerCase(), { target_type: m.type, target_id: m.id || null }])) };
    for (const choice of choices) {
      const rich = core.parseMarkup(`🌌 @${choice.token}`, names);
      expect(rich.entities).toContainEqual(expect.objectContaining({ type: 'mention', offset: 3, target_type: choice.type, ...(choice.id ? { target_id: choice.id } : {}) }));
    }
  });
  it('retains distinct identities of identically named mentions and overlapping formatting when editing', () => {
    const ids = ['019a3f23-77e1-7000-8000-000000000001', '019a3f23-77e1-7000-8000-000000000002'];
    const choices = mentionChoices(ids.map((id) => ({ id, name: 'Kai' })), []);
    const rich = { text: '🌌 @Kai @Kai', entities: [
      { type: 'mention', offset: 3, length: 4, target_type: 'member', target_id: ids[0] },
      { type: 'mention', offset: 8, length: 4, target_type: 'member', target_id: ids[1] },
      { type: 'bold', offset: 3, length: 9 },
    ], segments: [{ offset: 0, length: 12, authors: ['author'] }] };
    const names = { mentions: Object.fromEntries(choices.map((m) => [m.token.toLowerCase(), { target_type: m.type, target_id: m.id || null }])) };
    const draft = markupDraft(rich);
    expect(draft.text).toBe('🌌 **@Kai @Kai**');
    const parsed = parseComposer(draft, names).rich;
    expect(parsed.entities.filter((e) => e.type === 'mention').map((e) => e.target_id)).toEqual(ids);
    expect(parsed.entities.find((e) => e.type === 'bold')?.length).toBe(rich.text.length - 3);
    expect(parsed.text).toBe(rich.text);
  });
  it('keeps human-readable duplicate names bound while composing, including speaker prefixes and UTF-16 segments', () => {
    const draft = { text: '🌙 **@Kai**\n🦊 @Kai', entities: [
      { type: 'mention', offset: 5, length: 4, target_type: 'member', target_id: 'id-one' },
      { type: 'mention', offset: 15, length: 4, target_type: 'member', target_id: 'id-two' },
    ] };
    const parsed = parseComposer(draft, {}, { speakers: [
      { member_id: 'moon', sigils: ['🌙'], proxy_tags: [] }, { member_id: 'fox', sigils: ['🦊'], proxy_tags: [] },
    ], options: { segments: true }, defaults: [] });
    expect(parsed.rich.text).toBe('@Kai\n@Kai');
    expect(parsed.rich.entities.filter((e) => e.type === 'mention').map((e) => e.target_id)).toEqual(['id-one', 'id-two']);
    expect(parsed.segments).toEqual([{ offset: 0, length: 4, authors: ['moon'] }, { offset: 5, length: 4, authors: ['fox'] }]);
  });
  it('moves intact bindings around Unicode insertion and invalidates edited or replaced tokens', () => {
    const draft = { text: '@Kai @Kai', entities: [{ type: 'mention', offset: 5, length: 4, target_type: 'member', target_id: 'second' }] };
    const inserted = editDraft(draft, 0, 0, '🌌 ');
    expect(inserted.entities[0].offset).toBe(8);
    expect(parseComposer(inserted, {}).rich.entities[0]).toMatchObject({ offset: 8, target_id: 'second' });
    expect(updateDraft(inserted, '🌌 @Kai @Koi').entities).toEqual([]);
    expect(editDraft(draft, 5, 9, '@Kai').entities).toEqual([]);
  });
  it('retains custom emoji IDs after aliases are renamed or removed', () => {
    const draft = markupDraft({ text: 'hi :old_name:', entities: [{ type: 'custom_emoji', offset: 3, length: 10, emoji_id: 'opaque-emoji' }] });
    expect(parseComposer(updateDraft(draft, 'hello :old_name:'), {}).rich.entities).toEqual([{ type: 'custom_emoji', offset: 6, length: 10, emoji_id: 'opaque-emoji' }]);
  });
  it('uses actual DOM replacement ranges when repeated labels make text diffs ambiguous', () => {
    const draft = { text: '@Kai @Kai', entities: [{ type: 'mention', offset: 5, length: 4, target_type: 'member', target_id: 'second' }] };
    const removedFirst = inputDraft(draft, '@Kai', { from: 0, to: 5, type: 'deleteContentBackward' });
    expect(parseComposer(removedFirst, {}).rich.entities[0]).toMatchObject({ target_id: 'second', offset: 0 });
    const replacedSecond = inputDraft(draft, '@Kai @Kai', { from: 5, to: 9, type: 'insertFromPaste' });
    expect(replacedSecond.entities).toEqual([]);
  });
  it('replaces a mid-draft mention using DOM UTF-16 cursor units and retains suffix', () => {
    const text = '🌌 @ka rest';
    const range = mentionAt(text, 6)!;
    expect(range).toEqual({ from: 3, to: 6, query: 'ka' });
    expect(replaceSelection(text, range.from, range.to, '@Kai ')).toEqual({ text: '🌌 @Kai  rest', cursor: 8 });
    expect(mentionAt('email@kai', 9)).toBeNull();
    expect(mentionAt('@kai', 1, 4)).toBeNull();
    expect(replaceSelection('a🌌z', 1, 3, '❤️')).toEqual({ text: 'a❤️z', cursor: 3 });
  });
});
