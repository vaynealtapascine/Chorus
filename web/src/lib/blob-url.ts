import { loadBlob } from './sync/blobs';
import { sync } from './sync/client';

export type BlobLoadState = 'idle' | 'loading' | 'ready' | 'unavailable';

/** Subscribe to sync status transitions so offline/pending blobs retry when live. */
export function onSyncStatusChange(onChange: () => void): () => void {
  let status = sync.status;
  return sync.subscribe(() => {
    if (sync.status !== status) {
      status = sync.status;
      onChange();
    }
  });
}

/** Load a content-addressed blob into an object URL, revoking it on cleanup. */
export function loadObjectUrl(
  hash: string,
  onState: (state: BlobLoadState, url: string) => void,
): () => void {
  if (!hash) {
    onState('unavailable', '');
    return () => {};
  }
  let cancelled = false;
  let objectUrl = '';
  onState('loading', '');
  void loadBlob(hash, sync.device)
    .then((blob) => {
      if (cancelled) return;
      if (!blob) {
        onState('unavailable', '');
        return;
      }
      objectUrl = URL.createObjectURL(blob);
      onState('ready', objectUrl);
    })
    .catch(() => {
      if (!cancelled) onState('unavailable', '');
    });
  return () => {
    cancelled = true;
    if (objectUrl) URL.revokeObjectURL(objectUrl);
  };
}

/**
 * Pictures shown in many places at once (avatars): one object URL per hash for the whole page,
 * kept while anything shows it and for a while after, so a picture that is re-created (a list
 * re-sorting, a chat re-rendering) shows at once instead of blinking through its letter.
 */
type Shared = { url: string; refs: number; loading: boolean; waiting: Set<(url: string) => void> };
const shared = new Map<string, Shared>();
/** Hashes nothing shows right now, oldest first; past this many the oldest are let go. */
const unused: string[] = [];
const KEEP_UNUSED = 64;

/** The object URL of a picture already loaded on this page, or ''. */
export function sharedObjectUrl(hash: string | null | undefined): string {
  return (hash && shared.get(hash)?.url) || '';
}

/**
 * Hold the shared object URL for `hash`: `onUrl` gets it now when it is loaded, else once it is.
 * Call again to retry a failed load (the sync status changed). Returns the release.
 */
export function holdObjectUrl(hash: string, onUrl: (url: string) => void): () => void {
  let s = shared.get(hash);
  if (!s) shared.set(hash, (s = { url: '', refs: 0, loading: false, waiting: new Set() }));
  const entry = s;
  entry.refs += 1;
  const at = unused.indexOf(hash);
  if (at >= 0) unused.splice(at, 1);
  if (entry.url) onUrl(entry.url);
  else {
    entry.waiting.add(onUrl);
    if (!entry.loading) {
      entry.loading = true;
      void loadBlob(hash, sync.device)
        .catch(() => null)
        .then((blob) => {
          entry.loading = false;
          if (!blob || shared.get(hash) !== entry) return;
          entry.url = URL.createObjectURL(blob);
          for (const f of entry.waiting) f(entry.url);
          entry.waiting.clear();
        });
    }
  }
  let released = false;
  return () => {
    if (released) return;
    released = true;
    entry.waiting.delete(onUrl);
    entry.refs -= 1;
    if (entry.refs > 0) return;
    unused.push(hash);
    while (unused.length > KEEP_UNUSED) {
      const old = unused.shift()!;
      const gone = shared.get(old);
      if (!gone || gone.refs > 0) continue;
      shared.delete(old);
      if (gone.url) URL.revokeObjectURL(gone.url);
    }
  };
}
