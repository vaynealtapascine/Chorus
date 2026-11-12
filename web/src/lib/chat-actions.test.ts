import { expect, it } from 'vitest';
import { layout, type Action } from './chat-actions';

const all: Action[] = ['delete', 'quote', 'reply', 'react', 'link', 'thread', 'copy', 'pin', 'forward', 'edit', 'select', 'history', 'reply_in', 'private'];

it('puts the eight first, in the Figma order, and the rest behind More', () => {
  const { primary, more } = layout(all);
  expect(primary).toEqual(['react', 'reply', 'thread', 'copy', 'pin', 'forward', 'edit', 'select', 'delete']);
  expect(more).toEqual(['private', 'reply_in', 'quote', 'link', 'history']);
});
it("leaves out what isn't there (someone else's message has no Edit or Delete)", () => {
  const { primary } = layout(all.filter((a) => a !== 'edit' && a !== 'delete'));
  expect(primary).toEqual(['react', 'reply', 'thread', 'copy', 'pin', 'forward', 'select']);
});
it('does not hide a lone extra action behind More', () => {
  const { primary, more } = layout(['reply', 'delete', 'link']);
  expect(more).toEqual([]);
  expect(primary).toEqual(['reply', 'link', 'delete']);
  expect(layout(['reply', 'link']).primary).toEqual(['reply', 'link']);
});
