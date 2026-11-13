// `@` suggestions for the Journal text field: our own members are mentioned by typing `@handle` (D-126).
import { mentionAt, replaceSelection } from './chat-composer';
import type { Sel } from './format-text';

export interface Suggestion { id: string; name: string; handle: string }

/** The @word being typed at the caret, if any. */
export function suggestionAt(v: Sel): { from: number; to: number; query: string } | null {
  return mentionAt(v.text, v.start, v.end);
}

/** Our members whose name or handle matches, best first (prefix matches before substring). */
export function suggest(query: string, choices: Suggestion[], limit = 6): Suggestion[] {
  const q = query.trim().toLowerCase().replace(/^@/, '');
  if (!q) return choices.slice(0, limit);
  const rank = (c: Suggestion) => {
    const name = c.name.toLowerCase(), handle = c.handle.toLowerCase();
    if (handle.startsWith(q) || name.startsWith(q)) return 0;
    return handle.includes(q) || name.includes(q) ? 1 : 2;
  };
  return choices.map((c, i) => ({ c, i, r: rank(c) })).filter((x) => x.r < 2)
    .sort((a, b) => a.r - b.r || a.i - b.i).slice(0, limit).map((x) => x.c);
}

/** The text with the @word replaced by `@handle ` and the caret after it. */
export function applySuggestion(v: Sel, at: { from: number; to: number }, handle: string): Sel {
  const r = replaceSelection(v.text, at.from, at.to, `@${handle} `);
  return { text: r.text, start: r.cursor, end: r.cursor };
}
