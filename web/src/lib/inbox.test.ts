import { describe, expect, it } from 'vitest';
import { chips, fromActivity, fromNotifications, isRead, mark, needsYou, parseRead, readMarks, showsPayload, unmark, unread, visible, withReactors } from './inbox';

// D-127: the Notifications page's rows, chips, read marks and "what shows here" (Android's InboxTest)
const notes = [
  { id: 'm1', kind: 'mention', title: '#general', text: '@Fox could you check?', delivered_at: 400, channel_id: 'c', message_id: 'msg',
    sender: { name: 'Amarynthe', color: '#f00' } },
  { id: 's1', kind: 'switch', text: 'Kit is steering', delivered_at: 300, account: { id: 'j', display_name: 'The Junipers' } },
  { id: 'o1', kind: 'own_switch', title: 'Fox is fronting', delivered_at: 250 },
  { id: 'r1', kind: 'post_reply', title: 'Ash replied to your post', text: 'lovely', delivered_at: 200, post_id: 'reply', sender: { name: 'Ash' } },
  { id: 'n1', kind: 'poll_nudge', title: 'Kai nudged Rin', text: 'Dinner?', delivered_at: 150, message_id: 'm9', channel_id: 'c9', sender: { name: 'Kai' } },
  { id: 'x', kind: 'something_new', delivered_at: 100 },
  // plain messages are pushed, not listed
  { id: 'd1', kind: 'dm', title: 'Kit', text: 'hi', delivered_at: 90, channel_id: 'c' },
  { id: 'g1', kind: 'message', title: '#general', text: 'hello', delivered_at: 80, channel_id: 'c' },
  { id: 'md', kind: 'member_dm', title: 'Amy', text: 'psst', delivered_at: 70, channel_id: 'c' },
];

