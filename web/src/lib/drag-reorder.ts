// The pure side of a drag-to-reorder list (kit/DragList.svelte): where a dragged row lands, and the
// order after a move. Rows are known by id; nothing here touches the page.

/** [ids] with the one at [from] moved to [to] (both clamped); the same array when nothing moves. */
export function moveId(ids: string[], from: number, to: number): string[] {
  const end = Math.max(0, Math.min(ids.length - 1, to));
  if (from < 0 || from >= ids.length || from === end) return ids;
  const next = [...ids];
  next.splice(end, 0, next.splice(from, 1)[0]);
  return next;
}

/** Where a dragged row goes: after every other row whose middle is above [y] (each `[top, height]`,
 *  the dragged row left out). */
export function dropIndex(others: [number, number][], y: number): number {
  let n = 0;
  for (const [top, height] of others) if (top + height / 2 < y) n++;
  return n;
}

/** The ids that changed place between two orders, for writing only what moved. */
export function movedIds(before: string[], after: string[]): string[] {
  return after.filter((id, i) => before[i] !== id);
}
