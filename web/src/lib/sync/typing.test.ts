import { describe, expect, it } from 'vitest';
import { TypingState, TypingThrottle, typingLabel, typingName, type TypingEntry, type TypingFrame } from './typing';

const frame = (overrides: Partial<TypingFrame> = {}): TypingFrame => ({ t: 'typing', channel: 'ch', account: 'a', member: 'kai', state: 'start', until: 7000, ...overrides });

describe('ephemeral typing', () => {
  it('refreshes one identity, isolates channels, expires at the deadline and prunes storage', () => {
    const state = new TypingState();
    state.receive(frame(), 1000);
    state.receive(frame({ until: 9000 }), 3000);
    state.receive(frame({ channel: 'other', member: null }), 3000);
    expect(state.entries('ch', 7000)).toEqual([{ account: 'a', member: 'kai', until: 9000 }]);
    expect(state.entries('other', 7000)).toEqual([]);
    state.expire(7000);
    expect(state.nextExpiry).toBe(9000);
    state.expire(9000);
    expect(state.nextExpiry).toBeUndefined();
  });
  it('stop removes only its identity; delayed expired starts never revive it', () => {
    const state = new TypingState();
    state.receive(frame(), 1000);
    state.receive(frame({ account: 'b', member: null }), 1000);
    state.receive(frame({ state: 'stop' }), 2000);
    expect(state.entries('ch', 2000).map((e) => e.account)).toEqual(['b']);
    state.receive(frame({ until: 2000 }), 2000);
    expect(state.entries('ch', 2000)).toHaveLength(1);
    state.clear();
    expect(state.entries('ch', 2000)).toEqual([]);
  });
  it('throttles starts per channel across stops; the two-second boundary is inclusive', () => {
    const gate = new TypingThrottle();
    expect(gate.allow('ch', true, 1000)).toBe(true);
    expect(gate.allow('ch', false, 1001)).toBe(true);
    expect(gate.allow('ch', true, 2999)).toBe(false);
    expect(gate.allow('other', true, 2999)).toBe(true);
    expect(gate.allow('ch', true, 3000)).toBe(true);
  });
  it('labels zero, one, two and several identities without merging equal names', () => {
    const a: TypingEntry = { account: 'a', member: 'kai', until: 7000 };
    const b: TypingEntry = { account: 'b', member: 'hidden', until: 7000 };
    const c: TypingEntry = { account: 'c', member: null, until: 7000 };
    // Only the own/visible member map is available; the hidden member falls back to account name.
    const names = (e: TypingEntry) => typingName(e, new Map([['kai', { name: 'Kai' }]]), [{ id: 'b', display_name: 'Moon', handle: null }]);
    expect(names(c)).toBe('Someone');
    expect(names({ ...a, member: null })).toBe('Someone');
    expect(typingLabel([], names)).toBeNull();
    expect(typingLabel([a, a], names)).toBe('Kai is typing…');
    expect(typingLabel([a, b], names)).toBe('Kai and Moon are typing…');
    expect(typingLabel([a, b, c], names)).toBe('Several people are typing…');
  });
});
