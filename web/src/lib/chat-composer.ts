/** Stable, parser-safe mention tokens: duplicate names never choose an arbitrary member. */
import { core, type Composed, type Entity, type Rich, type Segment } from './core';
export interface MentionChoice {
  id: string; name: string; type: 'member' | 'group' | 'front'; token: string;
  /** A member's shown handle (D-126); `foreign` members (another account's) are mentioned by it. */
  handle?: string; foreign?: boolean;
}
const encodedId = (id: string) => id.split('').map((c) => c.charCodeAt(0).toString(16).padStart(4, '0')).join('');
export function mentionChoices(members: { id: string; name: string; handle?: string; foreign?: boolean }[], groups: { id: string; name: string }[]): MentionChoice[] {
  return [{ id: '', name: 'front', type: 'front', token: 'front' }, ...([
    ...members.map((m) => ({ ...m, type: 'member' as const })),
    ...groups.map((g) => ({ ...g, type: 'group' as const })),
  ]).map((m) => ({ ...m, token: `${m.name.replace(/[^\p{L}\p{N}_]/gu, '_') || m.type}__${m.type}_${encodedId(m.id)}` }))];
}
export function mentionAt(text: string, start: number, end = start): { from: number; to: number; query: string } | null {
  if (start !== end) return null;
  const match = text.slice(0, start).match(/(?:^|\s)@([^\s@]*)$/u);
  return match ? { from: start - match[1].length - 1, to: start, query: match[1].toLowerCase() } : null;
}
export function replaceSelection(text: string, from: number, to: number, insert: string): { text: string; cursor: number } {
  return { text: text.slice(0, from) + insert + text.slice(to), cursor: from + insert.length };
}
export interface ComposerDraft { text: string; entities: Entity[] }
export function editDraft(before: ComposerDraft, from: number, to: number, insert: string): ComposerDraft {
  const shift = insert.length - (to - from);
  return { text: before.text.slice(0, from) + insert + before.text.slice(to), entities: before.entities.flatMap((e) => {
    if (e.offset + e.length <= from) return [{ ...e }];
    if (e.offset >= to) return [{ ...e, offset: e.offset + shift }];
    return [];
  }) };
}
export function inputDraft(before: ComposerDraft, text: string, selection: { from: number; to: number; type: string } | null): ComposerDraft {
  if (!selection) return updateDraft(before, text);
  let { from, to } = selection;
  if (from === to && selection.type === 'deleteContentBackward' && from > 0) {
    from -= /[\uDC00-\uDFFF]/.test(before.text[from - 1]) && from > 1 ? 2 : 1;
  } else if (from === to && selection.type === 'deleteContentForward' && to < before.text.length) {
    to += /[\uD800-\uDBFF]/.test(before.text[to]) && to + 1 < before.text.length ? 2 : 1;
  }
  const insertedLength = text.length - before.text.length + to - from;
  if (insertedLength >= 0 && text.slice(0, from) === before.text.slice(0, from) && text.slice(from + insertedLength) === before.text.slice(to)) {
    return editDraft(before, from, to, text.slice(from, from + insertedLength));
  }
  return updateDraft(before, text);
}
/** Keep provenance only for intact tokens; edits inside a token deliberately remove its binding. */
export function updateDraft(before: ComposerDraft, text: string): ComposerDraft {
  if (before.text === text) return before;
  let start = 0;
  while (start < before.text.length && start < text.length && before.text[start] === text[start]) start++;
  let oldEnd = before.text.length, newEnd = text.length;
  while (oldEnd > start && newEnd > start && before.text[oldEnd - 1] === text[newEnd - 1]) { oldEnd--; newEnd--; }
  const shift = newEnd - oldEnd;
  return { text, entities: before.entities.flatMap((e) => {
    if (e.offset + e.length <= start) return [{ ...e }];
    if (e.offset >= oldEnd) return [{ ...e, offset: e.offset + shift }];
    return [];
  }) };
}
function rewrite<T extends Rich & { segments?: Segment[] }>(rich: T, changes: { offset: number; length: number; value: string }[]): T {
  const map = Array.from({ length: rich.text.length + 1 }, (_, i) => i);
  let text = '', at = 0;
  for (const r of [...changes].sort((a, b) => a.offset - b.offset)) {
    if (r.offset < at) continue;
    for (; at < r.offset; at++) { map[at] = text.length; text += rich.text[at]; }
    for (let i = 0; i < r.length; i++) map[at + i] = text.length + Math.min(i, r.value.length);
    text += r.value; at += r.length; map[at] = text.length;
  }
  for (; at < rich.text.length; at++) { map[at] = text.length; text += rich.text[at]; }
  map[at] = text.length;
  const remap = <R extends { offset: number; length: number }>(e: R): R => ({ ...e, offset: map[e.offset], length: map[e.offset + e.length] - map[e.offset] });
  return { ...rich, text, entities: rich.entities.map(remap), ...(rich.segments ? { segments: rich.segments.map(remap) } : {}) };
}
/** Alias tokens exist only while core parses markup. Stored/displayed text stays human-readable. */
function encodedDraft(draft: ComposerDraft) {
  let prefix = 'ui8_bound_';
  while (draft.text.includes(prefix)) prefix += '_';
  const bindings = draft.entities.filter((e) => e.type === 'mention' || e.type === 'custom_emoji').map((e, i) => ({
    entity: e, label: draft.text.slice(e.offset, e.offset + e.length), key: `${prefix}${i}`,
    value: e.type === 'mention' ? `@${prefix}${i}` : `:${prefix}${i}:`,
  }));
  return { bindings, source: rewrite(draft, bindings.map((b) => ({ ...b.entity, value: b.value }))).text,
    mentions: Object.fromEntries(bindings.filter((b) => b.entity.type === 'mention').map((b) => [b.key, { target_type: b.entity.target_type, target_id: b.entity.target_id ?? null }])),
    emoji: Object.fromEntries(bindings.filter((b) => b.entity.type === 'custom_emoji').map((b) => [b.key, b.entity.emoji_id])) };
}
export function parseComposer(draft: ComposerDraft, names: { mentions?: Record<string, unknown>; emoji?: Record<string, unknown> }, compose?: { speakers: unknown; options: unknown; defaults: string[] }): Composed {
  const encoded = encodedDraft(draft);
  const resolver = { mentions: { ...names.mentions, ...encoded.mentions }, emoji: { ...names.emoji, ...encoded.emoji } };
  const parsed = compose ? core.compose(encoded.source, compose.speakers, compose.options, compose.defaults, resolver)
    : { rich: core.parseMarkup(encoded.source, resolver), segments: [], authors: [], explicit: false };
  const combined = { ...parsed.rich, segments: parsed.segments };
  const restored = rewrite(combined, parsed.rich.entities.flatMap((e) => {
    const token = parsed.rich.text.slice(e.offset, e.offset + e.length);
    const binding = encoded.bindings.find((b) => b.value === token && b.entity.type === e.type);
    return binding ? [{ offset: e.offset, length: e.length, value: binding.label }] : [];
  }));
  return { ...parsed, rich: { text: restored.text, entities: restored.entities }, segments: restored.segments };
}
/** Export rich text to editable markup while retaining mention/custom-emoji identity by span. */
export function markupDraft(rich: Rich): ComposerDraft {
  const encoded = encodedDraft(rich);
  const tokenized = rewrite(rich, encoded.bindings.map((b) => ({ ...b.entity, value: b.value })));
  const markup = core.toMarkup(tokenized);
  const changes = encoded.bindings.flatMap((b) => {
    const offset = markup.indexOf(b.value);
    return offset < 0 ? [] : [{ ...b.entity, offset, length: b.value.length, value: b.label }];
  });
  return rewrite({ text: markup, entities: changes.map(({ value: _value, ...e }) => e) }, changes);
}
export const unicodeEmoji = ['😀', '😊', '🥰', '😂', '🥹', '😅', '😭', '🤔', '😴', '👀', '❤️', '🧡', '💛', '💚', '💙', '💜', '🫂', '👍', '👎', '👏', '🙌', '🙏', '✨', '🎉', '🌈', '🌙', '☀️', '🌻', '🐱', '🐾', '☕', '🔥'];
