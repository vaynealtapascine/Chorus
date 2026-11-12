// Things the app says once, and the words of the connection banner (UI21 F2.5, F10.3, F10.7).
// Same decisions as Android's data/Notices.kt.

type Slow = (seconds: number | null) => void;
const listeners = new Set<Slow>();

/** The server said slow down (`429 rate_limited`, or the socket's `rate_limited` frame). */
export function slowDown(seconds: number | null): void {
  for (const l of listeners) l(seconds);
}
export function onSlowDown(l: Slow): () => void {
  listeners.add(l);
  return () => listeners.delete(l);
}

export function slowDownText(secondsLeft: number | null): string {
  return secondsLeft == null || secondsLeft <= 0
    ? 'Catching its breath. Try again in a moment.'
    : `Catching its breath. Try again in ${secondsLeft} ${secondsLeft === 1 ? 'second' : 'seconds'}.`;
}

export type Connection = 'none' | 'away' | 'asleep';

/** Failed or dropped sockets since the last welcome after which a reachable network means the server is down. */
export const ASLEEP_AFTER = 3;

/** A fresh start that is still connecting shows nothing; no network reads as away, repeated failures on a working one as asleep. */
export function connection(status: string, failures: number, network: boolean): Connection {
  if (status !== 'offline' && status !== 'connecting') return 'none';
  if (failures <= 0) return 'none';
  if (!network) return 'away';
  return failures >= ASLEEP_AFTER ? 'asleep' : 'away';
}

/** A Chorus Home install is "home"; the public server never is (D-095). */
export function connectionText(kind: Connection, home: boolean): string | null {
  switch (kind) {
    case 'none': return null;
    case 'away': return home ? 'Away from home. Changes wait on this device.' : 'Offline. Changes wait on this device.';
    case 'asleep': return home
      ? 'Chorus Home is asleep. Everything you do is saved here and sent later.'
      : "The server isn't answering. Everything you do is saved here and sent later.";
  }
}
