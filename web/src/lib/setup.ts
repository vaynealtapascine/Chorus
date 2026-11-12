// A new system's setup (F1.2-F1.4) is owed until it's finished or left: kept per account on this
// device, with the name typed on the welcome screen so setup can name the system (`system.set`)
// once the replica is open (as Android's data/Setup.kt).

const key = (accountId: string) => `chorus.setup:${accountId}`;

export const setupOwed = {
  begin(accountId: string, name: string) {
    try { localStorage.setItem(key(accountId), name); } catch { /* private mode: no setup, which is harmless */ }
  },
  /** The name typed at welcome while setup is owed (empty if none was), else null. */
  pending(accountId: string): string | null {
    try { return localStorage.getItem(key(accountId)); } catch { return null; }
  },
  done(accountId: string) {
    try { localStorage.removeItem(key(accountId)); } catch { /* nothing kept */ }
  },
};
