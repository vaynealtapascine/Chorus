import { describe, expect, it } from 'vitest';
import { active, fence, inFence, inline, insert, link, linkProblem, lines, lineStyle, spoilerBlock, style, wordCount } from './format-text';

const sel = (text: string, start: number, end = start) => ({ text, start, end });

describe('composer formatting', () => {
  it('wraps and unwraps a selection, and says when it is wrapped', () => {
    const on = inline(sel('say hi now', 4, 6), '**');
    expect(on).toEqual({ text: 'say **hi** now', start: 6, end: 8 });
    expect(active(on, '**')).toBe(true);
    expect(inline(on, '**')).toEqual({ text: 'say hi now', start: 4, end: 6 });
  });

  it('prefixes each selected line, numbering when asked', () => {
    expect(lines(sel('a\nb\nc', 0, 3), '• ').text).toBe('• a\n• b\nc');
    expect(lines(sel('a\nb\nc', 2, 5), '', true).text).toBe('a\n1. b\n2. c');
  });

  it('sets a heading on the caret line only, and swaps styles', () => {
    const h = style(sel('one\ntwo', 5), 'heading1');
    expect(h.text).toBe('one\n# two');
    expect(lineStyle(h)).toBe('heading1');
    expect(style(h, 'heading2').text).toBe('one\n## two');
    expect(style(h, '').text).toBe('one\ntwo');
    expect(style(sel('a\nb', 0, 3), 'quote').text).toBe('> a\n> b');
  });

  it('wraps lines in a code block and takes the block off again', () => {
    const f = fence(sel('x\ncode\ny', 3));
    expect(f.text).toBe('x\n```\ncode\n```\ny');
    expect(inFence(sel(f.text, 8))).toBe(true);
    expect(fence(sel(f.text, 8)).text).toBe('x\ncode\ny');
    expect(spoilerBlock(sel('hush', 0)).text).toBe('||hush||');
  });

  it('builds links only from safe addresses', () => {
    expect(link(sel('see here', 4, 8), 'https://x.org')).toEqual({ text: 'see [here](https://x.org)', start: 5, end: 9 });
    expect(link(sel('', 0), 'https://x.org').text).toBe('[https://x.org](https://x.org)');
    expect(linkProblem('javascript:alert(1)')).not.toBeNull();
    expect(linkProblem('https://a b')).not.toBeNull();
    expect(linkProblem('https://a.org/x)')).not.toBeNull();
    expect(linkProblem('mailto:a@b.c')).toBeNull();
  });

  it('inserts at the caret and counts words', () => {
    expect(insert(sel('ab', 1), '🌈')).toEqual({ text: 'a🌈b', start: 3, end: 3 });
    expect(wordCount('  a  b\nc ')).toBe(3);
    expect(wordCount('')).toBe(0);
  });
});
