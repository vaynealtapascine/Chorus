import { describe, expect, it } from 'vitest';
import { active, chord, enter, fence, inFence, inline, insert, link, linkProblem, lines, lineStyle, spoilerBlock, style, wordCount } from './format-text';

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

describe('Enter in lists and quotes', () => {
  it('continues a bullet, a hand-typed dash or star, and a quote', () => {
    expect(enter(sel('• one', 5))).toEqual({ text: '• one\n• ', start: 8, end: 8 });
    expect(enter(sel('- one', 5))).toEqual({ text: '- one\n- ', start: 8, end: 8 });
    expect(enter(sel('* one', 5))).toEqual({ text: '* one\n* ', start: 8, end: 8 });
    expect(enter(sel('> said', 6))).toEqual({ text: '> said\n> ', start: 9, end: 9 });
  });
  it('numbers the next item', () => {
    expect(enter(sel('1. a\n2. b', 9))).toEqual({ text: '1. a\n2. b\n3. ', start: 13, end: 13 });
    expect(enter(sel('9. a', 4))?.text).toBe('9. a\n10. ');
  });
  it('moves the text after the caret to the new item', () => {
    expect(enter(sel('• ab', 3))).toEqual({ text: '• a\n• b', start: 6, end: 6 });
    expect(enter(sel('• ab\nrest', 4))).toEqual({ text: '• ab\n• \nrest', start: 7, end: 7 });
  });
  it('ends the list on an empty item, leaving the caret on the empty line', () => {
    expect(enter(sel('• a\n• ', 6))).toEqual({ text: '• a\n', start: 4, end: 4 });
    expect(enter(sel('1. a\n2. \nz', 8))).toEqual({ text: '1. a\n\nz', start: 5, end: 5 });
    expect(enter(sel('> ', 2))).toEqual({ text: '', start: 0, end: 0 });
  });
  it('leaves everything else to the browser', () => {
    expect(enter(sel('plain', 5))).toBeNull();
    expect(enter(sel('# head', 6))).toBeNull();
    expect(enter(sel('## head', 7))).toBeNull();
    expect(enter(sel('• one', 0, 5))).toBeNull(); // a range
    expect(enter(sel('• one', 1))).toBeNull(); // inside the marker
    expect(enter(sel('```\n• a', 7))).toBeNull(); // in a code block
    expect(enter(sel('•x', 2))).toBeNull(); // no space: not a bullet
  });
});

describe('editor key chords', () => {
  const k = (key: string, o: Partial<{ ctrlKey: boolean; metaKey: boolean; shiftKey: boolean; altKey: boolean }> = {}) =>
    chord({ key, ctrlKey: false, metaKey: false, shiftKey: false, altKey: false, ...o });
  it('maps ctrl or cmd plus a letter', () => {
    for (const mod of [{ ctrlKey: true }, { metaKey: true }]) {
      expect(k('b', mod)).toBe('**');
      expect(k('i', mod)).toBe('_');
      expect(k('k', mod)).toBe('link');
      expect(k('e', mod)).toBe('`');
      expect(k('Enter', mod)).toBe('post');
      expect(k('B', mod)).toBe('**');
      expect(k('X', { ...mod, shiftKey: true })).toBe('~~');
      expect(k('x', { ...mod, shiftKey: true })).toBe('~~');
    }
  });
  it('ignores anything else', () => {
    expect(k('b')).toBeNull();
    expect(k('b', { ctrlKey: true, metaKey: true })).toBeNull();
    expect(k('b', { ctrlKey: true, altKey: true })).toBeNull();
    expect(k('b', { ctrlKey: true, shiftKey: true })).toBeNull();
    expect(k('x', { ctrlKey: true })).toBeNull();
    expect(k('z', { ctrlKey: true })).toBeNull();
  });
});
