import { describe, expect, it } from 'vitest';
import { blackParts, dragBlackout, fresh, loadDef, maskText, mergeRanges, redacted, shiftLabel, tapBlackout, wordAt, withBlackout } from './stage-def';

describe('stage definition', () => {
  it('opens a stage saved without the newer fields', () => {
    const d = loadDef({ selected: ['a'], render: { style: 'card' }, channel_id: 'c' });
    expect(d.selected).toEqual(['a']);
    expect(d.render).toMatchObject({ style: 'card', theme: 'auto', width: 'phone', hide_header: false });
    expect(d.blackout).toEqual({});
    expect(d.time).toEqual({ mode: 'real' });
  });
  it('gives a custom theme without a palette the default one', () => {
    expect(loadDef({ render: { theme: 'custom' } }).render.palette).toMatchObject({ bg: '#F4EFE6' });
  });
  it('knows what is redacted', () => {
    expect(redacted(fresh())).toBe(false);
    expect(redacted({ ...fresh(), blackout: { m: [[0, 2]] } })).toBe(true);
    expect(redacted({ ...fresh(), time: { mode: 'hide' } })).toBe(true);
  });
  it('writes a shift as hours and minutes', () => {
    expect([0, 15, 75, -30, 120].map(shiftLabel)).toEqual(['0 min', '+15 min', '+1 h 15 min', '−30 min', '+2 h']);
  });
});

describe('black-outs', () => {
  it('finds the word around a position, none on a space', () => {
    expect(wordAt('Meet at the clinic', 13)).toEqual([12, 18]);
    expect(wordAt('Meet at', 4)).toEqual([0, 4]);
    expect(wordAt('a  b', 2)).toBeNull();
    expect(wordAt('', 0)).toBeNull();
  });
  it('a tap blacks out a word, and a second tap gives it back', () => {
    const once = tapBlackout('Meet at the clinic', [], 14);
    expect(once).toEqual([[12, 18]]);
    expect(tapBlackout('Meet at the clinic', once, 14)).toEqual([]);
  });
  it('a drag blacks out whole words at both ends', () => {
    expect(dragBlackout('Meet at the clinic now', [], 6, 14)).toEqual([[5, 18]]);
    expect(dragBlackout('Meet at the clinic now', [], 14, 6)).toEqual([[5, 18]]);
  });
  it('merges overlapping ranges and drops empty ones', () => {
    expect(mergeRanges([[5, 8], [0, 3], [2, 4], [9, 9], [8, 10]])).toEqual([[0, 4], [5, 10]]);
  });
  it('never leaves a blacked-out word in the text of a capture', () => {
    expect(maskText('Meet at the clinic', [[12, 18]])).toBe('Meet at the ██████');
    const shown = blackParts('Meet at the clinic', [[12, 18]], false);
    expect(shown.map((p) => p.text).join('')).toBe('Meet at the ██████');
    expect(shown.map((p) => p.text).join('')).not.toContain('clinic');
    expect(blackParts('Meet at the clinic', [[12, 18]], true).at(-1)).toEqual({ text: 'clinic', black: true });
  });
  it('keeps no entry for a message with nothing blacked out', () => {
    const d = { ...fresh(), blackout: { a: [[0, 2]] as [number, number][] } };
    expect(withBlackout(d, 'a', [])).toEqual({});
    expect(withBlackout(d, 'b', [[1, 2]])).toEqual({ a: [[0, 2]], b: [[1, 2]] });
  });
});
