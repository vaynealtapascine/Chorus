<script lang="ts">
  // Add to a message (Figma F5.8; Android ChatAttach): tiles for photos, files, a poll and a Journal post,
  // what is picked as a tray, and a caption row with the eye (hide behind a tap) and send, so a photo is
  // pick, type, send. The caption is the composer's own text.
  import Icon from './Icon.svelte';
  import Sheet from './Sheet.svelte';
  import EmptyState from './kit/EmptyState.svelte';

  export interface Picked { file: File; alt: string; spoiler: boolean }
  export interface PostChoice { id: string; label: string }

  let { pending = $bindable(), draft = $bindable(), pollOn = false, canSend, journal, onphotos, onfiles, onpoll, onlink, onsend, onclose }: {
    pending: Picked[]; draft: string; pollOn?: boolean; canSend: boolean;
    /** own recent Journal posts the sheet can link to */
    journal: PostChoice[];
    onphotos: () => void; onfiles: () => void; onpoll: () => void; onlink: (id: string) => void; onsend: () => void; onclose: () => void;
  } = $props();

  let page = $state<'' | 'journal'>('');
  let urls = $state<string[]>([]);
  $effect(() => {
    const made = pending.map((p) => (p.file.type.startsWith('image/') ? URL.createObjectURL(p.file) : ''));
    urls = made;
    return () => made.forEach((u) => u && URL.revokeObjectURL(u));
  });
  const hidden = $derived(pending.length > 0 && pending.every((p) => p.spoiler));
  const toggleHidden = () => { const next = !hidden; pending = pending.map((p) => ({ ...p, spoiler: next })); };
  const go = () => { if (canSend) { onsend(); onclose(); } };
</script>

<Sheet title={page ? 'Journal' : 'Add to message'} subtitle={pending.length ? `${pending.length} picked` : null} {onclose}
  footer={page ? undefined : bar}>
  {#if page === 'journal'}
    {#each journal as post (post.id)}
      <button class="post" onclick={() => { onlink(post.id); page = ''; }}><Icon name="journal" size={18} /><span>{post.label}</span></button>
    {:else}
      <EmptyState icon="journal" label="No posts yet" />
    {/each}
    <button class="back" onclick={() => (page = '')}><Icon name="back" size={18} />Back</button>
  {:else}
    <div class="tiles">
      <button class="tile" style="--tint: #45a396" onclick={onphotos}><Icon name="image" size={24} />Photos</button>
      <button class="tile" style="--tint: #93bdeb" onclick={onfiles}><Icon name="doc" size={24} />Files</button>
      <button class="tile" class:on={pollOn} style="--tint: #e8836f" onclick={() => { onpoll(); onclose(); }}><Icon name="poll" size={24} />Poll</button>
      <button class="tile" style="--tint: #b9a5e8" onclick={() => (page = 'journal')}><Icon name="journal" size={24} />Journal</button>
    </div>
    {#if pending.length}
      <ul class="tray" aria-label="Picked">
        {#each pending as item, i (item.file.name + i)}
          <li class:spoiler={item.spoiler}>
            {#if urls[i]}<img src={urls[i]} alt={item.alt || item.file.name} />{:else}<span class="file"><Icon name="doc" size={22} /><small>{item.file.name}</small></span>{/if}
            <button class="x" aria-label="Remove {item.file.name}" onclick={() => (pending = pending.filter((_, n) => n !== i))}><Icon name="close" size={14} /></button>
          </li>
        {/each}
      </ul>
    {/if}
  {/if}
</Sheet>

{#snippet bar()}
  <input class="caption" bind:value={draft} placeholder="Add a caption…" aria-label="Caption" onkeydown={(e) => { if (e.key === 'Enter') go(); }} />
  {#if pending.length}<button class="eye" class:on={hidden} aria-pressed={hidden} aria-label="Hide behind a tap" onclick={toggleHidden}><Icon name={hidden ? 'eye-off' : 'eye'} size={20} /></button>{/if}
  <button class="go" disabled={!canSend} onclick={go} aria-label="Send" title="Send"><Icon name="send" size={20} /></button>
{/snippet}

<style>
  .tiles { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: var(--s-2); padding: 0 var(--s-4) var(--s-2); }
  .tile { display: grid; justify-items: center; gap: 6px; padding: var(--s-3) var(--s-1); border: 0; border-radius: var(--r-control); font: inherit; font-size: var(--fs-xs); font-weight: 600;
    background: color-mix(in srgb, var(--tint) 85%, transparent); color: #14231f; cursor: pointer; }
  .tile.on { outline: 2px solid var(--accent); }
  .tray { display: flex; gap: var(--s-2); margin: 0; padding: var(--s-2) var(--s-4) var(--s-3); list-style: none; overflow-x: auto; }
  .tray li { position: relative; flex: none; width: 76px; height: 76px; border-radius: var(--r-row); overflow: hidden; background: var(--surface-2); display: grid; place-items: center; }
  .tray img { width: 100%; height: 100%; object-fit: cover; }
  .tray li.spoiler img { filter: blur(10px); }
  .file { display: grid; justify-items: center; gap: 2px; padding: var(--s-1); color: var(--ink-2); }
  .file small { max-width: 68px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 10px; }
  .x { position: absolute; top: 4px; right: 4px; width: 22px; height: 22px; display: grid; place-items: center; border: 0; border-radius: 50%; background: rgba(0, 0, 0, 0.6); color: #fff; cursor: pointer; }
  .post, .back { display: flex; align-items: center; gap: 10px; width: 100%; min-height: 46px; padding: var(--s-2) var(--s-5); border: 0; background: none; color: var(--ink); font: inherit; font-size: var(--fs-md); text-align: left; cursor: pointer; }
  .post span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .back { color: var(--accent); }
  .caption { flex: 1; min-width: 0; min-height: 44px; padding: 0 var(--s-4); border: 0; border-radius: var(--r-lg); background: var(--surface-2); color: var(--ink); font: inherit; font-size: var(--fs-md); outline: 0; }
  .caption:focus { box-shadow: 0 0 0 1.5px color-mix(in srgb, var(--accent) 60%, transparent); }
  .eye, .go { flex: none; width: 44px; height: 44px; display: grid; place-items: center; border: 0; border-radius: 50%; cursor: pointer; }
  .eye { background: none; color: var(--ink-2); }
  .eye.on { background: var(--surface-2); color: var(--accent); }
  .go { background: var(--accent); color: var(--on-accent); }
  .go:disabled { background: var(--surface-3); color: var(--ink-3); cursor: default; }
</style>
