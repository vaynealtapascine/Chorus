/**
 * Hold a finger on a message or a chat row to open its menu (owner, 2026-11-06), as a right click
 * does with a mouse; Android's combinedClickable long press does the same. Touch and pen only: a
 * mouse has its context menu. The finger may wander a little; moving further is a scroll or a
 * swipe, and lifting first is an ordinary tap.
 */

/** How long a finger rests before the menu opens (ms), as Android's long-press timeout. */
export const HOLD_MS = 500;
/** How far it may drift and still count as resting (px). */
export const SLOP = 10;

export interface LongPressOptions {
  enabled: boolean;
  /** Presses that start on these keep their own long press (a link's, a video's). */
  skip?: string;
  /** Opens the menu, at the point where the finger rests. */
  onpress: (at: { x: number; y: number }) => void;
}

export function longPress(node: EventTarget, options: LongPressOptions) {
  let opts = options;
  let pointer: number | null = null;
  let x0 = 0;
  let y0 = 0;
  let timer: ReturnType<typeof setTimeout> | undefined;
  // the press has opened the menu: the lift isn't also a tap, and the browser's own menu stays shut
  let fired = false;

  function stop() {
    clearTimeout(timer);
    timer = undefined;
    pointer = null;
  }

  function down(e: Event) {
    const p = e as PointerEvent;
    fired = false;
    if (!opts.enabled || p.pointerType === 'mouse' || pointer !== null) return;
    if (opts.skip && (p.target as Element | null)?.closest?.(opts.skip)) return;
    pointer = p.pointerId;
    x0 = p.clientX;
    y0 = p.clientY;
    timer = setTimeout(() => {
      timer = undefined;
      if (pointer === null) return;
      fired = true;
      navigator.vibrate?.(10);
      opts.onpress({ x: x0, y: y0 });
    }, HOLD_MS);
  }

  function move(e: Event) {
    const p = e as PointerEvent;
    if (p.pointerId !== pointer || fired) return;
    if (Math.hypot(p.clientX - x0, p.clientY - y0) > SLOP) stop();
  }

  function end(e: Event) {
    if ((e as PointerEvent).pointerId === pointer) stop();
  }

  const swallow = (e: Event) => {
    if (!fired) return;
    e.preventDefault();
    e.stopPropagation();
  };
  const click = (e: Event) => {
    swallow(e);
    fired = false;
  };

  node.addEventListener('pointerdown', down);
  node.addEventListener('pointermove', move);
  node.addEventListener('pointerup', end);
  node.addEventListener('pointercancel', end);
  node.addEventListener('contextmenu', swallow, true);
  node.addEventListener('click', click, true);
  return {
    update(next: LongPressOptions) {
      opts = next;
    },
    destroy() {
      stop();
      node.removeEventListener('pointerdown', down);
      node.removeEventListener('pointermove', move);
      node.removeEventListener('pointerup', end);
      node.removeEventListener('pointercancel', end);
      node.removeEventListener('contextmenu', swallow, true);
      node.removeEventListener('click', click, true);
    },
  };
}
