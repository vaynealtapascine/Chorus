import { describe, expect, it } from 'vitest';
import { activeViewers, memberVisible } from './hidden';

describe('soft member message view', () => {
  const restricted = { visibility: { mode: 'members', member_ids: ['alex'] } };
  it('uses front/co-con or explicit viewing-as, excluding merely present members', () => {
    const front = [
      { subject_type: 'member', subject_id: 'alex', level: 'present' },
      { subject_type: 'member', subject_id: 'sam', level: 'front' },
    ];
    expect(memberVisible(restricted, 'internal', activeViewers(front), null)).toBe(false);
    expect(memberVisible(restricted, 'internal', activeViewers(front), 'alex')).toBe(true);
    front[0].level = 'cocon';
    expect(memberVisible(restricted, 'internal', activeViewers(front), null)).toBe(true);
  });

  it('leaves ordinary messages visible and does not treat shared asides as member filters', () => {
    expect(memberVisible({}, 'internal', [], null)).toBe(true);
    expect(memberVisible(restricted, 'shared', [], null)).toBe(true);
  });
});
