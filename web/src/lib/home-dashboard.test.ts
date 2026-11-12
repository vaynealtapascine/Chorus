// The same cases as android/.../data/HomeDashboardTest.kt: the two clients share `home.sections`.
import { describe, expect, it } from 'vitest';
import * as H from './home-dashboard';
import { AROUND_MS, type FollowerView, type FriendRow } from './friends-screens';

const stored = (...s: [string, boolean][]) => s.map(([id, on]) => ({ id, on }));

describe('home sections', () => {
  it('default to the Figma order, with Handover waiting for D-132', () => {
    const system = H.parse(null, false);
    expect(system.map((s) => s.id)).toEqual(H.SYSTEM);
    expect(system.every((s) => s.on)).toBe(true);
    expect(H.shown(system)).not.toContain('handover');
    expect(H.editable(system).some((s) => s.id === 'handover')).toBe(false);
    expect(H.shown(H.parse(null, true))).toEqual(['write', 'friends', 'waiting', 'new_from_friends']);
  });

  it('keep the stored order, drop unknown ids and put missing ones back beside their neighbour', () => {
    const raw = stored(['today', true], ['waiting', false], ['bogus', true], ['fronting', true], ['today', false]);
    const sections = H.parse(raw, false);
    expect(sections.map((s) => s.id)).toEqual(['today', 'new_from_friends', 'waiting', 'friends', 'fronting', 'quick_switch', 'handover']);
    expect(sections.find((s) => s.id === 'waiting')!.on).toBe(false);
    expect(H.shown(sections)).toEqual(['today', 'new_from_friends', 'friends', 'fronting', 'quick_switch']);
    // a person's list never takes a system's sections
    expect(H.parse(raw, true).map((s) => s.id)).toEqual(H.PERSON);
  });

  it('read a bare string as shown and a missing "on" as shown', () => {
    const got = H.parse(['today', { id: 'friends' }, { id: 'waiting', on: false }], false);
    const at = (id: string) => got.find((x) => x.id === id)!;
    expect(at('today').on && at('friends').on && !at('waiting').on).toBe(true);
    expect(got.map((x) => x.id).filter((id) => ['today', 'friends', 'waiting'].includes(id))).toEqual(['today', 'friends', 'waiting']);
    expect(H.parse('nonsense', false).map((s) => s.id)).toEqual(H.SYSTEM);
  });

  it('are kept per reader, falling back to the account', () => {
    const byReader = { fox: stored(['fronting', false]) };
    const account = stored(['waiting', false]);
    const fox = H.forReader(byReader, account, 'fox', false);
    expect(fox.find((s) => s.id === 'fronting')!.on).toBe(false);
    expect(fox.find((s) => s.id === 'waiting')!.on).toBe(true);
    const nairon = H.forReader(byReader, account, 'nairon', false);
    expect(nairon.find((s) => s.id === 'fronting')!.on).toBe(true);
    expect(nairon.find((s) => s.id === 'waiting')!.on).toBe(false);
    expect(H.key('fox')).toBe('home.sections:fox');
    expect(H.key(null)).toBe('home.sections');
    expect(H.value(H.parse(null, false))).toHaveLength(H.SYSTEM.length);
  });

  it('are read from the pref rows, a pending change winning over the stamped row', () => {
    const prefs = {
      'acct||home.sections': { exists: true, fields: { value: stored(['waiting', false]) } },
      'acct||home.sections:fox': { exists: true, fields: { value: stored(['fronting', false]) } },
      '||home.sections:fox': { fields: { value: stored(['today', false]) } },
      'other||home.sections:ash': { exists: true, fields: { value: stored(['today', false]) } },
      'acct|phone|home.sections:ash': { exists: true, fields: { value: stored(['today', false]) } },
    };
    const { byReader, accountWide } = H.storedSections(prefs, 'acct');
    expect(Object.keys(byReader)).toEqual(['fox']);
    expect(byReader.fox).toEqual(stored(['today', false]));
    expect(accountWide).toEqual(stored(['waiting', false]));
  });
});

describe('quick switch', () => {
  const own = ['violet', 'nairon', 'amarynthe', 'foxglove', 'ash'];
  it('leads with pins, then recents, then everyone ours', () => {
    expect(H.quickSwitch(own, ['foxglove', 'gone'], ['violet', 'foxglove', 'nairon'], 4)).toEqual(['foxglove', 'violet', 'nairon', 'amarynthe']);
    expect(H.quickSwitch(own, [], [], 10)).toEqual(own);
    expect(H.quickSwitch(own, [], [], -1)).toEqual([]);
  });
});

