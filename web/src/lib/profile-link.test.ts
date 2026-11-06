import { describe, expect, it } from 'vitest';
import { profileHref } from './profile-link';

describe('profile links', () => {
  it('opens our own members here and another account’s on their friend profile', () => {
    expect(profileHref({ id: 'kai' }, 'me')).toBe('#/profile/kai');
    expect(profileHref({ id: 'kai', account_id: 'me' }, 'me')).toBe('#/profile/kai');
    expect(profileHref({ id: 'fox 1', account_id: 'acc/2' }, 'me')).toBe('#/people/acc%2F2/fox%201');
    expect(profileHref(undefined, 'me')).toBeNull();
  });
});
