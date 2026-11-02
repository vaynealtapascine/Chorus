import { describe, expect, it } from 'vitest';
import fixture from '../../../fixtures/ui_profile_audience.json';
import { audienceLabel, audienceOf, effectiveAudience, isPrivate, ownAudience, possiblyWidens, sameAudience, withAudience } from './profile-audience';

const names = new Map(Object.entries(fixture.buckets));
const live = new Set(Object.keys(fixture.buckets));

describe('profile audiences (shared fixture)', () => {
  for (const c of fixture.cases) {
    it(c.name, () => {
      const a = effectiveAudience(c.profile, c.parts, c.key, 'default' in c ? c.default : undefined);
      expect(a).toEqual(c.audience);
      expect(audienceLabel(a, names)).toBe(c.label);
      expect(isPrivate(a, live)).toBe(c.label === 'Only us');
    });
  }
});

describe('editing audiences', () => {
  it('sets and clears one part without touching the others', () => {
    const parts = { pronouns: { mode: 'private' }, banner: { mode: 'followers' } };
    expect(withAudience(parts, 'birthday', { mode: 'server' })).toEqual({ ...parts, birthday: { mode: 'server' } });
    expect(withAudience(parts, 'pronouns', null)).toEqual({ banner: { mode: 'followers' } });
    expect(withAudience(null, 'pronouns', { mode: 'followers' })).toEqual({ pronouns: { mode: 'followers' } });
    // a wrong-shaped map starts over rather than spreading junk
    expect(withAudience([1], 'pronouns', null)).toEqual({});
    // and a bad entry is normalised (to private), never widened
    expect(withAudience({ x: { mode: 'everyone' } }, 'y', null)).toEqual({ x: { mode: 'private' } });
  });

  it('reads stored audiences strictly', () => {
    expect(audienceOf({ mode: 'buckets', bucket_ids: ['a', 3] })).toEqual({ mode: 'buckets', bucket_ids: ['a'] });
    expect(audienceOf('followers')).toEqual({ mode: 'private' });
    expect(ownAudience({}, 'pronouns')).toBeNull();
    expect(ownAudience({ pronouns: null }, 'pronouns')).toBeNull();
    expect(sameAudience({ mode: 'buckets', bucket_ids: ['a', 'b'] }, { mode: 'buckets', bucket_ids: ['b', 'a'] })).toBe(true);
    expect(sameAudience({ mode: 'followers' }, { mode: 'server' })).toBe(false);
  });
});

describe('changing a post audience', () => {
  it('warns when more people may read it, as Android does', () => {
    const b = (...ids: string[]) => ({ mode: 'buckets' as const, bucket_ids: ids });
    expect(possiblyWidens({ mode: 'server' }, { mode: 'followers' })).toBe(true);
    expect(possiblyWidens({ mode: 'private' }, { mode: 'server' })).toBe(false);
    expect(possiblyWidens(b('a'), b('a', 'c'))).toBe(false);
    expect(possiblyWidens(b('a', 'd'), b('a', 'c'))).toBe(true);
    expect(possiblyWidens(b('a'), { mode: 'followers' })).toBe(false);
  });
});
