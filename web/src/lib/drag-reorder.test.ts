import { expect, it } from 'vitest';
import { dropIndex, movedIds, moveId } from './drag-reorder';

it('moves one id and leaves the rest in order', () => {
  expect(moveId(['a', 'b', 'c', 'd'], 0, 2)).toEqual(['b', 'c', 'a', 'd']);
  expect(moveId(['a', 'b', 'c', 'd'], 3, 0)).toEqual(['d', 'a', 'b', 'c']);
  expect(moveId(['a', 'b'], 0, 9)).toEqual(['b', 'a']);
});

it('returns the same list when nothing moves', () => {
  const ids = ['a', 'b', 'c'];
  expect(moveId(ids, 1, 1)).toBe(ids);
  expect(moveId(ids, 5, 0)).toBe(ids);
  expect(moveId(ids, 2, 7)).toBe(ids);
});

it('lands a dragged row after the rows whose middle it has passed', () => {
  const others: [number, number][] = [[0, 40], [40, 40], [80, 40]];
  expect(dropIndex(others, 10)).toBe(0);
  expect(dropIndex(others, 61)).toBe(2);
  expect(dropIndex(others, 200)).toBe(3);
});

it('names what moved', () => {
  expect(movedIds(['a', 'b', 'c'], ['b', 'a', 'c'])).toEqual(['b', 'a']);
  expect(movedIds(['a', 'b'], ['a', 'b'])).toEqual([]);
});
