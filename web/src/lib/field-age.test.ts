import { expect, it } from 'vitest';
import { ageOf, fieldKeys, nextFieldKey } from './field-age';

const now = new Date(2026, 10, 12);
it('turns a birth date into an age, before and after the birthday', () => {
  expect(ageOf('1999-11-12', now)).toBe('27');
  expect(ageOf('1999-11-13', now)).toBe('26');
  expect(ageOf('--11-12', now)).toBeNull();
  expect(ageOf('2999-01-01', now)).toBeNull();
  expect(ageOf(5, now)).toBeNull();
});
it('keeps fields in a fixed order', () => {
  expect(fieldKeys(3)).toEqual(['0001', '0002', '0003']);
  expect(nextFieldKey(['0001', '0004', undefined])).toBe('0005');
  expect(nextFieldKey([undefined])).toBeUndefined();
});
