<script lang="ts">
  // F7.7: let someone follow us, choosing how much they hear and the bucket they go in. Starts on
  // the bucket new friends start in (F7.8) and its preset; a Custom bucket starts with no preset,
  // so accepting keeps it in charge. Friends › Requests and the Notifications inbox both use it.
  import { untrack } from 'svelte';
  import { buckets } from '../data';
  import { friends } from '../friends-state.svelte';
  import { acceptFollow, everyoneElsePreset, presetOf, startBucket } from '../follow-presets';
  import { PRESET_IDS, presetAbout, presetName } from '../friends-screens';
  import type { Projection } from '../sync/client';
  import BucketGlyph from './BucketGlyph.svelte';
  import Icon from './Icon.svelte';
  import Sheet from './Sheet.svelte';

  let { projection, followId, accountId, name, onclose, ondecline }: {
    projection: Projection; followId: string; accountId: string; name: string; onclose: () => void; ondecline: () => void;
  } = $props();
  const own = $derived(buckets(projection));
  // starts where new friends start (F7.8); picking another doesn't follow later changes
  let bucketId = $state<string | null>(untrack(() => startBucket(projection)));
  const bucket = $derived(own.find((b) => b.id === bucketId) ?? null);
  const inherited = $derived.by(() => {
    const fromBucket = bucket ? presetOf(bucket.ceiling) : 'inherit';
    const c = fromBucket !== 'inherit' ? fromBucket : everyoneElsePreset(projection);
    return (PRESET_IDS as readonly string[]).includes(c) ? c : null;
  });
  let picked = $state<string | null | undefined>(undefined);
  const preset = $derived(picked === undefined ? inherited : picked);
  let choosing = $state(false);

  function accept() {
    acceptFollow(projection, followId, accountId, preset, bucket);
    void friends.offerFollowBack(accountId, name);
    onclose();
  }
</script>

<Sheet title="Let {name} follow you?" subtitle="Choose how much they hear" {onclose}>
  <div class="card" role="radiogroup" aria-label="How much they hear">
    {#each PRESET_IDS as p (p)}
      <button class="choice" role="radio" aria-checked={preset === p} onclick={() => (picked = p)}>
        <span class="fr-radio" class:on={preset === p}></span>
        <span class="text"><strong>{p === 'close' ? 'Close — instant' : presetName(p)}</strong><span>{presetAbout(p)}</span></span>
      </button>
    {/each}
  </div>
  <button class="bucket" aria-haspopup="dialog" onclick={() => (choosing = true)}>
    <span class="fr-tile tile">{#if bucket}<BucketGlyph icon={bucket.icon} color={bucket.color} size={18} />{:else}<Icon name="user" size={18} />{/if}</span>
    <span class="text"><span>Bucket</span><strong>{bucket?.name ?? 'No bucket'}</strong></span>
    <span class="down"><Icon name="chevron" size={18} /></span>
  </button>
  {#snippet footer()}
    <button class="fr-wide neutral decline" onclick={ondecline}>Decline</button>
    <button class="fr-wide" onclick={accept}>{preset ? `Accept as ${presetName(preset)}` : 'Accept'}</button>
  {/snippet}
</Sheet>
{#if choosing}
  <Sheet title="Bucket" subtitle="Friends in a bucket hear the same things" layer={1} onclose={() => (choosing = false)}>
    <div class="options">
      <button class:on={!bucketId} onclick={() => { bucketId = null; picked = undefined; choosing = false; }}>
        <span class="fr-tile tile"><Icon name="user" size={18} /></span>
        <span class="text"><strong>No bucket</strong><span>Everyone else · {presetName(everyoneElsePreset(projection))}</span></span>
        {#if !bucketId}<Icon name="check" size={20} />{/if}
      </button>
      {#each own as b (b.id)}
        <button class:on={bucketId === b.id} onclick={() => { bucketId = b.id; picked = undefined; choosing = false; }}>
          <span class="fr-tile tile"><BucketGlyph icon={b.icon} color={b.color} size={18} /></span>
          <span class="text"><strong>{b.name}</strong><span>{presetName(presetOf(b.ceiling))}</span></span>
          {#if bucketId === b.id}<Icon name="check" size={20} />{/if}
        </button>
      {/each}
    </div>
  </Sheet>
{/if}

<style>
  .card { display: grid; border-radius: var(--r-md); background: var(--surface-2); overflow: hidden; }
  .choice { display: flex; align-items: center; gap: 14px; padding: 10px 14px; border: 0; border-top: 1px solid var(--line); background: none; color: var(--ink); font: inherit; text-align: left; cursor: pointer; }
  .choice:first-child { border-top: 0; }
  .text { flex: 1; display: grid; min-width: 0; }
  .text strong { font-size: var(--fs-base); font-weight: 400; }
  .text span { color: var(--ink-3); font-size: var(--fs-sm); line-height: 17px; }
  .bucket { display: flex; align-items: center; gap: var(--s-3); padding: 10px 14px; border: 0; border-radius: var(--r-md); background: var(--surface-2); color: var(--ink); font: inherit; text-align: left; cursor: pointer; }
  .bucket .text span { font-size: var(--fs-xs); }
  .bucket .text strong { font-weight: 600; }
  .tile { width: 36px; height: 36px; border-radius: 9px; background: var(--surface-3); color: var(--ink-3); }
  .down { color: var(--ink-3); transform: rotate(90deg); display: grid; }
  .decline { flex: .4; }
  .options { display: grid; gap: 2px; }
  .options button { display: flex; align-items: center; gap: 14px; padding: var(--s-2) var(--s-3); border: 0; border-radius: var(--r-row); background: none; color: var(--ink); font: inherit; text-align: left; cursor: pointer; }
  .options button.on { background: var(--accent-soft); }
  .options button.on strong { color: var(--accent); font-weight: 600; }
  .options button :global(.icon) { color: var(--accent); }
</style>
