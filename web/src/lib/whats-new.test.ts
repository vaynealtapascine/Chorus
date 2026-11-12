import { expect, it } from 'vitest';
import { parseWhatsNew } from './whats-new';

it('keeps up to three lines and skips comments and blanks', () => {
  expect(parseWhatsNew('# note\n\nOne\r\nTwo\nThree\nFour\n')).toEqual(['One', 'Two', 'Three']);
  expect(parseWhatsNew('# only a comment')).toEqual([]);
});
