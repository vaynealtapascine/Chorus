import { expect, it } from 'vitest';
import {
  acceptCeiling, announcedTo, around, fieldValue, friendRows, hearsAbout, nowLine, prefsSummary, sinceLine, subjects,
  timeNote, withLevel, withQuiet, withSubject, quiet, type FollowRow, type FriendRow, type Person,
} from './friends-screens';

const person = (id: string, kind = 'system'): Person => ({ id, handle: id, display_name: id[0].toUpperCase() + id.slice(1), kind });
const follow = (id: string, who: Person, status = 'active', at = 0): FollowRow => ({ id, account: who, status, created_at: at });
const at = (h: number, m = 0, daysAgo = 0) => { const d = new Date(2026, 10, 7, h, m); d.setDate(d.getDate() - daysAgo); return d.getTime(); };
const hhmm = (t: number) => { const d = new Date(t); return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`; };

it('lists whom we follow, then those who only follow us', () => {
  const j = person('junipers'); const r = person('ren', 'person'); const m = person('moss');
  const rows = friendRows([follow('f1', j), follow('f2', r, 'requested')], [follow('b1', m), follow('b2', j), follow('b3', person('ash'), 'requested')], {}, {});
  expect(rows.map((x) => x.account.id)).toEqual(['junipers', 'ren', 'moss']);
  expect(rows[0].follower?.id).toBe('b2');
  expect(rows[2].following).toBeNull();
});

it('is around when a system shows someone or a person posted today', () => {
  const now = at(15);
  const sys: FriendRow = { account: person('j'), following: follow('f', person('j')), follower: null, view: { entries: [{ name: 'Kit', level: 'cocon' }], since: 1 }, latest: null };
  expect(around(sys, now)).toBe(true);
  expect(around({ ...sys, view: { entries: [], since: null } }, now)).toBe(false);
  expect(around({ ...sys, following: follow('f', person('j'), 'requested') }, now)).toBe(false);
  const ren: FriendRow = { account: person('r', 'person'), following: follow('g', person('r', 'person')), follower: null, view: null, latest: { occurred_at: now - 7_200_000 } };
  expect(around(ren, now)).toBe(true);
  expect(around({ ...ren, latest: { occurred_at: now - 25 * 3_600_000 } }, now)).toBe(false);
});

it('words who is here and since when, never more precisely than shared', () => {
  expect(nowLine([{ name: 'Juniper', level: 'cocon' }, { name: 'Kit', level: 'front' }])).toBe('Kit fronting · Juniper co-conscious');
  const now = at(15);
  const v = (mode: string, since: number) => ({ entries: [], since, time: { mode } });
  expect(sinceLine(v('round', at(10, 15)), now, hhmm)).toBe('since about 10:15');
  expect(sinceLine(v('exact', at(10, 15)), now, hhmm)).toBe('since 10:15');
  expect(sinceLine(v('part_of_day', at(5)), now, hhmm)).toBe('since this morning');
  expect(sinceLine(v('part_of_day', at(17, 0, 1)), now, hhmm)).toBe('since yesterday evening');
  expect(sinceLine(v('hidden', at(10)), now, hhmm)).toBeNull();
  expect(timeNote('The Junipers', { mode: 'round', round_min: 15 })).toBe('The Junipers round times to 15 minutes.');
});

it('edits follow prefs one chip at a time and keeps one on', () => {
  const all = ['kit', 'juniper', 'sable'];
  const some = withSubject({}, 'sable', all);
  expect([...subjects(some)!]).toEqual(['kit', 'juniper']);
  expect(subjects(withSubject(some, 'sable', all))).toBeNull();
  expect(withLevel({}, 'front')).toEqual({});
  expect(withLevel({ channel: 'x' }, 'cocon')).toEqual({ channel: 'x', levels: ['front', 'cocon'] });
  expect(quiet(withQuiet({}, ['23:00', '08:00']))).toEqual(['23:00', '08:00']);
  expect(prefsSummary(some, new Map([['kit', 'Kit'], ['juniper', 'Juniper']]), 0)).toBe('Kit and Juniper · each switch');
  expect(prefsSummary({ enabled: false }, new Map(), 0)).toBe('Off');
});

it('accepts without an override when the bucket already gives that preset', () => {
  const presetOf = (c: Record<string, unknown>) => (c.preset as string) ?? 'inherit';
  const ceiling = (p: string) => ({ preset: p });
  expect(acceptCeiling('gentle', { preset: 'gentle' }, {}, presetOf, ceiling)).toBeNull();
  expect(acceptCeiling('close', { preset: 'gentle' }, {}, presetOf, ceiling)).toEqual({ preset: 'close' });
  // an empty account default is Gentle
  expect(acceptCeiling('gentle', null, {}, presetOf, ceiling)).toBeNull();
  expect(acceptCeiling('digest', {}, { preset: 'digest' }, presetOf, ceiling)).toBeNull();
});

it('says who a follower hears about and reads ceiling details shortly', () => {
  expect(announcedTo(null, new Set())).toBe(true);
  expect(announcedTo('{"announce":"nobody"}', new Set(['close']))).toBe(false);
  expect(announcedTo({ announce: { buckets: ['close'] } }, new Set(['close']))).toBe(true);
  expect(hearsAbout([['Kit', true], ['June', true], ['Violet', false]])).toBe('Everyone except Violet');
  expect(hearsAbout([['Kit', false]])).toBe('Nobody');
  expect(fieldValue('delay', { min_s: 300, max_s: 1200 })).toBe('5–20 min');
  expect(fieldValue('time', { mode: 'round', round_min: 5 })).toBe('5 min');
  expect(fieldValue('quiet_hours', null)).toBe('None');
});
