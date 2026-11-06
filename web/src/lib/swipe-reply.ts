/**
 * Swipe a message either way to reply (owner, 2026-11-02), on touch only; Android's ChatGestures.kt
 * does the same. The row's children follow the finger with growing resistance through `--swipe`,
 * and `.swipe-hint` waits on the side they leave.
 */

/** Shown distance (px) at which a release replies. */
export const THRESHOLD = 64;
/** The bubble never travels further than this, however far the finger does. */
export const LIMIT = 128;

/** Rubber band: 1:1 with the finger at first, easing toward `limit`, either way. */
export function pull(drag: number, limit = LIMIT): number {
  return Math.sign(drag) * limit * (1 - Math.exp(-Math.abs(drag) / limit));
}

/** Movement (px) before a touch counts as a swipe or a scroll. */
export const SLOP = 10;

/** What a touch that has moved (dx, dy) turns out to be: a sideways swipe, a scroll, or not yet known. */
export function intent(dx: number, dy: number, slop = SLOP): 'swipe' | 'scroll' | null {
  if (Math.hypot(dx, dy) < slop) return null;
  return Math.abs(dx) > Math.abs(dy) * 1.5 ? 'swipe' : 'scroll';
}

/**
 * Where a swipe's travel is measured from once it is recognised: only the slop is dropped, so a
 * fast flick whose first event is already far along keeps the distance it covered.
 */
export function origin(x0: number, x: number, slop = SLOP): number {
  const dx = x - x0;
  return Math.abs(dx) <= slop ? x : x0 + Math.sign(dx) * slop;
}

export interface SwipeOptions {
  enabled: boolean;
  onreply: () => void;
}

/** Things that take sideways touches themselves. */
const OWN_GESTURES = 'pre, input, textarea, video, audio, [data-no-swipe]';

export function swipeReply(node: HTMLElement, options: SwipeOptions) {
  let opts = options;
  let pointer: number | null = null;
  let x0 = 0;
  let y0 = 0;
  let mode: 'swipe' | 'scroll' | null = null;
  let armed = false;
  let swallowClick = false;
  let settle: ReturnType<typeof setTimeout> | undefined;

  function show(px: number) {
    node.style.setProperty('--swipe', `${px}px`);
    node.style.setProperty('--swipe-p', String(Math.min(1, Math.abs(px) / THRESHOLD)));
    node.classList.toggle('toward-end', px > 0);
    const now = Math.abs(px) >= THRESHOLD;
    if (now && !armed) navigator.vibrate?.(8);
    armed = now;
    node.classList.toggle('armed', now);
  }

  function down(e: PointerEvent) {
    swallowClick = false;
    if (!opts.enabled || e.pointerType !== 'touch' || pointer !== null) return;
    if ((e.target as Element).closest(OWN_GESTURES)) return;
    pointer = e.pointerId;
    x0 = e.clientX;
    y0 = e.clientY;
    mode = null;
  }

  function move(e: PointerEvent) {
    if (e.pointerId !== pointer) return;
    if (mode === null) {
      mode = intent(e.clientX - x0, e.clientY - y0);
      if (mode !== 'swipe') return;
      // drop only the slop, as Android does, so the bubble doesn't jump and a flick keeps its distance
      x0 = origin(x0, e.clientX);
      clearTimeout(settle);
      node.classList.remove('returning');
      node.classList.add('swiping');
      // keep the finger's events here even when it leaves the row
      try {
        node.setPointerCapture(e.pointerId);
      } catch {
        /* the pointer is already gone */
      }
    }
    if (mode === 'swipe') show(pull(e.clientX - x0));
  }

  // Safari (and Chrome when the finger drifts) start scrolling during a long or hard swipe and
  // cancel the pointer; refusing the touch's default once it leans sideways keeps it ours.
  function touchmove(e: TouchEvent) {
    if (pointer === null || mode === 'scroll' || e.touches.length !== 1) return;
    const t = e.touches[0];
    if (mode === 'swipe' || Math.abs(t.clientX - x0) > Math.abs(t.clientY - y0)) {
      if (e.cancelable) e.preventDefault();
    }
  }

  function end(e: PointerEvent) {
    if (e.pointerId !== pointer) return;
    pointer = null;
    if (mode !== 'swipe') return;
    mode = null;
    swallowClick = true;
    // once armed the reply is meant, even if the browser took the touch away before the release
    const reply = armed && opts.enabled;
    node.classList.remove('swiping');
    node.classList.add('returning');
    show(0);
    settle = setTimeout(() => node.classList.remove('returning'), 320);
    if (reply) opts.onreply();
  }

  // a swipe that ends over the bubble isn't also a tap on it
  const click = (e: MouseEvent) => {
    if (!swallowClick) return;
    swallowClick = false;
    e.stopPropagation();
    e.preventDefault();
  };

  node.addEventListener('pointerdown', down);
  node.addEventListener('pointermove', move);
  node.addEventListener('pointerup', end);
  node.addEventListener('pointercancel', end);
  node.addEventListener('touchmove', touchmove, { passive: false });
  node.addEventListener('click', click, true);
  return {
    update(next: SwipeOptions) {
      opts = next;
    },
    destroy() {
      clearTimeout(settle);
      node.removeEventListener('pointerdown', down);
      node.removeEventListener('pointermove', move);
      node.removeEventListener('pointerup', end);
      node.removeEventListener('pointercancel', end);
      node.removeEventListener('touchmove', touchmove);
      node.removeEventListener('click', click, true);
    },
  };
}
