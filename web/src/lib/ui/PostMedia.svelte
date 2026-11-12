<script lang="ts">
  import { onDestroy, onMount } from 'svelte';
  import { loadObjectUrl, onSyncStatusChange, type BlobLoadState } from '../blob-url';
  import type { AttachmentRow } from '../data';
  import { formatAttachmentSize, fullTimestamp, postMediaPlan, singleImageAlt } from '../journal-presentation';
  import Icon from './Icon.svelte';

  let {
    attachments,
    who = '',
    occurredAt = 0,
  }: {
    attachments: readonly AttachmentRow[];
    who?: string;
    occurredAt?: number;
  } = $props();

  const plan = $derived(postMediaPlan(attachments));
  let revealed = $state<Set<string>>(new Set());
  let tick = $state(0);
  let blobStates = $state<Record<string, { state: BlobLoadState; url: string }>>({});

  let viewingIndex = $state<number | null>(null);
  let viewerState = $state<{ state: BlobLoadState; url: string }>({ state: 'idle', url: '' });
  let dialogEl = $state<HTMLDialogElement | undefined>();
  let triggerEl: HTMLElement | null = null;

  const singleAlt = $derived(singleImageAlt(plan.images, revealed));
  const activeImage = $derived(viewingIndex !== null ? plan.images[viewingIndex] ?? null : null);
  const activeCovered = $derived(Boolean(activeImage?.is_spoiler && !revealed.has(activeImage.id)));
  const activeSrc = $derived(
    viewerState.url || (activeImage && !activeCovered ? blobStates[activeImage.id]?.url ?? '' : ''),
  );

  onMount(() => onSyncStatusChange(() => { tick += 1; }));

  const activeLoaders = new Map<string, { hash: string; tick: number; stop: () => void }>();

  $effect(() => {
    const currentTick = tick;
    const wanted = new Map<string, string>();
    for (const img of plan.shownImages) {
      if (!img.is_spoiler || revealed.has(img.id)) {
        wanted.set(img.id, img.thumb_blob_hash ?? img.blob_hash);
      }
    }
    for (const file of plan.files) {
      if (!file.is_spoiler || revealed.has(file.id)) {
        wanted.set(file.id, file.blob_hash);
      }
    }
    for (const [id, entry] of activeLoaders) {
      const nextHash = wanted.get(id);
      const shouldRetry = entry.tick !== currentTick && blobStates[id]?.state === 'unavailable';
      if (nextHash !== entry.hash || shouldRetry) {
        entry.stop();
        activeLoaders.delete(id);
        delete blobStates[id];
      }
    }
    for (const [id, hash] of wanted) {
      if (activeLoaders.has(id)) continue;
      const stop = loadObjectUrl(hash, (state, url) => {
        blobStates[id] = { state, url };
      });
      activeLoaders.set(id, { hash, tick: currentTick, stop });
    }
  });

  onDestroy(() => {
    for (const entry of activeLoaders.values()) entry.stop();
    activeLoaders.clear();
  });

  $effect(() => {
    void tick;
    if (!activeImage || activeCovered) {
      viewerState = { state: 'idle', url: '' };
      return;
    }
    return loadObjectUrl(activeImage.blob_hash, (state, url) => {
      viewerState = { state, url };
    });
  });

  $effect(() => {
    if (dialogEl && viewingIndex !== null && !dialogEl.open) {
      dialogEl.showModal?.();
    }
  });

  function reveal(id: string) {
    const next = new Set(revealed);
    next.add(id);
    revealed = next;
  }

  function conceal(id: string) {
    const next = new Set(revealed);
    next.delete(id);
    revealed = next;
  }

  function markUnavailable(id: string) {
    const existing = activeLoaders.get(id);
    if (existing) {
      existing.stop();
      activeLoaders.delete(id);
    }
    blobStates[id] = { state: 'unavailable', url: '' };
  }

  function openViewer(index: number, el?: HTMLElement | null) {
    triggerEl = el ?? (typeof document !== 'undefined' ? (document.activeElement as HTMLElement | null) : null);
    viewingIndex = index;
  }

  function closeViewer() {
    if (dialogEl?.open) dialogEl.close?.();
    viewingIndex = null;
    const prev = triggerEl;
    triggerEl = null;
    prev?.focus?.();
  }

  function stepViewer(delta: number) {
    if (viewingIndex === null || plan.images.length <= 1) return;
    viewingIndex = (viewingIndex + delta + plan.images.length) % plan.images.length;
  }

  function onDialogKeydown(event: KeyboardEvent) {
    if (event.key === 'ArrowLeft') {
      event.preventDefault();
      stepViewer(-1);
    } else if (event.key === 'ArrowRight') {
      event.preventDefault();
      stepViewer(1);
    }
  }
