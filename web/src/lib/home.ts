// Chorus Home (D-071, docs/HOME.md): the setup and *This computer* settings a home-PC install
// offers to the browser on that PC (API.md §8a). Elsewhere these calls fail and the UI hides them.
import { apiBase } from './sync/device';
import { sync } from './sync/client';

export interface HomeStatus {
  needs_setup: boolean;
  version: string;
  port: number | null;
  lan: boolean;
  lan_port: number | null;
  lan_address: string | null;
  pin: string | null;
  data_dir: string;
  backup: { dir: string; keep_daily: number };
}

/** This server's Home status, or null when it isn't a Home install (or this isn't its PC). */
export async function homeStatus(): Promise<HomeStatus | null> {
  try {
    const r = await fetch(`${apiBase()}/home`);
    return r.ok ? ((await r.json()) as HomeStatus) : null;
  } catch {
    return null;
  }
}

async function call(method: string, path: string, body?: unknown) {
  const r = await fetch(`${apiBase()}${path}`, {
    method,
    headers: { 'content-type': 'application/json', authorization: `Bearer ${sync.device?.session ?? ''}` },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const j = await r.json().catch(() => null);
  if (!r.ok) throw new Error(j?.error?.message ?? `HTTP ${r.status}`);
  return j;
}

/** First run: a one-use invite for the first account, which this browser then redeems. */
export async function homeSetupCode(): Promise<string> {
  return (await call('POST', '/home/setup')).code as string;
}

export interface HomeSettings {
  port?: number;
  lan?: boolean;
  keep_daily?: number;
}

/** Save and restart; resolves to the address Chorus comes back on. */
export async function saveHomeSettings(s: HomeSettings): Promise<string> {
  return (await call('PUT', '/home/settings', s)).url as string;
}

/** Wait for Chorus to answer at `url` again after a restart (up to ~30 s). */
export async function waitForServer(url: string): Promise<boolean> {
  for (let i = 0; i < 60; i++) {
    await new Promise((r) => setTimeout(r, 500));
    try {
      if ((await fetch(`${url.replace(/\/$/, '')}/api/v1/server`, { cache: 'no-store' })).ok) return true;
    } catch {
      // still restarting
    }
  }
  return false;
}
