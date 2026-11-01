import { describe, expect, it } from 'vitest';
import { alignsRight, readSetting, roomSpeakers, settingValue } from './room-speakers';

const all = () => true;

describe('room speakers', () => {
  it('reads and writes the autoproxy pref, front by default', () => {
    expect(readSetting(undefined)).toEqual({ mode: 'front', member: undefined, members: [] });
    expect(readSetting({ mode: 'members', members: ['kai', 'rin', 'kai', 3] })).toEqual({ mode: 'members', member: undefined, members: ['kai', 'rin'] });
    expect(readSetting({ mode: 'weird' }).mode).toBe('front');
    expect(settingValue('members', undefined, ['rin', 'kai', 'rin'])).toEqual({ mode: 'members', members: ['rin', 'kai'] });
    expect(settingValue('member', 'kai')).toEqual({ mode: 'member', member: 'kai' });
    expect(settingValue('front', 'kai')).toEqual({ mode: 'front' });
  });
  it('speakers follow the front, a chosen set, one member, the last speaker or nobody', () => {
    const fronting = [{ member_id: 'june', is_primary: false, level: 'front' }, { member_id: 'kai', is_primary: true, level: 'front' },
      { member_id: 'rin', is_primary: false, level: 'cocon' }, { member_id: 'moss', is_primary: false, level: 'present' }];
    const ctx = { fronting, lastAuthors: ['moss'], writable: (id: string) => id !== 'gone' };
    expect(roomSpeakers(readSetting({}), ctx)).toEqual(['kai', 'june', 'rin']);
    expect(roomSpeakers(readSetting({ mode: 'members', members: ['rin', 'gone', 'june'] }), ctx)).toEqual(['rin', 'june']);
    expect(roomSpeakers(readSetting({ mode: 'member', member: 'moss' }), ctx)).toEqual(['moss']);
    expect(roomSpeakers(readSetting({ mode: 'latch' }), ctx)).toEqual(['moss']);
    expect(roomSpeakers(readSetting({ mode: 'off' }), { ...ctx, writable: all })).toEqual([]);
  });
  it('lines up our messages by any speaker on our side', () => {
    const aligned = new Set(['kai', 'leaf']);
    expect(alignsRight({ account_id: 'me', authors: ['leaf'] }, 'me', aligned)).toBe(true);
    expect(alignsRight({ account_id: null, authors: ['kai', 'june'] }, 'me', aligned)).toBe(true);
    expect(alignsRight({ account_id: 'me', authors: ['june'] }, 'me', aligned)).toBe(false);
    expect(alignsRight({ account_id: 'them', authors: ['kai'] }, 'me', aligned)).toBe(false);
  });
});
