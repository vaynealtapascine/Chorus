import { describe, expect, it } from 'vitest';
import { classes, href, pieces } from './RichText.svelte';

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
});
