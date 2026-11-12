import { describe, expect, it } from 'vitest';
import { connection, connectionText, slowDownText } from './notices';

describe('connection banner', () => {
  it('shows nothing before a first failure or while live', () => {
    expect(connection('connecting', 0, true)).toBe('none');
    expect(connection('live', 5, true)).toBe('none');
  });
  it('reads away without a network, asleep after repeated failures', () => {
    expect(connection('offline', 1, false)).toBe('away');
    expect(connection('offline', 1, true)).toBe('away');
    expect(connection('offline', 3, true)).toBe('asleep');
    expect(connection('offline', 3, false)).toBe('away');
  });
  it('words Home installs differently', () => {
    expect(connectionText('away', false)).toBe('Offline. Changes wait on this device.');
    expect(connectionText('away', true)).toMatch(/^Away from home/);
    expect(connectionText('none', true)).toBeNull();
  });
  it('counts down the slow-down text', () => {
    expect(slowDownText(12)).toBe('Catching its breath. Try again in 12 seconds.');
    expect(slowDownText(1)).toContain('1 second.');
    expect(slowDownText(null)).toContain('in a moment');
  });
});
