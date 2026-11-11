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

/** A renewal the server refused for good: it no longer knows this device (signed out from another
 * one, API.md §2). A network error, a 5xx or a rate limit is worth trying again later. */
export function refusesDevice(e: unknown): boolean {
  return e instanceof ApiError && e.status === 403 && e.code === 'forbidden';
}
