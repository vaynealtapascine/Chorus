import { describe, expect, it } from 'vitest';
import { blackoutParts, paletteStyle } from './stage-text';

describe('stage black-outs', () => {
  it('cuts the text into plain and black runs', () => {
    expect(blackoutParts('Meet at the clinic now', [[0, 4], [12, 18]])).toEqual([
      { text: 'Meet', black: true }, { text: ' at the ', black: false }, { text: 'clinic', black: true }, { text: ' now', black: false },
    ]);
  });
  it('clamps ranges past the end and counts UTF-16 units', () => {
    expect(blackoutParts('hi 👋 there', [[3, 5], [9, 99]])).toEqual([
      { text: 'hi ', black: false }, { text: '👋', black: true }, { text: ' the', black: false }, { text: 're', black: true },
    ]);
    expect(blackoutParts('abc', [])).toEqual([{ text: 'abc', black: false }]);
  });
  it('turns a custom palette into canvas variables', () => {
    expect(paletteStyle({ dark: false, bg: '#f4efe6', ink2: '#5d554f' })).toBe('--bg: #f4efe6; --ink-2: #5d554f');
    expect(paletteStyle(undefined)).toBe('');
  });
});
