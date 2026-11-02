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
