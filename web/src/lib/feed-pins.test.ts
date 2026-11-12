import { expect, it } from 'vitest';
import { pinned, pinnedTabs } from './feed-pins';

it('shows the three built-in tabs until pins are chosen, and drops pins whose feed is gone', () => {
  expect(pinnedTabs(undefined, new Set())).toEqual(['system', 'friends', 'entries']);
  expect(pinnedTabs(['friends', 'f1', 'gone', 'friends'], new Set(['f1']))).toEqual(['friends', 'f1']);
  expect(pinnedTabs(['gone'], new Set())).toEqual(['system', 'friends', 'entries']);
  expect(pinnedTabs('nope', new Set())).toEqual(['system', 'friends', 'entries']);
});

it('pins by appending and unpins by removing', () => {
  expect(pinned(['system'], 'f1', true)).toEqual(['system', 'f1']);
  expect(pinned(['f1', 'system'], 'f1', true)).toEqual(['system', 'f1']);
  expect(pinned(['system', 'f1'], 'f1', false)).toEqual(['system']);
});
