// What erasing a signed-out browser's copy removes, and what it leaves (D-152).
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { relinkPlan } from './device';

const wipe = vi.fn(async () => {});
const forgetBlobs = vi.fn(async () => {});
vi.mock('./persist', () => ({ wipe: () => wipe(), saveDevice: async () => {} }));
vi.mock('./blobs', () => ({ forgetBlobs: () => forgetBlobs() }));
vi.mock('../http', () => ({ apiFetch: async () => new Response('{}'), ApiError: Error }));
const { eraseKeptCopy, erasedHere, forgetErased } = await import('./erase');

class FakeStorage {
  [key: string]: unknown;
  getItem(k: string) { return Object.hasOwn(this, k) ? String(this[k]) : null; }
  setItem(k: string, v: string) { this[k] = String(v); }
  removeItem(k: string) { delete this[k]; }
}

let local: FakeStorage, session: FakeStorage;
const unsubscribe = vi.fn(async () => true);
beforeEach(() => {
  local = new FakeStorage();
  session = new FakeStorage();
  vi.stubGlobal('localStorage', local);
  vi.stubGlobal('sessionStorage', session);
  vi.stubGlobal('navigator', { serviceWorker: { getRegistration: async () => ({ pushManager: { getSubscription: async () => ({ unsubscribe }) } }) } });
});

describe('erasing a signed-out copy', () => {
  it("removes everything of the account's and keeps the browser's theme", async () => {
    local.setItem('chorus-unsaved-ops', '[{"id":"x"}]');
    local.setItem('chorus.members.collapsed', '["g"]');
    local.setItem('chorus.journal.name-notice', '1');
    local.setItem('chorus.theme', 'dark');
    local.setItem('chorus.journal-draft.v1:2:a1:main', '{"v":1,"body":"hi"}'); // the Journal draft (D-158)
    local.setItem('chorus.journal-shelf.v1:2:a1', '{"v":1,"drafts":[]}'); // and the drafts shelf
    local.setItem('other-site-thing', 'kept');
    session.setItem('chorus.journal-draft.v1:2:a1:reply:p', '{"v":1,"body":"hi"}');
    session.setItem('chorus.chat.draft:a1:c1', '{"text":"hi"}');
    session.setItem('chorus.chat.folder', 'all');
    await eraseKeptCopy();
    expect(wipe).toHaveBeenCalledTimes(1); // IndexedDB: ops, kv (device + key, meta, snapshot, settings), uploads
    expect(forgetBlobs).toHaveBeenCalledTimes(1); // the offline file cache
    expect(unsubscribe).toHaveBeenCalledTimes(1);
    expect(Object.keys(local).sort()).toEqual(['chorus.theme', 'other-site-thing']);
    expect(Object.keys(session)).toEqual(['chorus.erased-signed-out']);
    expect(erasedHere()).toBe(true);
    forgetErased();
    expect(erasedHere()).toBe(false);
  });
});

describe('enrolling a browser that holds a signed-out copy (D-153)', () => {
  it('picks it up only for a device link of the same account', () => {
    expect(relinkPlan(null, { kind: 'device', account_id: 'b' })).toBe('none');
    expect(relinkPlan('a', { kind: 'device', account_id: 'a' })).toBe('pick-up');
    expect(relinkPlan('a', { kind: 'device', account_id: 'b' })).toBe('other-account');
    expect(relinkPlan('a', { kind: 'device', account_id: null })).toBe('unknown-account');
    expect(relinkPlan('a', { kind: 'system', account_id: null })).toBe('new-account');
    expect(relinkPlan('a', { kind: 'person', account_id: null })).toBe('new-account');
    expect(relinkPlan('a', { kind: null, account_id: null })).toBe('unknown-account');
  });
});
