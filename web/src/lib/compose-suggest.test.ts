import { describe, expect, it } from 'vitest';
import { applySuggestion, suggest, suggestionAt, type Suggestion } from './compose-suggest';

const sel = (text: string, start: number, end = start) => ({ text, start, end });
const m = (name: string, handle: string): Suggestion => ({ id: handle, name, handle });
const members = [m('Marigold', 'marigold.lanterns'), m('Kai', 'kai.lanterns'), m('Foxglove', 'foxglove.lanterns'), m('Ari', 'goldie')];

describe('@ suggestions', () => {
  it('finds the word being typed', () => {
    expect(suggestionAt(sel('hi @ka', 6))).toEqual({ from: 3, to: 6, query: 'ka' });
    expect(suggestionAt(sel('@', 1))).toEqual({ from: 0, to: 1, query: '' });
    expect(suggestionAt(sel('mail a@b', 8))).toBeNull();
    expect(suggestionAt(sel('hi @ka', 3, 6))).toBeNull();
    expect(suggestionAt(sel('hi @ka there', 12))).toBeNull();
  });

  it('lists prefix matches before substring matches, case-insensitively', () => {
    expect(suggest('gold', members).map((c) => c.name)).toEqual(['Ari', 'Marigold']);
    expect(suggest('GLOVE', members).map((c) => c.name)).toEqual(['Foxglove']);
    expect(suggest('mar', members).map((c) => c.name)).toEqual(['Marigold']);
    expect(suggest('lan', members).map((c) => c.name)).toEqual(['Marigold', 'Kai', 'Foxglove']);
    expect(suggest('zzz', members)).toEqual([]);
  });

  it('puts a prefix match ahead of an earlier substring match', () => {
    const list = [m('Bigfoot', 'bigfoot'), m('Foot', 'foot')];
    expect(suggest('foot', list).map((c) => c.name)).toEqual(['Foot', 'Bigfoot']);
  });

  it('lists the first choices in order for an empty query, up to the limit', () => {
    expect(suggest('', members).map((c) => c.name)).toEqual(['Marigold', 'Kai', 'Foxglove', 'Ari']);
    expect(suggest('', members, 2)).toHaveLength(2);
    expect(suggest('lan', members, 1)).toHaveLength(1);
    expect(suggest('', Array.from({ length: 9 }, (_, i) => m(`n${i}`, `h${i}`)))).toHaveLength(6);
  });

  it('replaces the word with the handle and a space, caret after', () => {
    const v = sel('hi @ka and', 6);
    expect(applySuggestion(v, suggestionAt(v)!, 'kai.lanterns')).toEqual({ text: 'hi @kai.lanterns  and', start: 17, end: 17 });
    const w = sel('@m', 2);
    expect(applySuggestion(w, { from: 0, to: 2 }, 'mo')).toEqual({ text: '@mo ', start: 4, end: 4 });
  });
});
