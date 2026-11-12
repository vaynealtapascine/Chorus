// Which message actions show first (Figma F5.6: Reply, Reply in thread, Copy text, Pin, Forward, Edit,
// Select, Delete) and which wait behind More, each in a fixed order. Port of Android's data/ChatActions.kt;
// React comes first on the web, where there is no emoji row above the menu.
export const PRIMARY = ['react', 'reply', 'thread', 'copy', 'pin', 'forward', 'edit', 'select', 'delete'] as const;
export const MORE = ['private', 'reply_in', 'quote', 'link', 'history'] as const;
export type Action = (typeof PRIMARY)[number] | (typeof MORE)[number];

/** `available` split in two; a More holding a single action would cost a tap for nothing, so that one
 * action joins the first rows, just above Delete. */
export function layout(available: Iterable<Action>): { primary: Action[]; more: Action[] } {
  const have = new Set(available);
  const primary: Action[] = PRIMARY.filter((a) => have.has(a));
  const more: Action[] = MORE.filter((a) => have.has(a));
  if (more.length !== 1) return { primary, more };
  const at = primary.indexOf('delete') >= 0 ? primary.indexOf('delete') : primary.length;
  return { primary: [...primary.slice(0, at), ...more, ...primary.slice(at)], more: [] };
}
