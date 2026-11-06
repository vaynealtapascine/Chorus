import { beforeEach, describe, expect, it, vi } from 'vitest';

const loads: Record<string, number> = {};
let failing = false;
vi.mock('./sync/blobs', () => ({
  loadBlob: (hash: string) => {
    loads[hash] = (loads[hash] ?? 0) + 1;
    return Promise.resolve(failing ? null : new Blob([hash], { type: 'image/png' }));
  },
}));
vi.mock('./sync/client', () => ({ sync: { device: null, status: 'live', subscribe: () => () => {} } }));

const { holdObjectUrl, sharedObjectUrl } = await import('./blob-url');
const settle = () => new Promise((r) => setTimeout(r, 0));

describe('shared avatar URLs', () => {
  beforeEach(() => { failing = false; });

  it('loads a picture once for every place that shows it, and hands it over at once afterwards', async () => {
    const got: string[] = [];
    const a = holdObjectUrl('h1', (u) => got.push(u));
    const b = holdObjectUrl('h1', (u) => got.push(u));
    expect(sharedObjectUrl('h1')).toBe('');
    await settle();
    expect(loads.h1).toBe(1);
    expect(got).toHaveLength(2);
    expect(got[0]).toBe(got[1]);
    a(); b();
    // re-created after everything let go: still there, synchronously
    expect(sharedObjectUrl('h1')).toBe(got[0]);
    let now = '';
    const c = holdObjectUrl('h1', (u) => (now = u));
    expect(now).toBe(got[0]);
    expect(loads.h1).toBe(1);
    c();
  });

  it('retries a failed load when held again', async () => {
    failing = true;
    let url = '';
    holdObjectUrl('h2', (u) => (url = u))();
    await settle();
    expect(url).toBe('');
    failing = false;
    const release = holdObjectUrl('h2', (u) => (url = u));
    await settle();
    expect(url).not.toBe('');
    expect(loads.h2).toBe(2);
    release();
  });

  it('lets go of the oldest pictures nothing shows once many are kept', async () => {
    const first = holdObjectUrl('old', () => {});
    await settle();
    first();
    for (let i = 0; i < 70; i++) {
      holdObjectUrl(`n${i}`, () => {})();
    }
    expect(sharedObjectUrl('old')).toBe('');
  });
});