describe('waiting', () => {
  it('lists unread chats newest first, without muted or archived', () => {
    const rows = [
      { id: 'read', unread: 0, at: 9 }, { id: 'general', unread: 3, at: 5 }, { id: 'kit', unread: 1, at: 7 },
      { id: 'quiet', unread: 4, muted: true, at: 8 }, { id: 'old', unread: 2, archived: true, at: 6 }, { id: 'market', unread: 1, at: 1 },
    ] as { id: string; unread: number; at: number; muted?: boolean; archived?: boolean }[];
    const shown = H.waiting(rows, (r) => r.unread, (r) => !!r.muted, (r) => !!r.archived, (r) => r.at, 2);
    expect(shown.map((r) => r.id)).toEqual(['kit', 'general']);
  });
});

describe('friends right now', () => {
  const now = 100 * 3_600_000;
  const kit = { name: 'Kit', level: 'front' };
  const row = (id: string, kind: string, view: Partial<FollowerView> | null = null, latestAt: number | null = null, status = 'active'): FriendRow => {
    const account = { id, handle: id, display_name: id, kind };
    return {
      account, following: { id: `f${id}`, account, status, created_at: 1 }, follower: null,
      view: view ? { entries: [], since: null, ...view } : null, latest: latestAt === null ? null : { occurred_at: latestAt, kind: 'entry' },
    };
  };
  it('sorts around, not yet, posted, quiet, then not sharing', () => {
    const rows = [
      row('moss', 'system', { shared: false }),
      row('drift', 'system', {}),
      row('idle', 'system', { since: now - 10 }),
      row('junipers', 'system', { entries: [kit], since: now - 5 }),
      row('lanterns', 'system', { entries: [kit], since: now - 1 }),
      row('ren', 'person', null, now - 3_600_000),
      row('ash', 'person', null, now - AROUND_MS - 1),
      row('asked', 'system', { entries: [kit] }, null, 'requested'),
    ];
    const shown = H.friends(rows, now);
    expect(shown.map((s) => s.row.account.id)).toEqual(['lanterns', 'junipers', 'drift', 'ren', 'idle', 'moss']);
    expect(shown.map((s) => s.card)).toEqual(['around', 'around', 'not_yet', 'posted', 'quiet', 'not_sharing']);
  });
});

describe('today', () => {
  const layers = [{ id: 'front', name: 'Steering' }, { id: 'cocon', name: 'Co-conscious' }, { id: 'present', name: 'Present' }];
  const names: Record<string, string> = { fox: 'Foxglove', am: 'Amarynthe', nai: 'Nairon', vi: 'Violet' };
  const e = (id: string, level: string, is_primary = false) => ({ level, is_primary, name: names[id] });

  it('names the inner layer, then the next', () => {
    expect(H.switchLine([e('am', 'cocon'), e('nai', 'present'), e('fox', 'front')], layers)).toBe('Foxglove started steering · Amarynthe co-conscious');
    expect(H.switchLine([e('fox', 'front'), e('vi', 'front', true)], layers)).toBe('Violet & Foxglove started steering');
    expect(H.switchLine([e('nai', 'present')], layers)).toBe('Nairon present');
    expect(H.switchLine([], layers)).toBe('No one fronting');
  });

  it('merges our switches and posts since midnight, newest first', () => {
    const day = 1000;
    const sw = (id: string, at: number, front: ReturnType<typeof e>[], retracted = false) => ({ id, occurred_at: at, retracted, resulting_front: front });
    const switches = [sw('s0', day - 1, [e('vi', 'front')]), sw('s1', day + 10, [e('nai', 'front')]),
      sw('s2', day + 30, [e('fox', 'front'), e('am', 'cocon')]), sw('s3', day + 40, [e('vi', 'front')], true)];
    const post = (id: string, at: number, title: string | null, text: string, reply_to: string | null = null) => ({ id, occurred_at: at, title, text, reply_to, author: 'Amarynthe' });
    const posts = [post('p1', day + 20, 'A quiet afternoon', ''), post('p2', day + 25, null, 'reply', 'x'), post('p3', day + 5, null, '\nTiny painting from this morning')];
    const events = H.today(switches, posts, day, layers);
    expect(events.map((x) => x.id)).toEqual(['s2', 'p1', 's1', 'p3']);
    expect(events[0].text).toBe('Foxglove started steering · Amarynthe co-conscious');
    expect(events[1].text).toBe('Amarynthe wrote “A quiet afternoon”');
    expect(events[3].text).toBe('Amarynthe wrote “Tiny painting from this morning”');
    expect(events.map((x) => x.kind)).toEqual(['switch', 'post', 'switch', 'post']);
  });
});

describe('first day', () => {
  it('shows what is left to do', () => {
    const ren = { pronouns: null, avatar_blob: null, description: null };
    const first = H.firstDay(0, 0, ren);
    expect(first.welcome && first.post && first.profile).toBe(true);
    expect(H.firstDay(2, 1, { ...ren, pronouns: 'she/her' }).any).toBe(false);
  });
});
