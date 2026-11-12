import { expect, it } from 'vitest';
import { ago } from './ago';

it('says how long ago in the largest whole unit', () => {
  const now = 1_000_000_000_000;
  const before = (ms: number) => ago(now - ms, now);
  expect(before(5_000)).toBe('just now');
  expect(before(60_000)).toBe('1 minute ago');
  expect(before(3 * 3_600_000)).toBe('3 hours ago');
  expect(before(2 * 86_400_000)).toBe('2 days ago');
  expect(before(21 * 86_400_000)).toBe('3 weeks ago');
  expect(before(90 * 86_400_000)).toBe('3 months ago');
  expect(before(800 * 86_400_000)).toBe('2 years ago');
  expect(ago(now + 5000, now)).toBe('just now');
});
