import { describe, expect, it } from 'vitest';
import { withPrimary, customStates, displayPrefs, duration, icon, memberStatuses, ordered, parse, scene, shortAbout, status, statusChoices, statusLine, statusShowing, statusValue,
  story, toJson, type Settings } from './fronting-display';
import type { FrontPresence } from './core/fronts';

const p = (id: string, layer_id: string, is_primary = false): FrontPresence => ({ subject_type: 'member', subject_id: id, layer_id, is_primary });
const LAYERS = [{ id: 'front', name: 'Fronting' }, { id: 'cocon', name: 'Co-conscious' }, { id: 'present', name: 'Present' }];

describe('fronting display (D-122, F3)', () => {
  it('keeps defaults for a missing or malformed pref', () => {
    const s = parse(undefined);
    expect(s.order).toEqual([]);
    const odd = parse({ layers: { x: { style: 'wavy', color: 'red' } }, fronts: { b: { preset: 'huge', icon: 'rocket' } }, order: ['b', '', 3] });
    expect(odd.layers.x).toEqual({ style: null, about: null, separate: false, color: null });
    expect(odd.fronts.b.preset).toBe('detailed');
    expect(odd.fronts.b.icon).toBeNull();
    expect(odd.order).toEqual(['b', '3']);
  });

  it('round-trips, and matches the Android field names', () => {
    const s: Settings = {
      layers: { cocon: { style: 'faint', about: 'close', separate: true, color: '#E8776D' } },
      fronts: { b: { icon: 'book', about: 'Inner world', hidden: true, preset: 'simple', hiddenLayers: ['present', 'away'], hideEmpty: false, collapseAway: false, showTime: false, showOthers: false } },
      order: ['b', 'a'], hintDismissed: true,
    };
    const json = toJson(s);
    expect(json).toMatchObject({ hint_dismissed: true, fronts: { b: { hidden_layers: ['away', 'present'], collapse_away: false } } });
    expect(parse(json)).toEqual({ ...s, fronts: { b: { ...s.fronts.b, hiddenLayers: ['away', 'present'] } } });
  });

  it('reads the pref and member statuses, pending changes first', () => {
    const prefs = {
      'acct||fronting.display': { exists: true, fields: { value: { fronts: { acct: { preset: 'complete' } } } } },
      'acct||member_status:fox': { exists: true, fields: { value: { text: 'Tired', at: 5 } } },
      'acct||member_status:ama': { exists: true, fields: { value: { text: 'Busy', at: 5 } } },
      '||member_status:ama': { exists: true, fields: { value: null } },
    };
    expect(displayPrefs(prefs, 'acct').fronts.acct.preset).toBe('complete');
    const st = memberStatuses(prefs, 'acct');
    expect(st.get('fox')).toMatchObject({ tags: [], note: 'Tired', at: 5 });
    expect(st.has('ama')).toBe(false);
  });

  it('reads and writes member states as Android does (tags, note, clear_on_leave, text)', () => {
    const states = customStates({ s1: { exists: true, fields: { name: 'Blurry' } }, s2: { exists: true, fields: { name: 'Gone', deleted_at: 3 } } });
    expect(states).toEqual([{ id: 's1', name: 'Blurry' }]);
    // Android's shape (FrontingDisplay.statusPayload)
    const android = { tags: ['tired', 'state:s1', 'humming'], note: 'Typing only today, please', at: 10, clear_on_leave: true, text: 'Tired · Blurry · humming · Typing only today, please', later: 1 };
    const s = status(android)!;
    expect(s).toMatchObject({ tags: ['tired', 'state:s1', 'humming'], note: 'Typing only today, please', at: 10, clearOnLeave: true });
    expect(statusLine(s, states)).toBe('Tired · Blurry · humming · Typing only today, please');
    expect(statusLine(s, [])).toBe('Tired · State · humming · Typing only today, please');
    // a write keeps every tag and the fields this client doesn't know
    expect(statusValue({ ...s, note: '  ' }, states)).toEqual({ tags: ['tired', 'state:s1', 'humming'], at: 10, clear_on_leave: true, text: 'Tired · Blurry · humming', later: 1 });
    expect(status(statusValue(s, states))).toEqual(s);
    // the old web shape reads as a note; tags with no note keep `text` out of the note
    expect(status({ text: 'Busy', at: 5 })).toEqual({ tags: [], note: 'Busy', at: 5, clearOnLeave: false });
    expect(status({ tags: ['busy'], text: 'Busy', at: 5 })).toEqual({ tags: ['busy'], note: null, at: 5, clearOnLeave: false });
    expect(status({ tags: [], note: ' ' })).toBeNull();
    expect(statusValue({ tags: [], note: ' ', at: 1, clearOnLeave: false })).toBeNull();
    expect(statusValue(null)).toBeNull();
    // choices: built-ins, the system's own, then the member's own words
    expect(statusChoices(states, s)).toEqual(['tired', 'nonverbal', 'busy', 'low_spoons', 'needs_quiet', 'out_and_about', 'state:s1', 'humming']);
    // clear_on_leave: hidden once they came back after it was set
    expect(statusShowing(s, 5)).toBe(true);
    expect(statusShowing(s, 10 + 60_000)).toBe(true);
    expect(statusShowing(s, 10 + 60_001)).toBe(false);
    expect(statusShowing(s, null)).toBe(true);
    expect(statusShowing({ ...s, clearOnLeave: false }, 10 ** 9)).toBe(true);
  });

  it('sorts entries into nested, separate, unlisted and away', () => {
    const s = parse({ layers: { present: { separate: true } }, fronts: { acct: { hidden_layers: ['away'] } } });
    const entries = [p('fox', 'front', true), p('ama', 'front'), p('nai', 'cocon'), p('vio', 'present'), p('ghost', 'quiet')];
    const sc = scene(LAYERS, entries, s, 'acct', ['fox', 'ama', 'nai', 'vio', 'sab']);
    expect(sc.nested.map((l) => [l.id, l.style])).toEqual([['front', 'solid'], ['cocon', 'dashed']]);
    expect(sc.separate.map((l) => [l.id, l.style])).toEqual([['present', 'plain']]);
    expect(sc.unlisted.map((e) => e.subject_id)).toEqual(['ghost']);
    expect(sc.away).toEqual(['sab']);
    expect(sc.awayShown).toBe(false);
    expect(shortAbout(sc.nested[0].about)).toBe('in control');
  });

  it('orders fronts and picks icons', () => {
    const fronts = [{ id: 'z', name: 'Work' }, { id: 'body', name: 'Body' }, { id: 'a', name: 'Inner world' }];
    expect(ordered(fronts, 'body', parse(undefined)).map((f) => f.id)).toEqual(['body', 'a', 'z']);
    expect(ordered(fronts, 'body', parse({ order: ['a'] })).map((f) => f.id)).toEqual(['a', 'body', 'z']);
    expect(icon('body', 'Body', 'body', parse(undefined))).toBe('target');
    expect(icon('z', 'Work', 'body', parse(undefined))).toBe('briefcase');
    expect(duration(84 * 60_000)).toBe('1h 24m');
  });

  it('tells the story of a front, as Android does', () => {
    const states = [
      { id: '1', at: 100, entries: [p('ama', 'front', true)] },
      { id: '2', at: 200, entries: [p('fox', 'front', true)] },
      { id: '3', at: 300, entries: [p('fox', 'front', true), p('vio', 'present')] },
      { id: '4', at: 400, entries: [p('fox', 'front', true), p('vio', 'present'), p('nai', 'present')] },
      { id: '5', at: 500, entries: [p('fox', 'front', true), p('ama', 'front'), p('vio', 'present'), p('nai', 'cocon')] },
      { id: '6', at: 600, entries: [p('fox', 'front', true)] },
    ];
    const lines = story(states, ['front', 'cocon', 'present'], 'present', (id) => id, (_, id) => id[0].toUpperCase() + id.slice(1));
    expect(lines.map((l) => l.text)).toEqual(['Ama started fronting', 'Fox took over from Ama', 'Vio came close', 'Nai came close',
      'Ama joined Fox', 'Nai moved inward', 'Ama left', 'Vio left', 'Nai left']);
  });

  it('gives up primary when leaving the front, and gives it to the first into an empty one', () => {
    const current = [p('fox', 'front', true), p('nai', 'cocon')];
    type Move = { type: string; id: string; layer: string | null; primary?: boolean };
    const moves = withPrimary<Move>([{ type: 'member', id: 'fox', layer: 'cocon' }, { type: 'member', id: 'nai', layer: 'front' }, { type: 'member', id: 'vio', layer: 'front' }], current, 'front');
    expect(moves.map((m) => m.primary)).toEqual([false, true, undefined]);
    expect(withPrimary<Move>([{ type: 'member', id: 'ama', layer: 'front' }], current, 'front')[0].primary).toBeUndefined();
  });
});
