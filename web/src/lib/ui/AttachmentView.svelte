<script lang="ts">
  import { onMount } from 'svelte';
  import type { AttachmentRow } from '../data';
  import { sync } from '../sync/client';
  import { loadBlob } from '../sync/blobs';

  let { attachment, blur = false, revealable = true }: { attachment: AttachmentRow; blur?: boolean; revealable?: boolean } = $props();
  let revealed = $state(false);
  let src = $state('');
  let tick = $state(0);
  const hash = $derived(attachment.thumb_blob_hash ?? attachment.blob_hash);
  onMount(() => {
    let status = sync.status;
    return sync.subscribe(() => {
      if (sync.status !== status) { status = sync.status; tick += 1; }
    });
  });
  $effect(() => {
    void tick;
    const wanted = hash;
    let cancelled = false;
    let objectUrl = '';
    src = '';
    void (async () => {
      const blob = await loadBlob(wanted, sync.device);
      if (!blob || cancelled) return;
      objectUrl = URL.createObjectURL(blob);
      src = objectUrl;
    })();
    return () => { cancelled = true; if (objectUrl) URL.revokeObjectURL(objectUrl); };
  });
</script>

<div class="attachment" class:concealed={(attachment.is_spoiler || blur) && !revealed}>
  {#if attachment.mime.startsWith('image/')}
    {#if src}
      <a href={src} target="_blank" rel="noreferrer" aria-label={attachment.alt_text || attachment.filename}>
        <img src={src} alt={attachment.alt_text || attachment.filename} loading="lazy" />
      </a>
    {:else}<span class="loading">Image queued…</span>{/if}
  {:else}
    {#if src}<a href={src} download={attachment.filename}>↓ {attachment.filename}</a>
    {:else}<span class="loading">{attachment.filename} queued…</span>{/if}
  {/if}
  {#if (attachment.is_spoiler || blur) && !revealed && revealable}
    <button class="reveal" onclick={() => (revealed = true)}>Reveal {attachment.is_spoiler ? 'spoiler' : 'attachment'}</button>
  {/if}
</div>

<style>
  .attachment { position: relative; display: inline-block; max-width: min(100%, 480px); margin: var(--s-1) var(--s-1) var(--s-1) 0; vertical-align: top; }
  img { display: block; max-width: 100%; max-height: 360px; border-radius: var(--r-md); border: 1px solid var(--line); }
  .attachment > a { color: var(--accent); }
  .loading { color: var(--ink-3); font-size: var(--fs-sm); }
  .concealed img, .concealed > a { filter: blur(16px); }
  .reveal { position: absolute; inset: 0; width: 100%; border: 0; background: rgb(0 0 0 / 0.25); color: white; cursor: pointer; border-radius: var(--r-md); }
</style>
