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
export function updatePostEdit(state: PostEditState, markup: string, start?: number, end?: number): PostEditState {
  const old = state.markup;
  let prefix = 0;
  const maxPrefix = start ?? Math.min(old.length, markup.length);
  while (prefix < maxPrefix && prefix < markup.length && old[prefix] === markup[prefix]) prefix++;
  let suffix = 0;
  const maxSuffix = end == null ? old.length - prefix : old.length - end;
  while (suffix < maxSuffix && suffix < old.length - prefix && suffix < markup.length - prefix && old[old.length - suffix - 1] === markup[markup.length - suffix - 1]) suffix++;
  return { ...state, markup, origins: [...state.origins.slice(0, prefix), ...Array<number>(markup.length - prefix - suffix).fill(-1), ...(suffix ? state.origins.slice(-suffix) : [])] };
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
    if (e.type === 'mention' && /[\p{L}\p{N}_]/u.test(parsed.text[at + e.length] ?? '')) continue;
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