</script>

{#if plan.shownImages.length || plan.files.length}
  <div class="post-media" data-media-viewer>
    {#if plan.shownImages.length}
      <div class="media-grid count-{plan.shownImages.length}" role="group"
        aria-label={plan.images.length === 1 ? 'Post image' : `${plan.images.length} post images`}>
        {#each plan.shownImages as img, index (img.id)}
          {@const covered = img.is_spoiler && !revealed.has(img.id)}
          {@const more = index === 3 ? plan.moreCount : 0}
          {@const entry = blobStates[img.id]}
          {@const desc = img.alt_text.trim() || img.filename}
          <div class="tile-cell" class:lead-tall={plan.shownImages.length === 3 && index === 0}>
            {#if covered}
              <button type="button" class="media-tile spoiler" aria-label="Reveal spoiler image"
                onclick={() => reveal(img.id)}>
                <span class="tile-note">Spoiler · click to reveal</span>
              </button>
            {:else}
              <button type="button" class="media-tile"
                aria-label={more > 0
                  ? `Open photo ${index + 1} of ${plan.images.length} (${more} more): ${desc}`
                  : `Open photo${plan.images.length > 1 ? ` ${index + 1} of ${plan.images.length}` : ''}: ${desc}`}
                onclick={(e) => openViewer(index, e.currentTarget)}>
                {#if entry?.state === 'ready' && entry.url}
                  <img src={entry.url} alt={desc} loading="lazy" decoding="async"
                    onerror={() => markUnavailable(img.id)} />
                {:else}
                  <span class="tile-note muted">
                    {entry?.state === 'unavailable' ? 'Loading, or unavailable offline.' : 'Loading image…'}
                  </span>
                {/if}
                {#if more > 0}
                  <span class="more-overlay" aria-hidden="true">+{more}</span>
                {/if}
                {#if plan.shownImages.length > 1 && img.alt_text.trim()}
                  <span class="alt-pill" aria-hidden="true">ALT</span>
                {/if}
              </button>
              {#if img.is_spoiler}
                <button type="button" class="rehide-chip" aria-label="Hide spoiler image"
                  onclick={() => conceal(img.id)}>Hide</button>
              {/if}
            {/if}
          </div>
        {/each}
      </div>
      {#if singleAlt}
        <p class="single-alt">{singleAlt}</p>
      {/if}
    {/if}

    {#if plan.files.length}
      <div class="file-list">
        {#each plan.files as file (file.id)}
          {@const covered = file.is_spoiler && !revealed.has(file.id)}
          {@const entry = blobStates[file.id]}
          {@const sizeLabel = formatAttachmentSize(file.size)}
          {#if covered}
            <button type="button" class="file-spoiler" aria-label="Reveal spoiler attachment"
              onclick={() => reveal(file.id)}>
              Spoiler attachment · Reveal
            </button>
          {:else}
            <div class="file-item">
              {#if entry?.state === 'ready' && entry.url}
                <a class="file-card" href={entry.url} download={file.filename} rel="noreferrer">
                  <span class="file-icon"><Icon name="doc" size={18} /></span>
                  <span class="file-meta">
                    <span class="file-name">{file.filename}</span>
                    {#if sizeLabel}<span class="file-sub">{sizeLabel}</span>{/if}
                  </span>
                  <span class="file-dl">Save</span>
                </a>
              {:else}
                <div class="file-card pending" aria-live="polite">
                  <span class="file-icon"><Icon name="doc" size={18} /></span>
                  <span class="file-meta">
                    <span class="file-name">{file.filename}</span>
                    <span class="file-sub">
                      {entry?.state === 'unavailable'
                        ? 'File unavailable offline.'
                        : `${sizeLabel ? `${sizeLabel} · ` : ''}Loading…`}
                    </span>
                  </span>
                </div>
              {/if}
              {#if file.alt_text.trim()}
                <p class="file-alt">{file.alt_text.trim()}</p>
              {/if}
              {#if file.is_spoiler}
                <button type="button" class="file-hide" onclick={() => conceal(file.id)}>Hide attachment</button>
              {/if}
            </div>
          {/if}
        {/each}
      </div>
    {/if}

    {#if activeImage}
      <dialog class="media-dialog" bind:this={dialogEl} aria-label="Image viewer"
        onclose={closeViewer}
        onclick={(e) => { if (e.target === dialogEl) closeViewer(); }}
        onkeydown={onDialogKeydown}>
        <div class="viewer-shell">
          <header class="viewer-bar">
            <div class="viewer-caption-top">
              {#if who}<strong>{who}</strong>{/if}
              {#if who && occurredAt}<span> · </span>{/if}
              {#if occurredAt}<time datetime={new Date(occurredAt).toISOString()}>{fullTimestamp(occurredAt)}</time>{/if}
              {#if plan.images.length > 1}
                <span class="viewer-count">{viewingIndex! + 1} / {plan.images.length}</span>
              {/if}
            </div>
            <div class="viewer-actions">
              {#if !activeCovered && activeSrc}
                <a class="viewer-btn" href={activeSrc} download={activeImage.filename} rel="noreferrer">Save</a>
              {/if}
              <button type="button" class="viewer-icon-btn" aria-label="Close image viewer" onclick={closeViewer}>
                <Icon name="close" size={20} />
              </button>
            </div>
          </header>

          <div class="viewer-stage">
            {#if plan.images.length > 1}
              <button type="button" class="viewer-nav prev" aria-label="Previous photo" onclick={() => stepViewer(-1)}>
                <span class="flip"><Icon name="chevron" size={22} /></span>
              </button>
            {/if}
            <div class="viewer-frame">
              {#if activeCovered}
                <button type="button" class="viewer-spoiler" onclick={() => reveal(activeImage.id)}>
                  Spoiler · click to reveal
                </button>
              {:else if activeSrc}
                <img src={activeSrc} alt={activeImage.alt_text.trim() || activeImage.filename}
                  onerror={() => { viewerState = { state: 'unavailable', url: '' }; }} />
              {:else}
                <p class="viewer-status">
                  {viewerState.state === 'unavailable' ? 'Image unavailable offline.' : 'Loading full image…'}
                </p>
              {/if}
            </div>
            {#if plan.images.length > 1}
              <button type="button" class="viewer-nav next" aria-label="Next photo" onclick={() => stepViewer(1)}>
                <Icon name="chevron" size={22} />
              </button>
            {/if}
          </div>

          {#if !activeCovered && activeImage.alt_text.trim()}
            <footer class="viewer-footer">
              <p class="viewer-alt">{activeImage.alt_text.trim()}</p>
            </footer>
          {/if}
        </div>
      </dialog>
    {/if}
  </div>
{/if}

<style>
  .post-media { display: grid; gap: var(--s-2); min-width: 0; }

  .media-grid {
    display: grid;
    width: 100%;
    aspect-ratio: 16 / 9;
    gap: 2px;
    border-radius: var(--r-row);
    overflow: hidden;
    background: var(--line);
  }
  .media-grid.count-1 { grid-template-columns: minmax(0, 1fr); grid-template-rows: minmax(0, 1fr); }
  .media-grid.count-2 { grid-template-columns: repeat(2, minmax(0, 1fr)); grid-template-rows: minmax(0, 1fr); }
  .media-grid.count-3,
  .media-grid.count-4 {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    grid-template-rows: repeat(2, minmax(0, 1fr));
  }
  .tile-cell { position: relative; min-width: 0; min-height: 0; }
  .tile-cell.lead-tall { grid-row: 1 / span 2; }

  .media-tile {
    position: relative;
    display: grid;
    place-items: center;
    width: 100%;
    height: 100%;
    padding: 0;
    border: 0;
    background: var(--surface-3);
    color: var(--ink-2);
    cursor: pointer;
    overflow: hidden;
    transition: background-color var(--t-fast) var(--ease), transform var(--t-fast) var(--ease);
  }
  .media-tile img {
    display: block;
    width: 100%;
    height: 100%;
    object-fit: cover;
    transition: transform var(--t-base) var(--ease);
  }
  @media (hover: hover) and (pointer: fine) {
    .media-tile:hover img { transform: scale(1.015); }
    .media-tile.spoiler:hover { background: var(--surface-2); }
  }
  .media-tile:active { transform: scale(0.99); }

  .tile-note {
    padding: var(--s-2) var(--s-3);
    font-size: var(--fs-sm);
    font-weight: 500;
    text-align: center;
  }
  .tile-note.muted { color: var(--ink-3); font-weight: 400; font-size: var(--fs-xs); }

  .more-overlay {
    position: absolute;
    inset: 0;
    display: grid;
    place-items: center;
    background: rgb(0 0 0 / 0.52);
    color: #fff;
    font-size: var(--fs-lg);
    font-weight: 700;
    font-variant-numeric: tabular-nums;
  }
  .alt-pill {
    position: absolute;
    left: var(--s-2);
    bottom: var(--s-2);
    padding: 2px 6px;
    border-radius: 6px;
    background: rgb(0 0 0 / 0.65);
    color: #fff;
    font-size: 11px;
    font-weight: 700;
    letter-spacing: 0.03em;
  }
  .rehide-chip {
    position: absolute;
    top: var(--s-2);
    right: var(--s-2);
    min-height: 28px;
    padding: var(--s-1) 10px;
    border: 0;
    border-radius: var(--r-full);
    background: rgb(0 0 0 / 0.62);
    color: #fff;
    font-size: var(--fs-xs);
    cursor: pointer;
    transition: opacity var(--t-fast) var(--ease), transform var(--t-fast) var(--ease);
  }
  .rehide-chip:active { transform: scale(0.97); }

  .single-alt {
    margin: 0;
    color: var(--ink-3);
    font-size: var(--fs-xs);
    line-height: 1.4;
  }

  .file-list { display: grid; gap: var(--s-2); }
  .file-item { display: grid; gap: var(--s-1); }
  .file-spoiler {
    justify-self: start;
    min-height: 44px;
    padding: var(--s-2) var(--s-3);
    border: 1px solid var(--line);
    border-radius: var(--r-sm);
    background: var(--surface-2);
    color: var(--accent);
    font-size: var(--fs-sm);
    font-weight: 500;
    cursor: pointer;
    transition: background-color var(--t-fast) var(--ease), transform var(--t-fast) var(--ease);
  }
  .file-spoiler:active { transform: scale(0.985); }

  .file-card {
    display: flex;
    align-items: center;
    gap: var(--s-3);
    min-height: 52px;
    padding: var(--s-2) var(--s-3);
    border-radius: var(--r-sm);
    background: var(--surface-3);
    color: var(--ink);
    text-decoration: none;
    min-width: 0;
    transition: background-color var(--t-fast) var(--ease), transform var(--t-fast) var(--ease);
  }
  @media (hover: hover) and (pointer: fine) {
    a.file-card:hover { background: var(--surface-2); }
  }
  a.file-card:active { transform: scale(0.99); }
  .file-icon { color: var(--accent); display: inline-grid; place-items: center; flex: none; }
  .file-meta { display: grid; min-width: 0; flex: 1; }
  .file-name {
    font-size: var(--fs-sm);
    font-weight: 500;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .file-sub { color: var(--ink-3); font-size: var(--fs-xs); font-variant-numeric: tabular-nums; }
  .file-dl { color: var(--accent); font-size: var(--fs-xs); font-weight: 600; flex: none; }
  .file-alt { margin: 0; color: var(--ink-3); font-size: var(--fs-xs); }
  .file-hide {
    justify-self: start;
    border: 0;
    background: none;
    padding: var(--s-1) 0;
    color: var(--accent);
    font-size: var(--fs-xs);
    cursor: pointer;
  }

  .media-dialog {
    width: min(96vw, 1040px);
    max-height: 92vh;
    padding: 0;
    border: 1px solid var(--line);
    border-radius: var(--r-md);
    background: var(--surface);
    color: var(--ink);
    box-shadow: var(--shadow-pop);
    animation: dialog-in var(--t-base) var(--ease) both;
  }
  .media-dialog::backdrop {
    background: rgb(10 11 14 / 0.82);
  }
  @keyframes dialog-in {
    from { opacity: 0; transform: scale(0.96); }
    to { opacity: 1; transform: scale(1); }
  }
  .viewer-shell {
    display: grid;
    grid-template-rows: auto minmax(0, 1fr) auto;
    max-height: 92vh;
  }
  .viewer-bar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--s-3);
    padding: var(--s-2) var(--s-3) var(--s-2) var(--s-4);
    border-bottom: 1px solid var(--line);
  }
  .viewer-caption-top {
    color: var(--ink-2);
    font-size: var(--fs-sm);
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .viewer-caption-top strong { color: var(--ink); }
  .viewer-count { margin-left: var(--s-2); color: var(--ink-3); font-variant-numeric: tabular-nums; }
  .viewer-actions { display: flex; align-items: center; gap: var(--s-2); flex: none; }
  .viewer-btn {
    padding: 6px var(--s-3);
    border-radius: var(--r-full);
    background: var(--surface-2);
    color: var(--ink);
    font-size: var(--fs-xs);
    font-weight: 600;
    text-decoration: none;
  }
  .viewer-icon-btn {
    display: inline-grid;
    place-items: center;
    width: 40px;
    height: 40px;
    border: 0;
    border-radius: 50%;
    background: transparent;
    color: var(--ink);
    cursor: pointer;
    transition: background-color var(--t-fast) var(--ease);
  }
  @media (hover: hover) and (pointer: fine) {
    .viewer-icon-btn:hover, .viewer-nav:hover { background: var(--surface-2); }
  }
  .viewer-stage {
    position: relative;
    display: grid;
    grid-template-columns: auto minmax(0, 1fr) auto;
    align-items: center;
    gap: var(--s-2);
    padding: var(--s-3);
    min-height: 240px;
    overflow: hidden;
    background: var(--bg);
  }
  .viewer-frame {
    grid-column: 2;
    display: grid;
    place-items: center;
    min-width: 0;
    min-height: 0;
  }
  .viewer-frame img {
    display: block;
    max-width: 100%;
    max-height: 70vh;
    width: auto;
    height: auto;
    object-fit: contain;
    border-radius: var(--r-sm);
  }
  .viewer-nav {
    display: inline-grid;
    place-items: center;
    width: 44px;
    height: 44px;
    border: 0;
    border-radius: 50%;
    background: var(--surface);
    color: var(--ink);
    cursor: pointer;
    transition: background-color var(--t-fast) var(--ease), transform var(--t-fast) var(--ease);
  }
  .viewer-nav:active { transform: scale(0.96); }
  .flip { display: inline-grid; transform: rotate(180deg); }
  .viewer-spoiler {
    padding: var(--s-3) var(--s-4);
    border: 1px solid var(--line);
    border-radius: var(--r-sm);
    background: var(--surface-2);
    color: var(--ink);
    cursor: pointer;
  }
  .viewer-status { margin: 0; color: var(--ink-3); font-size: var(--fs-sm); }
  .viewer-footer {
    padding: var(--s-3) var(--s-4);
    border-top: 1px solid var(--line);
    background: var(--surface);
  }
  .viewer-alt { margin: 0; color: var(--ink-2); font-size: var(--fs-sm); line-height: 1.45; }

  @media (prefers-reduced-motion: reduce) {
    .media-dialog { animation: none; }
    .media-tile, .media-tile img, .rehide-chip, .file-spoiler, .file-card, .viewer-icon-btn, .viewer-nav {
      transition: none;
    }
  }
</style>
