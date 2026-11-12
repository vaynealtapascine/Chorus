// Black-outs by pointer (F8.6): a click blacks out a word (or gives it back), a press and drag blacks
// out what it passes over. A mouse drags at once; a finger presses for a moment first, so that
// scrolling still works. Offsets are UTF-16 units into the node's text, which is what the stage keeps.

export interface PickOptions {
  enabled: boolean;
  ontap: (index: number) => void;
  /** while dragging: the two ends so far; null when it ends or is cancelled */
  ondragging: (ends: [number, number] | null) => void;
  ondrag: (from: number, to: number) => void;
}

type CaretDocument = Document & {
  caretPositionFromPoint?: (x: number, y: number) => { offsetNode: Node; offset: number } | null;
  caretRangeFromPoint?: (x: number, y: number) => Range | null;
};

/** The index into `node`'s text under a point, or null off the text. */
export function textOffsetAt(node: HTMLElement, x: number, y: number): number | null {
  const doc = document as CaretDocument;
  let target: Node | null | undefined;
  let offset = 0;
  if (doc.caretPositionFromPoint) {
    const p = doc.caretPositionFromPoint(x, y);
    target = p?.offsetNode;
    offset = p?.offset ?? 0;
  } else if (doc.caretRangeFromPoint) {
    const r = doc.caretRangeFromPoint(x, y);
    target = r?.startContainer;
    offset = r?.startOffset ?? 0;
  }
  if (!target || !node.contains(target)) return null;
  const range = document.createRange();
  range.setStart(node, 0);
  range.setEnd(target, offset);
  return range.toString().length;
}

const PRESS_MS = 350;

export function pick(node: HTMLElement, initial: PickOptions) {
  let opts = initial;
  let state: { id: number; x: number; y: number; from: number | null; touch: boolean; drag: boolean; moved: boolean; timer?: ReturnType<typeof setTimeout> } | null = null;

  function end() {
    if (state?.timer) clearTimeout(state.timer);
    state = null;
  }
  function down(e: PointerEvent) {
    if (!opts.enabled || e.button > 0) return;
    const touch = e.pointerType === 'touch';
    state = { id: e.pointerId, x: e.clientX, y: e.clientY, from: textOffsetAt(node, e.clientX, e.clientY), touch, drag: false, moved: false };
    if (touch) {
      const mine = state;
      state.timer = setTimeout(() => {
        if (state !== mine) return;
        mine.drag = true;
        try { node.setPointerCapture(mine.id); } catch { /* the pointer is already gone */ }
        navigator.vibrate?.(10);
        if (mine.from != null) opts.ondragging([mine.from, mine.from]);
      }, PRESS_MS);
    }
  }
  function move(e: PointerEvent) {
    if (!state || e.pointerId !== state.id) return;
    const far = Math.hypot(e.clientX - state.x, e.clientY - state.y) > (state.touch ? 8 : 4);
    if (far) state.moved = true;
    if (!state.drag) {
      if (!far) return;
      if (state.touch) { end(); return; } // a scroll, not a press
      state.drag = true;
      try { node.setPointerCapture(e.pointerId); } catch { /* ignore */ }
    }
    const to = textOffsetAt(node, e.clientX, e.clientY);
    if (state.from != null && to != null) opts.ondragging([state.from, to]);
  }
  function up(e: PointerEvent) {
    if (!state || e.pointerId !== state.id) return;
    const { from, drag, moved } = state;
    const to = textOffsetAt(node, e.clientX, e.clientY);
    end();
    if (drag) {
      opts.ondragging(null);
      if (from != null && to != null) opts.ondrag(from, to);
    } else if (!moved && from != null) opts.ontap(from);
  }
  function cancel() {
    if (state?.drag) opts.ondragging(null);
    end();
  }
  // once the press has begun, the page must not scroll under the finger
  function touchmove(e: TouchEvent) { if (state?.drag) e.preventDefault(); }

  node.addEventListener('pointerdown', down);
  node.addEventListener('pointermove', move);
  node.addEventListener('pointerup', up);
  node.addEventListener('pointercancel', cancel);
  node.addEventListener('touchmove', touchmove, { passive: false });
  return {
    update(next: PickOptions) { opts = next; },
    destroy() {
      end();
      node.removeEventListener('pointerdown', down);
      node.removeEventListener('pointermove', move);
      node.removeEventListener('pointerup', up);
      node.removeEventListener('pointercancel', cancel);
      node.removeEventListener('touchmove', touchmove);
    },
  };
}
