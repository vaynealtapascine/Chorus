import { describe, expect, it } from 'vitest';
import { accountStandIns, discGlyph, frontLine, frontsByAccount, ordered } from './space-fronts';
import type { SpaceFrontEntry } from './spaces';

const e = (name: string, level: string, extra: Partial<SpaceFrontEntry> = {}): SpaceFrontEntry => ({ t: 'subject', level, name, color: null, glyph: null, ...extra });

describe('shared fronts in a space', () => {
  it('puts the primary first, then front before co-conscious', () => {
    expect(ordered([e('Nairon', 'cocon'), e('Violet', 'front'), e('Kairos', 'cocon', { is_primary: true })]).map((x) => x.name)).toEqual(['Kairos', 'Violet', 'Nairon']);
  });
  it('shows nothing for accounts with nobody to show', () => {
    const by = frontsByAccount([{ account_id: 'a', entries: [] }, { account_id: 'b', entries: [e('Kairos', 'front')] }]);
    expect([...by.keys()]).toEqual(['b']);
    expect(frontLine(by.get('a'))).toBeNull();
    expect(frontsByAccount([]).size).toBe(0);
  });
  it('names them, with a letter when there is no glyph', () => {
    expect(frontLine([e('Kairos', 'front'), { t: 'someone', level: 'cocon', name: 'Someone', color: null, glyph: null }])).toBe('Kairos & Someone');
    expect([discGlyph(e('kairos', 'front')), discGlyph(e('Kairos', 'front', { glyph: '🌙' }))]).toEqual(['K', '🌙']);
  });
});

describe('authors without a card', () => {
  const accounts = [{ id: 'acc-j', handle: 'juniper', display_name: 'The Junipers', kind: 'system', avatar_blob: 'blob1' }, { id: 'acc-me', handle: 'me', display_name: null, kind: 'system' }];
  it('fall back to the account, never to nobody', () => {
    const known = new Map([['seen', {}]]);
    const out = accountStandIns([{ authors: ['x', 'seen'], account_id: 'acc-j' }, { authors: ['x'], account_id: 'acc-j' }, { authors: ['mine'], account_id: 'acc-me' }, { authors: ['y'] }], known, accounts, 'acc-me');
    expect(out).toHaveLength(1);
    expect(out[0]).toMatchObject({ id: 'x', account_id: 'acc-j', name: 'The Junipers', avatar_blob: 'blob1' });
  });
  it('uses the handle when the account has no display name', () => {
    expect(accountStandIns([{ authors: ['x'], account_id: 'acc-me' }], new Map(), accounts, 'other')[0].name).toBe('@me');
  });
});
