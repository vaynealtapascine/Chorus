import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiFetch, RateLimitedError, retryAfterMs } from './http';

const limited = (retryAfter?: string) =>
  new Response(JSON.stringify({ error: { code: 'rate_limited' } }), {
    status: 429,
    headers: retryAfter === undefined ? {} : { 'retry-after': retryAfter },
  });

describe('apiFetch', () => {
  let calls: RequestInit[];
  let replies: Response[];
  beforeEach(() => {
    vi.useFakeTimers();
    calls = [];
    replies = [];
    vi.stubGlobal('fetch', async (_url: string, init: RequestInit) => {
      calls.push(init);
      return replies.shift() ?? new Response('{}', { status: 200 });
    });
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('retries a read after Retry-After', async () => {
    replies = [limited('2'), new Response('ok')];
    const p = apiFetch('/api/v1/me');
    await vi.advanceTimersByTimeAsync(1999);
    expect(calls.length).toBe(1);
    await vi.advanceTimersByTimeAsync(1);
    const r = await p;
    expect(calls.length).toBe(2);
    expect(await r.text()).toBe('ok');
  });

  it('gives up on a read after two retries', async () => {
    replies = [limited('1'), limited('1'), limited('3')];
    const p = apiFetch('/api/v1/me', { method: 'HEAD' });
    const seen = expect(p).rejects.toThrow(RateLimitedError);
    await vi.advanceTimersByTimeAsync(5000);
    await seen;
    expect(calls.length).toBe(3);
    await expect(p).rejects.toThrow('Try again in 3 seconds');
  });

  it('never retries a write, and says so kindly', async () => {
    replies = [limited('4'), new Response('ok')];
    await expect(apiFetch('/api/v1/tokens', { method: 'POST', body: '{}' })).rejects.toThrow(/too many requests.*4 seconds/);
    expect(calls.length).toBe(1);
  });

  it("doesn't wait out a long Retry-After", async () => {
    replies = [limited('60')];
    await expect(apiFetch('/api/v1/front')).rejects.toThrow('Try again in 60 seconds');
    expect(calls.length).toBe(1);
  });

  it('stops waiting when the caller aborts', async () => {
    replies = [limited('5')];
    const abort = new AbortController();
    const p = apiFetch('/api/v1/search/messages', { signal: abort.signal });
    const seen = expect(p).rejects.toBeDefined();
    await vi.advanceTimersByTimeAsync(10);
    abort.abort();
    await seen;
    expect(calls.length).toBe(1);
  });

  it('passes every other response through', async () => {
    replies = [new Response('nope', { status: 403 })];
    const r = await apiFetch('/api/v1/admin/health');
    expect(r.status).toBe(403);
    expect(calls.length).toBe(1);
  });
});

describe('retryAfterMs', () => {
  it('reads seconds and dates, else a second', () => {
    expect(retryAfterMs(limited('7'))).toBe(7000);
    const now = Date.parse('2026-09-24T10:00:00Z');
    expect(retryAfterMs(limited('Thu, 24 Sep 2026 10:00:05 GMT'), now)).toBe(5000);
    expect(retryAfterMs(limited())).toBe(1000);
    expect(retryAfterMs(limited('soon'))).toBe(1000);
  });
});
