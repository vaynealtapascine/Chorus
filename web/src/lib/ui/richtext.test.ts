import { render } from 'svelte/server';
import { describe, expect, it } from 'vitest';
import RichText, { blocks, classes, href, pieces } from './RichText.svelte';

describe('rich text pieces', () => {
  it('splits at entity boundaries with UTF-16 offsets', () => {
    // "🌌 hi" — the emoji is 2 UTF-16 units, so "hi" starts at 3
    const p = pieces('🌌 hi', [{ type: 'bold', offset: 3, length: 2 }]);
    expect(p.map((x) => x.text)).toEqual(['🌌 ', 'hi']);
    expect(classes(p[1].marks)).toBe('b');
  });

  it('nests and only allows safe links', () => {
    const text = 'see docs now';
    const p = pieces(text, [
      { type: 'text_link', offset: 4, length: 4, url: 'https://x.org' },
      { type: 'italic', offset: 0, length: 12 },
    ]);
    const docs = p.find((x) => x.text === 'docs')!;
    expect(href(docs.marks, docs.text)).toBe('https://x.org');
    expect(classes(docs.marks)).toBe('i');
    expect(href([{ type: 'text_link', offset: 0, length: 1, url: 'javascript:alert(1)' }], 'x')).toBeNull();
  });

  it('groups a heading line into one heading, inline marks inside (D-134)', () => {
    const text = 'Saturday\nThe plan\nbody';
    const entities = [
      { type: 'heading', level: 1, offset: 0, length: 8 },
      { type: 'heading', level: 2, offset: 9, length: 8 },
      { type: 'bold', offset: 13, length: 4 },
    ];
    const b = blocks(pieces(text, entities));
    expect(b.map((x) => [x.level, x.parts.map(([p]) => p.text).join('')])).toEqual([[1, 'Saturday'], [0, '\n'], [2, 'The plan'], [0, '\nbody']]);
    const html = render(RichText, { props: { text, entities } }).body.replace(/<!--[^>]*-->/g, '');
    expect(html).toMatch(/<span class="h1[^"]*" role="heading" aria-level="3">Saturday<\/span>/);
    expect(html).toMatch(/<span class="h2[^"]*" role="heading" aria-level="4">The <span class="b[^"]*">plan<\/span><\/span>/);
    // an unknown level is plain text
    expect(blocks(pieces('x', [{ type: 'heading', level: 3, offset: 0, length: 1 }]))[0].level).toBe(0);
  });
});
