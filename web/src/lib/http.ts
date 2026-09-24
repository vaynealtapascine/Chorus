// Calls to the Chorus API that cope with its rate limit (API.md §1): a `429 rate_limited` carries
// `Retry-After` (seconds). Reads (GET/HEAD) wait that long and try again, a couple of times at
// most; a write, or a read that is still limited, fails with a message fit to show.

/** Retries after the first attempt, for GET/HEAD. */
const RETRIES = 2;
/** Longest wait before a retry; a longer `Retry-After` fails straight away instead. */
const MAX_WAIT_MS = 10_000;

export class RateLimitedError extends Error {
  readonly status = 429;
  constructor(readonly retryAfterMs: number) {
    const s = Math.max(1, Math.ceil(retryAfterMs / 1000));
    super(`Chorus is catching its breath — too many requests from here. Try again in ${s} second${s === 1 ? '' : 's'}.`);
    this.name = 'RateLimitedError';
  }
}

/** `Retry-After` in ms: delay-seconds or an HTTP date; 1 s when missing or unreadable. */
export function retryAfterMs(response: Response, now = Date.now()): number {
  const v = response.headers.get('retry-after')?.trim();
  if (!v) return 1000;
  if (/^\d+$/.test(v)) return Number(v) * 1000;
  const at = Date.parse(v);
  return Number.isNaN(at) ? 1000 : Math.max(0, at - now);
}

function wait(ms: number, signal?: AbortSignal | null): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) return reject(signal.reason);
    const timer = setTimeout(() => { signal?.removeEventListener('abort', stop); resolve(); }, ms);
    const stop = () => { clearTimeout(timer); reject(signal?.reason); };
    signal?.addEventListener('abort', stop, { once: true });
  });
}

/**
 * `fetch`, except that a 429 is retried for reads after `Retry-After` and otherwise throws a
 * {@link RateLimitedError}. Every other response (errors included) comes back as usual.
 */
export async function apiFetch(input: string, init: RequestInit = {}): Promise<Response> {
  const method = (init.method ?? 'GET').toUpperCase();
  const idempotent = method === 'GET' || method === 'HEAD';
  for (let attempt = 0; ; attempt++) {
    const response = await fetch(input, init);
    if (response.status !== 429) return response;
    const ms = retryAfterMs(response);
    if (!idempotent || attempt >= RETRIES || ms > MAX_WAIT_MS) throw new RateLimitedError(ms);
    await wait(ms, init.signal);
  }
}
