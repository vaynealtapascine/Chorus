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

/** What a touch that has moved (dx, dy) turns out to be: a sideways swipe, a scroll, or not yet known. */
export function intent(dx: number, dy: number, slop = 10): 'swipe' | 'scroll' | null {
  if (Math.hypot(dx, dy) < slop) return null;
  return Math.abs(dx) > Math.abs(dy) * 1.5 ? 'swipe' : 'scroll';
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
      // start from here, as Android drops the slop, so the bubble doesn't jump
      x0 = e.clientX;
      clearTimeout(settle);
      node.classList.remove('returning');
      node.classList.add('swiping');
    }
    if (mode === 'swipe') show(pull(e.clientX - x0));
  }

  function end(e: PointerEvent, release: boolean) {
    if (e.pointerId !== pointer) return;
    pointer = null;
    if (mode !== 'swipe') return;
    mode = null;
    swallowClick = true;
    const reply = release && armed && opts.enabled;
    node.classList.remove('swiping');
    node.classList.add('returning');
    show(0);
    settle = setTimeout(() => node.classList.remove('returning'), 320);
    if (reply) opts.onreply();
  }

  const up = (e: PointerEvent) => end(e, true);
  const cancel = (e: PointerEvent) => end(e, false);
  // a swipe that ends over the bubble isn't also a tap on it
  const click = (e: MouseEvent) => {
    if (!swallowClick) return;
    swallowClick = false;
    e.stopPropagation();
    e.preventDefault();
  };

  node.addEventListener('pointerdown', down);
  node.addEventListener('pointermove', move);
  node.addEventListener('pointerup', up);
  node.addEventListener('pointercancel', cancel);
  node.addEventListener('click', click, true);
  return {
    update(next: SwipeOptions) {
      opts = next;
    },
    destroy() {
      clearTimeout(settle);
      node.removeEventListener('pointerdown', down);
      node.removeEventListener('pointermove', move);
      node.removeEventListener('pointerup', up);
      node.removeEventListener('pointercancel', cancel);
      node.removeEventListener('click', click, true);
    },
  };
}
