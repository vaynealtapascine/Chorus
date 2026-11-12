// Calls to this account's server from the settings pages: the session as bearer, JSON in and out.
import { apiBase } from './sync/device';
import { apiFetch } from './http';
import { sync } from './sync/client';

export async function settingsApi<T = any>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await apiFetch(`${apiBase()}${path}`, {
    ...init,
    headers: { 'content-type': 'application/json', authorization: `Bearer ${sync.device?.session ?? ''}` },
  });
  const text = await response.text();
  const json = text ? JSON.parse(text) : {};
  if (!response.ok) throw new Error(json?.error?.message ?? `HTTP ${response.status}`);
  return json as T;
}

/** A pref the account or this device set (device-scoped first), as the settings pages read it. */
export function prefOf(rows: Record<string, { fields: Record<string, unknown> }> | undefined, key: string): unknown {
  return (rows?.[`||${key}`] ?? rows?.[`${sync.accountId}||${key}`])?.fields.value;
}

export function savePref(key: string, value: unknown) {
  sync.create('pref.set', sync.accountScope, null, { device: '', key, value });
}
