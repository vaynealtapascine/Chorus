import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { HOLD_MS, longPress, SLOP } from './long-press';

function pointer(type: string, at: { x: number; y: number }, pointerType = 'touch', pointerId = 1): Event {
  return Object.assign(new Event(type, { cancelable: true }), { clientX: at.x, clientY: at.y, pointerType, pointerId });
}

describe('long press', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  function setup(enabled = true) {
    const node = new EventTarget();
    const onpress = vi.fn();
    const action = longPress(node, { enabled, onpress });
    return { node, onpress, action };
  }

  it('opens the menu where a resting finger is, and the lift is not a tap', () => {
    const { node, onpress } = setup();
    node.dispatchEvent(pointer('pointerdown', { x: 40, y: 80 }));
    node.dispatchEvent(pointer('pointermove', { x: 40 + SLOP - 2, y: 80 }));
    vi.advanceTimersByTime(HOLD_MS - 1);
    expect(onpress).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1);
    expect(onpress).toHaveBeenCalledWith({ x: 40, y: 80 });
    const menu = new Event('contextmenu', { cancelable: true });
    node.dispatchEvent(menu);
    expect(menu.defaultPrevented).toBe(true);
    node.dispatchEvent(pointer('pointerup', { x: 40, y: 80 }));
    const click = new Event('click', { cancelable: true });
    node.dispatchEvent(click);
    expect(click.defaultPrevented).toBe(true);
    // the next tap is an ordinary one
    const next = new Event('click', { cancelable: true });
    node.dispatchEvent(next);
    expect(next.defaultPrevented).toBe(false);
  });

  it('is a tap, a scroll or a swipe when the finger lifts or moves first', () => {
    const { node, onpress } = setup();
    node.dispatchEvent(pointer('pointerdown', { x: 0, y: 0 }));
    node.dispatchEvent(pointer('pointerup', { x: 0, y: 0 }));
    vi.advanceTimersByTime(HOLD_MS);
    node.dispatchEvent(pointer('pointerdown', { x: 0, y: 0 }));
    node.dispatchEvent(pointer('pointermove', { x: 0, y: SLOP + 1 }));
    vi.advanceTimersByTime(HOLD_MS);
    expect(onpress).not.toHaveBeenCalled();
  });

  it('leaves a mouse its own right click, and does nothing while disabled', () => {
    const mouse = setup();
    mouse.node.dispatchEvent(pointer('pointerdown', { x: 0, y: 0 }, 'mouse'));
    vi.advanceTimersByTime(HOLD_MS);
    expect(mouse.onpress).not.toHaveBeenCalled();
    const off = setup(false);
    off.node.dispatchEvent(pointer('pointerdown', { x: 0, y: 0 }));
    vi.advanceTimersByTime(HOLD_MS);
    expect(off.onpress).not.toHaveBeenCalled();
    off.action.update({ enabled: true, onpress: off.onpress });
    off.node.dispatchEvent(pointer('pointerdown', { x: 0, y: 0 }));
    vi.advanceTimersByTime(HOLD_MS);
    expect(off.onpress).toHaveBeenCalledOnce();
  });
});
