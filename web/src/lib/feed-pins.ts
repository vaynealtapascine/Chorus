// Feeds shown as Journal tabs, in order (`journal.pinned_feeds`, F6.8; the same account pref as
// Android): the built-ins "system", "friends", "entries", or a saved feed's id. Unset: the three built-ins.
import { sync, type Projection } from './sync/client';

export const PINNED_FEEDS = 'journal.pinned_feeds';
export const BUILT_IN_TABS = ['system', 'friends', 'entries'] as const;
export const BUILT_IN_NAMES: Record<string, string> = { system: 'Our system', friends: 'Friends', entries: 'Journals' };

/** The tabs to show: pins that still exist (`live` are saved feed ids), never none. */
export function pinnedTabs(stored: unknown, live: Set<string>): string[] {
  const list = Array.isArray(stored) ? stored.filter((x): x is string => typeof x === 'string' && !!x) : [...BUILT_IN_TABS];
  const tabs = [...new Set(list)].filter((id) => (BUILT_IN_TABS as readonly string[]).includes(id) || live.has(id));
  return tabs.length ? tabs : [...BUILT_IN_TABS];
}

/** `id` pinned (appended) or unpinned in `tabs`. */
export const pinned = (tabs: string[], id: string, pin: boolean): string[] => pin ? [...tabs.filter((t) => t !== id), id] : tabs.filter((t) => t !== id);

export function storedPins(p: Projection, account: string): unknown {
  const rows = p.rows.pref ?? {};
  return (rows[`||${PINNED_FEEDS}`] ?? rows[`${account}||${PINNED_FEEDS}`])?.fields.value;
}

/** Written whole, so two devices pinning at once keep the later list. */
export function savePins(ids: string[]) {
  sync.create('pref.set', sync.accountScope, null, { device: '', key: PINNED_FEEDS, value: ids });
}
