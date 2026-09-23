import { describe, expect, it } from 'vitest';
import { fuzzyWhen, precisionOfRule } from './fuzz';

const now = new Date(2026, 8, 23, 21, 0);
const at = (d: number, h: number, m = 0) => new Date(2026, 8, d, h, m).getTime();

describe('fuzzyWhen', () => {
  it('never says more than the precision allows', () => {
    expect(fuzzyWhen(at(23, 19, 30), 'part_of_day', null, now)).toBe('this evening');
    expect(fuzzyWhen(at(22, 8), 'part_of_day', 'morning', now)).toBe('yesterday morning');
    expect(fuzzyWhen(at(22, 23), 'part_of_day', null, now)).toBe('last night');
    expect(fuzzyWhen(at(23, 19, 30), 'approx', null, now)).toMatch(/^around /);
    expect(fuzzyWhen(at(23, 19, 30), 'exact', null, now)).toMatch(/^at /);
    expect(fuzzyWhen(at(23, 19, 30), 'none', null, now)).toBe('');
    expect(fuzzyWhen(null, 'exact', null, now)).toBe('');
  });
  it('maps time rules', () => {
    expect(precisionOfRule({ mode: 'round' })).toBe('approx');
    expect(precisionOfRule({ mode: 'hidden' })).toBe('none');
    expect(precisionOfRule(undefined)).toBe('none');
  });
});
