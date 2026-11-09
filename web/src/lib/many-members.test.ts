import { expect, it } from 'vitest';
import { memberChange, votesFor } from './many-members';

const poll = (multiple: boolean) => ({ options: [{ id: 'x', text: 'Soup' }, { id: 'y', text: 'Rice' }], multiple });

it('adds and takes back in list order', () => {
  expect(memberChange(['a', 'b', 'c', 'd'], new Set(['b', 'd']), new Set(['d', 'c', 'a']))).toEqual({ add: ['a', 'c'], remove: ['b'] });
});

it('moves single-choice votes and withdraws whoever is left out', () => {
  const votes: Record<string, string[]> = { a: ['y'], b: ['x'], c: ['x'] };
  expect(votesFor(poll(false), (id) => votes[id] ?? [], ['a', 'b', 'c', 'd'], 'x', new Set(['a', 'b', 'd'])))
    .toEqual([['a', ['x']], ['c', []], ['d', ['x']]]);
});

it('keeps other choices on a multiple-choice poll', () => {
  const votes: Record<string, string[]> = { a: ['y'], b: ['x', 'y'] };
  expect(votesFor(poll(true), (id) => votes[id] ?? [], ['a', 'b'], 'x', new Set(['a']))).toEqual([['a', ['y', 'x']], ['b', ['y']]]);
});
