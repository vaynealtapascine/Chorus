import { expect, it } from 'vitest';
import { cap, customFront, customSystem, DEFAULT_WORDS, parseWords, wordsPayload } from './words';

it('reads the stored words, as an object or its text, and falls back to the defaults', () => {
  expect(parseWords(undefined)).toEqual(DEFAULT_WORDS);
  expect(parseWords({ system: ['Collective', 'Collectives'], front: ['steer', 'steering'] })).toEqual({ system: 'collective', systems: 'collectives', front: 'steer', fronting: 'steering' });
  expect(parseWords('{"system":["home"]}')).toEqual({ ...DEFAULT_WORDS, system: 'home', systems: 'home' });
  expect(parseWords('not json')).toEqual(DEFAULT_WORDS);
});

it('keeps other stored words when it writes the two', () => {
  expect(wordsPayload({ switch: ['x'] }, { system: 'home', systems: 'homes', front: 'out', fronting: 'out' }))
    .toEqual({ switch: ['x'], system: ['home', 'homes'], front: ['out', 'out'] });
});

it('makes a plural the plain English way', () => {
  expect(customSystem(' Party ')).toEqual(['party', 'parties']);
  expect(customSystem('box')).toEqual(['box', 'boxes']);
  expect(customSystem('boy')).toEqual(['boy', 'boys']);
  expect(customSystem(' ')).toBeNull();
  expect(customFront('Driving')).toEqual(['driving', 'driving']);
  expect(cap('fronting')).toBe('Fronting');
});
