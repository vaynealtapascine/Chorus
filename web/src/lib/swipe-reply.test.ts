import { describe, expect, it } from 'vitest';
import { intent, LIMIT, origin, pull, SLOP, THRESHOLD } from './swipe-reply';

describe('swipe to reply', () => {
  it('follows the finger at first, then resists, the same either way', () => {
    expect(pull(0)).toBe(0);
    expect(pull(10)).toBeCloseTo(10, 0);
    expect(pull(-10)).toBeCloseTo(-10, 0);
    let previous = 0;
    for (const drag of [10, 40, 90, 200, 1000]) {
      const shown = pull(drag);
      expect(shown).toBeGreaterThan(previous);
      expect(shown).toBeLessThan(LIMIT);
      expect(pull(-drag)).toBe(-shown);
      previous = shown;
    }
  });

  it('reaches the threshold, but only past the shown distance', () => {
    const finger = -LIMIT * Math.log(1 - THRESHOLD / LIMIT);
    expect(finger).toBeGreaterThan(THRESHOLD);
    expect(pull(finger)).toBeCloseTo(THRESHOLD, 5);
    expect(pull(-finger)).toBeCloseTo(-THRESHOLD, 5);
  });

  it('waits for the slop, then tells a sideways swipe from a scroll', () => {
    expect(intent(4, 3)).toBeNull();
    expect(intent(30, 5)).toBe('swipe');
    expect(intent(-30, 5)).toBe('swipe');
    expect(intent(5, 30)).toBe('scroll');
    // diagonal leans to the scroll so reading isn't interrupted
    expect(intent(20, 18)).toBe('scroll');
  });

  it('drops only the slop when a swipe starts, so a fast flick keeps its distance', () => {
    expect(origin(100, 100 + SLOP)).toBe(100 + SLOP);
    // a flick whose first event is already 70 px along still counts 60 of them
    expect(100 + 70 - origin(100, 170)).toBe(70 - SLOP);
    expect(100 - 70 - origin(100, 30)).toBe(-(70 - SLOP));
  });
});
