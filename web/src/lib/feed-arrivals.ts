// When the "New posts" pill shows (Android Journal.kt: announced, justPosted, atTop, newPosts).
export interface Arrivals { tab: string; newest: string | null; pill: boolean }
export const noArrivals: Arrivals | null = null;

/** After the feed changes: the next state and whether to scroll to the top now. */
export function arrive(before: Arrivals | null, tab: string, newest: string | null, o: { atTop: boolean; justPosted: string | null }): { state: Arrivals; scrollTop: boolean } {
  if (!before || before.tab !== tab) return { state: { tab, newest, pill: false }, scrollTop: false };
  if (before.newest === newest) return { state: before, scrollTop: false };
  if (newest === null) return { state: { tab, newest, pill: false }, scrollTop: false };
  if (newest === o.justPosted || o.atTop) return { state: { tab, newest, pill: false }, scrollTop: true };
  return { state: { tab, newest, pill: true }, scrollTop: false };
}

/** The reader reached the top (or tapped the pill): the pill goes. */
export function seen(state: Arrivals | null): Arrivals | null {
  return state && state.pill ? { ...state, pill: false } : state;
}
