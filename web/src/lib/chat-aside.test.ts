import { expect, it } from 'vitest';
import { afterSend, everyone, offered, on, placeholder, tag } from './chat-aside';

it('offers an aside in a shared space, to a system', () => {
  expect(offered('shared', false)).toBe(true);
  expect(offered('shared', true)).toBe(false);
  expect(offered('internal', false)).toBe(false);
  expect(offered('dm', false)).toBe(false);
});
it('says who is here, and what the aside keeps', () => {
  expect(everyone(undefined)).toBe('Everyone here');
  expect(everyone([{ kind: 'system' }, { kind: 'system' }, { kind: 'system' }])).toBe('Everyone here · 3 systems');
  expect(everyone([{ kind: 'system' }, { kind: 'system' }, { kind: 'person' }])).toBe('Everyone here · 2 systems · 1 person');
  expect(on('The Lanterns')).toBe('Aside · only The Lanterns will see this');
  expect(placeholder('')).toBe('Aside to your system…');
  expect(tag(true, 'The Lanterns')).toBe('aside');
  expect(tag(false, 'The Lanterns')).toBe('aside · only The Lanterns');
});
it('keeps an aside on after sending, nothing else', () => {
  expect(afterSend('system_only')).toBe('system_only');
  expect(afterSend('members')).toBe('all');
});
