// Other accounts' posts read once per page load through the server's audience check: what our own
// posts quote, and what the composer is quoting (D-127). `undefined` while unasked or loading,
// `null` when this reader may not read it (or it's gone, or we're offline).
import { apiFetch } from './http';
import { apiBase } from './sync/device';
import { sync } from './sync/client';
import type { ThreadPost } from './post-threads';

export const remotePosts = $state<Record<string, ThreadPost | null>>({});
const asked = new Set<string>();

/** Keep a post already read elsewhere (a feed card being quoted). */
export function keepRemote(post: ThreadPost): void {
  asked.add(post.id);
  remotePosts[post.id] = post;
}

/** The post if it has been read; the first ask starts the read (outside any render). */
export function remotePost(id: string | null | undefined): ThreadPost | null | undefined {
  if (!id) return undefined;
  if (!asked.has(id)) {
    asked.add(id);
    queueMicrotask(async () => {
      try {
        const r = await apiFetch(`${apiBase()}/posts/${encodeURIComponent(id)}?depth=0`, {
          headers: { authorization: `Bearer ${sync.device?.session ?? ''}` },
        });
        remotePosts[id] = r.ok ? ((await r.json()) as ThreadPost) : null;
      } catch {
        asked.delete(id);
        remotePosts[id] = null;
      }
    });
  }
  return remotePosts[id];
}