describe('inbox', () => {
  it('turns notifications into rows in their chips', () => {
    const rows = fromNotifications(notes);
    expect(rows.map((r) => r.id)).toEqual(['m1', 's1', 'o1', 'r1', 'n1']);
    expect(rows[4]).toMatchObject({ title: 'Kai nudged Rin', channelId: 'c9', category: 'mentions' });
    expect(rows[0]).toMatchObject({ title: 'Amarynthe mentioned you', quote: '@Fox could you check?', category: 'mentions', messageId: 'msg' });
    expect(rows[1]).toMatchObject({ title: 'The Junipers: Kit is steering', category: 'friends' });
    expect(rows[2].category).toBe('system');
    expect(rows[3].postId).toBe('reply');
    // own switches start hidden; turning them on shows them
    expect(visible(rows, 'all', null).some((r) => r.id === 'o1')).toBe(false);
    expect(visible(rows, 'all', { own_switches: true }).some((r) => r.id === 'o1')).toBe(true);
    expect(visible(rows, 'mentions', null).map((r) => r.id)).toEqual(['m1', 'r1', 'n1']);
    expect(chips(true).some(([id]) => id === 'system')).toBe(false);
  });

  it('groups hearts and puts what needs us first, never read', () => {
    const target = { id: 'p', kind: 'note', title: 'A quiet afternoon', text: '', cw: null };
    const liked = fromActivity({ items: [
      { kind: 'reaction', at: 50, emoji: '💜', member_id: 'kit', member_name: 'Kit', target },
      { kind: 'reaction', at: 60, emoji: '💜', member_id: 'jun', member_name: 'Juniper', target },
    ], reply_counts: {}, reactions: {} });
    expect(liked).toHaveLength(1);
    expect(liked[0]).toMatchObject({ title: 'Kit and Juniper liked “A quiet afternoon”', at: 60 });
    const asks = needsYou([{ id: 'f', status: 'requested', account: { id: 'mb', handle: 'mossbank', display_name: 'Mossbank' } }], [],
      [{ id: 'r' }], false, () => undefined, 10);
    expect(asks.map((a) => a.title)).toEqual(['Mossbank wants to follow your system', 'Two devices switched within a minute']);
    expect(isRead({ until: 1000, ids: [] }, asks[0])).toBe(false);
    expect(visible([...asks, ...liked], 'all', null)[0].id).toBe('follow:f');
  });

  it('counts hearts by members we may not see, named "Someone" (D-154)', () => {
    const target = { id: 'p', kind: 'note', title: 'Tea', text: '', cw: null };
    const heart = (member_id: string | null, member_name: string | null) => ({ kind: 'reaction' as const, at: 1, emoji: '💜', member_id, member_name, target });
    const title = (items: ReturnType<typeof heart>[]) => fromActivity({ items, reply_counts: {}, reactions: {} })[0].title;
    expect(title([heart(null, null)])).toBe('Someone liked “Tea”');
    expect(title([heart(null, null), heart(null, null)])).toBe('2 people liked “Tea”');
    expect(title([heart('kit', 'Kit'), heart(null, null)])).toBe('Kit and someone else liked “Tea”');
    expect(title([heart('kit', 'Kit'), heart(null, null), heart(null, null)])).toBe('Kit and 2 others liked “Tea”');
    const a = withReactors({ items: [], reply_counts: {}, reactions: { p: [
      { emoji: '💜', member_id: null, member_name: null }, { emoji: '💜', member_id: null, member_name: null }, { emoji: '💜', member_id: 'kit', member_name: 'Kit' },
    ] } } as never);
    const ids = a.reactions.p.map((r) => r.member_id);
    expect(new Set(ids).size).toBe(3);
    expect(ids.filter((id) => id.startsWith('someone:'))).toHaveLength(2);
    expect(a.reactions.p.map((r) => r.member_name)).toEqual(['Someone', 'Someone', 'Kit']);
  });

  it('moves read marks forward and back one row at a time', () => {
    const rows = fromNotifications(notes);
    const none = { until: 0, ids: [] };
    expect(unread(rows, none, null)).toBe(4);
    const one = mark(none, rows[0]);
    expect(isRead(one, rows[0])).toBe(true);
    expect(unread(rows, one, null)).toBe(3);
    const back = unmark({ until: 400, ids: [] }, rows[0], rows);
    expect(isRead(back, rows[0])).toBe(false);
    expect(isRead(back, rows[1]) && isRead(back, rows[3])).toBe(true);
    expect(parseRead(back)).toEqual(back);
    expect(parseRead('nonsense')).toEqual({ until: 0, ids: [] });
    expect(showsPayload(null, 'likes', false).value).toEqual({ likes: false });
  });

  it('reads each member’s marks from account prefs, a pending change winning', () => {
    const prefs = {
      'acct||inbox.read:fox': { fields: { value: { until: 5, ids: [] } } },
      '||inbox.read:fox': { fields: { value: { until: 9, ids: ['a'] } } },
      'acct|device|inbox.read:amy': { fields: { value: { until: 7, ids: [] } } },
      'other||inbox.read:kit': { fields: { value: { until: 3, ids: [] } } },
    };
    const marks = readMarks(prefs, 'acct');
    expect(marks.get('fox')).toEqual({ until: 9, ids: ['a'] });
    expect(marks.has('amy') || marks.has('kit')).toBe(false);
  });
});

describe('needs-you rows carry their time when it is known', () => {
  it('uses a request\'s own time and what each device recorded', () => {
    const rows = needsYou([{ id: 'f', status: 'requested', created_at: 500, account: { id: 'mb', handle: 'mossbank', display_name: 'Mossbank' } }], [], [{ id: 'r' }, { id: 'q' }], false, () => undefined, 9000,
      (r) => (r.id === 'r' ? ['Phone says Foxglove, laptop says Amarynthe', 700] : null));
    expect(rows.map((r) => [r.at, !!r.untimed])).toEqual([[500, false], [700, false], [9000, true]]);
    expect(rows[1].detail).toBe('Phone says Foxglove, laptop says Amarynthe');
    expect(rows[2].detail).toBeNull();
  });
});
