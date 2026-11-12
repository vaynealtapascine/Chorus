import { expect, it } from 'vitest';
import { keysOf, ownOf, PART_GROUPS, summary, withAll } from './member-sharing';

const live = new Set(['b1']);
it('a closed profile reaches nobody, whatever the parts say', () => {
  expect(summary({ mode: 'private' }, { pronouns: { mode: 'friends' } }, ['pronouns'], new Map(), live)).toEqual({ mode: 'private' });
});
it('parts follow the profile unless they chose, and disagree as Mixed', () => {
  const profile = { mode: 'friends' };
  expect(summary(profile, null, ['pronouns'], new Map(), live)).toEqual({ mode: 'friends' });
  expect(summary(profile, { banner: { mode: 'server' } }, ['name', 'banner'], new Map(), live)).toBeNull();
  expect(summary(profile, null, ['description', 'f1'], new Map([['f1', { mode: 'private' }]]), live)).toBeNull();
});
it('sets every key of a row at once, and clears them together', () => {
  const parts = withAll(null, ['name', 'banner'], { mode: 'friends' });
  expect(parts).toEqual({ name: { mode: 'friends' }, banner: { mode: 'friends' } });
  expect(ownOf(parts, ['name', 'banner'])).toEqual({ mode: 'friends' });
  expect(withAll(parts, ['name', 'banner'], null)).toEqual({});
  expect(keysOf(PART_GROUPS[2], ['f1'])).toEqual(['description', 'birthday', 'f1']);
});
