import { expect, it } from 'vitest';
import { ACCENTS, appearanceSummary, DENSITIES, pick, TEXT_SIZES } from './appearance-prefs';

it('reads stored words as choices and falls back to the first', () => {
  expect(pick(ACCENTS, 'Lavender')).toBe('lavender');
  expect(pick(ACCENTS, 'plum')).toBe('teal');
  expect(pick(TEXT_SIZES, null)).toBe('default');
  expect(pick(DENSITIES, 'Compact')).toBe('compact');
});

it('summarises as theme, accent, spacing', () => {
  expect(appearanceSummary('dark', 'teal', 'comfortable')).toBe('Dark · teal · comfortable');
  expect(appearanceSummary('system', 'sky', 'compact')).toBe('Device · sky · compact');
});
