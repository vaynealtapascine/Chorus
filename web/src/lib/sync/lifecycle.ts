// The sync connection's states and the moves between them (SYNC §6.5, D-148). Pure: client.ts
// owns the socket and the timers and asks these what to do.
import { ApiError } from '../http';

/**
 * `no-device` until this browser is enrolled. The syncing tab then goes `offline` → `connecting`
 * → `live`; an `unauthenticated` answer sends it `renewing` (signing a new session), and a server
 * that no longer knows the device's key ends it `signed-out` (D-148). The other tabs show the
 * syncing tab's status (SYNC §6.1).
 */
export type Status = 'no-device' | 'offline' | 'connecting' | 'live' | 'renewing' | 'signed-out';

/** The legal moves. `no-device` and `signed-out` end the lifecycle until the page starts again. */
export const MOVES: Readonly<Record<Status, readonly Status[]>> = {
  'no-device': [],
  offline: ['connecting'],
  connecting: ['live', 'offline', 'renewing'],
  live: ['offline', 'renewing'],
  renewing: ['connecting', 'offline', 'signed-out'],
  'signed-out': [],
};

/** A live socket that has heard nothing for this long sends a `ping`... */
export const PING_AFTER_MS = 25_000;
/** ...and is given up if nothing at all arrives this long after it. */
export const PONG_GRACE_MS = 10_000;
/** A socket not welcomed by then is given up too (a proxy that took the upgrade and went quiet). */
export const WELCOME_WITHIN_MS = PING_AFTER_MS + PONG_GRACE_MS;

/**
 * What a live connection does at `now`, having last heard anything at `heard` and last pinged at
 * `pinged`: `next` is when to ask again. Any frame counts as life, so a busy socket never pings.
 */
export function heartbeat(now: number, heard: number, pinged: number | null): { act: 'wait' | 'ping' | 'close'; next: number } {
  if (pinged !== null && heard <= pinged) {
    return now - pinged >= PONG_GRACE_MS ? { act: 'close', next: now } : { act: 'wait', next: pinged + PONG_GRACE_MS };
  }
  return now - heard >= PING_AFTER_MS ? { act: 'ping', next: now + PONG_GRACE_MS } : { act: 'wait', next: heard + PING_AFTER_MS };
}

/**
 * A renewal the server refused for good, and what it said: signed out from another device
 * (`403 signed_out`, with when, and when this copy is erased, D-152), a device it doesn't know
 * (`403 forbidden`: an older server, or one that lost it) or a key that no longer matches
 * (`401 bad_key`, D-153); the last two give no times, so nothing is erased on a timer. Null for
 * anything worth trying again later: a network error, a 5xx, a rate limit, a stale nonce.
 */
export function signedOutBy(e: unknown): { at: number | null; wipe_at: number | null } | null {
  if (!(e instanceof ApiError)) return null;
  if (e.status === 403 && e.code === 'signed_out') {
    const s = (e.body as { signed_out?: { at?: unknown; wipe_at?: unknown } } | null)?.signed_out;
    const ms = (v: unknown) => (typeof v === 'number' && Number.isFinite(v) ? v : null);
    return { at: ms(s?.at), wipe_at: ms(s?.wipe_at) };
  }
  if ((e.status === 403 && e.code === 'forbidden') || (e.status === 401 && e.code === 'bad_key')) return { at: null, wipe_at: null };
  return null;
}

/** How long until a signed-out copy is erased (≤ 0: now), by the server's clock; null: never on a timer. */
export function wipeIn(s: { wipe_at: number | null; offset: number }, now: number): number | null {
  return s.wipe_at === null ? null : s.wipe_at - (now + s.offset);
}
