import { describe, expect, it } from 'vitest';
import { pinMarker, switchMarker } from './journal-marker';

const now = 1_000_000_000_000;
describe('timeline markers', () => {
  it('names who is fronting, or no one', () => {
    expect(switchMarker({ retracted: false, names: ['Violet'], at: now - 3 * 3600_000 }, now)).toEqual({ icon: 'swap', text: 'Violet fronting', ago: '3h' });
    expect(switchMarker({ retracted: false, names: ['Violet', 'Kit'], at: now - 60_000 }, now).text).toBe('Violet & Kit fronting');
    expect(switchMarker({ retracted: false, names: [], at: now - 2 * 86_400_000 }, now)).toMatchObject({ text: 'No one fronting', ago: '2d' });
  });
  it('an undone switch says so', () => {
    expect(switchMarker({ retracted: true, names: ['Violet'], at: now - 5 * 60_000 }, now)).toMatchObject({ text: 'Undone switch', ago: '5m' });
  });
  it('a pin names its channel and flattens the text', () => {
    expect(pinMarker({ channel: 'general', text: 'bring\n  snacks', at: now - 1000 }, now)).toEqual({ icon: 'pin', text: 'Pinned in #general: bring snacks', ago: 'now' });
    expect(pinMarker({ channel: null, text: 'x', at: now }, now).text).toBe('Pinned in #channel: x');
  });
});
