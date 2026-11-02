import { core, type Entity, type Rich } from './core';
import { markupSources } from './core/pkg/chorus_wasm';

interface Mapped extends Rich { sources: number[] }
export interface PostEditState { markup: string; original: Rich; origins: number[] }
const mapped = (src: string): Mapped => ({ ...core.parseMarkup(src), sources: Array.from(markupSources(src)) });
const atomic = (e: Entity) => e.type === 'mention' || e.type === 'custom_emoji';

export function openPostEdit(original: Rich): PostEditState {
  const markup = core.toMarkup(original);
  const parsed = mapped(markup);
  if (parsed.text !== original.text) throw new Error('This rich text could not be opened safely.');
  const origins = Array<number>(markup.length).fill(-1);
  parsed.sources.forEach((source, i) => { origins[source] = i; });
  return { markup, original, origins };
}

/** Retain only characters outside the actual input replacement. Selection bounds disambiguate
 * identical tokens; newly typed/retyped characters can never acquire an old target id. */
export function updatePostEdit(state: PostEditState, markup: string, start?: number, end?: number, inputType?: string, caret?: number): PostEditState {
  const old = state.markup;
  const removed = old.length - markup.length;
  if (start === end && start != null && removed > 0) {
    if (inputType?.endsWith('Backward')) start -= removed;
    else if (inputType?.endsWith('Forward')) end = start + removed;
    else start = undefined;
    // The resulting caret confirms the browser's actual deletion, including whole graphemes.
    if (caret != null && caret !== start) start = undefined;
  }
  if (start != null && end != null && start >= 0 && end >= start && end <= old.length) {
    const inserted = markup.length - (old.length - (end - start));
    if (inserted >= 0 && markup.slice(0, start) === old.slice(0, start) && markup.slice(start + inserted) === old.slice(end)) {
      return { ...state, markup, origins: [...state.origins.slice(0, start), ...Array<number>(inserted).fill(-1), ...state.origins.slice(end)] };
    }
  }
  // Missing beforeinput / IME rewrites / undo: text equality cannot prove occurrence identity.
  return { ...state, markup, origins: Array<number>(markup.length).fill(-1) };
}

/** Same token boundaries as core's mention lexer and native RichTextEdit. */
function wholeMention(text: string, start: number, length: number): boolean {
  const word = (c: string) => /[\p{Alphabetic}\p{N}]/u.test(c);
  const before = Array.from(text.slice(Math.max(0, start - 2), start)).at(-1);
  if (before && word(before)) return false;
  const originalEnd = start + length;
  let end = originalEnd;
  while (end < text.length) {
    const c = String.fromCodePoint(text.codePointAt(end)!);
    if (!word(c) && !'_.-'.includes(c)) break;
    end += c.length;
  }
  while (end > originalEnd && '.-'.includes(text[end - 1])) end--;
  return end === originalEnd;
}

export function parsePostEdit(state: PostEditState): Rich {
  const parsed = mapped(state.markup);
  const positions = new Map<number, number[]>();
  parsed.sources.forEach((source, i) => {
    const origin = state.origins[source];
    if (origin >= 0) positions.set(origin, [...(positions.get(origin) ?? []), i]);
  });
  for (const e of state.original.entities.filter(atomic)) {
    const starts = positions.get(e.offset);
    if (starts?.length !== 1) continue;
    const at = starts[0];
    if (e.type === 'mention' && !wholeMention(parsed.text, at, e.length)) continue;
    if (Array.from({ length: e.length }, (_, i) => positions.get(e.offset + i)?.length === 1 && positions.get(e.offset + i)?.[0] === at + i).some((v) => !v)) continue;
    if (parsed.text.slice(at, at + e.length) !== state.original.text.slice(e.offset, e.offset + e.length)) continue;
    if (parsed.entities.some((n) => ['code', 'pre', 'mention', 'custom_emoji'].includes(n.type) && n.offset < at + e.length && n.offset + n.length > at)) continue;
    const source = parsed.sources[at];
    let escapes = 0;
    while (state.markup[source - escapes - 1] === '\\') escapes++;
    if (escapes % 2) continue;
    parsed.entities.push({ ...e, offset: at });
  }
  return { text: parsed.text, entities: parsed.entities.sort((a, b) => a.offset - b.offset || b.length - a.length) };
}
