// The latest server reads behind the Notifications page and our own Journal cards (D-127): the
// inbox rows (sidebar badge) and other accounts' activity on our posts (reply counts, hearts).
import { apiFetch } from './http';
import { apiBase } from './sync/device';
import { sync, type Projection } from './sync/client';
import { selfMember, members } from './data';
import { deviceName, summary } from './front-reviews';
import { whoKey, whoMap } from './fronting-subjects';
import { fromActivity, fromNotifications, needsYou, withReactors, type FollowRow, type InboxItem, type JournalActivity } from './inbox';

export const inbox = $state<{ account: string; items: InboxItem[]; activity: JournalActivity | null; loaded: boolean }>({
  account: '', items: [], activity: null, loaded: false,
});

async function get(path: string): Promise<unknown> {
  const r = await apiFetch(`${apiBase()}${path}`, { headers: { authorization: `Bearer ${sync.device?.session ?? ''}` } });
  if (!r.ok) throw new Error(`HTTP ${r.status}`);
  return r.json();
}
const soft = async <T>(read: () => Promise<T>): Promise<T | null> => { try { return await read(); } catch { return null; } };

/** Other accounts' replies, quotes, reposts and hearts on our posts; `before` pages back. */
export async function loadActivity(before?: number): Promise<JournalActivity> {
  return withReactors(await get(`/posts/activity?limit=50${before === undefined ? '' : `&before=${before}`}`) as JournalActivity);
}

/** Read the first activity page again for our own cards' counts. */
export async function refreshActivity(): Promise<JournalActivity | null> {
  const a = await soft(() => loadActivity());
  if (a) { inbox.activity = a; inbox.account = sync.accountId; }
  return a;
}

let running: Promise<void> | null = null;
let last = 0;

/** Read every source again (at most every 15 s; one read at a time); one failing leaves the others
 * standing, all failing keeps what was there. */
export async function refreshInbox(projection: Projection, force = false): Promise<void> {
  if (running) return running;
  if (!force && inbox.account === sync.accountId && Date.now() - last < 15_000) return;
  running = readAll(projection).finally(() => { running = null; last = Date.now(); });
  return running;
}

async function readAll(projection: Projection): Promise<void> {
  const notes = await soft(async () => ((await get('/notifications')) as { items: unknown[] }).items);
  const activity = await refreshActivity();
  const follows = await soft(async () => ((await get('/follows')) as { followers: FollowRow[] }).followers);
  const alerts = await soft(async () => ((await get('/alerts')) as { items: Record<string, unknown>[] }).items);
  if (notes === null && activity === null && follows === null) return;
  const names = new Map(members(projection).map((m) => [m.id, m.display_name ?? m.name]));
  const reviews = (projection.reviews?.[sync.accountId] ?? []).filter((r) => !projection.rows.front_review?.[r.id]?.fields?.resolution);
  const who = whoMap(projection);
  const devices = new Map(Object.entries(projection.rows.device ?? {}).map(([id, row]) => [id, String(row.fields.name ?? '')]));
  const switches = new Map((projection.fronts[sync.accountId]?.switches ?? []).map((s) => [s.id, { ...s, entries: s.resulting_front }]));
  const said = (r: { switch_a: string; switch_b: string }) => summary(switches.get(r.switch_a), switches.get(r.switch_b), (t, id) => who.get(whoKey(t, id))?.name ?? 'Someone',
    (s) => deviceName(devices, s.device_id, sync.device?.device_id));
  inbox.items = [...fromNotifications(notes), ...fromActivity(activity),
    ...needsYou(follows ?? [], alerts ?? [], reviews, !!selfMember(projection), (id) => names.get(id), Date.now(), (r) => said(r as never))];
  inbox.account = sync.accountId;
  inbox.loaded = true;
}
