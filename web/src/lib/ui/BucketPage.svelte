<script lang="ts">
  // A bucket (F7.10), or a new one: its look, its preset and every detail as a row, who's in it, and
  // deleting it. Changes save as they're made; the name saves when it changes.
  import SectionLabel from './kit/SectionLabel.svelte';
  import '../design/friends.css';
  import { onMount } from 'svelte';
  import { BUCKET_SWATCHES, DEFAULT_BUCKET_ICON } from '../bucket-controls';
  import { bucketAssignments, buckets, selfMember } from '../data';
  import { accountDefault, presetCeiling, presetOf } from '../follow-presets';
  import { friends } from '../friends-state.svelte';
  import { accountColor, personName, PRESET_IDS, presetAbout, presetName } from '../friends-screens';
  import { sync, type Projection } from '../sync/client';
  import BucketGlyph from './BucketGlyph.svelte';
  import BucketIconPicker from './BucketIconPicker.svelte';
  import CeilingDetails from './CeilingDetails.svelte';
  import FriendFace from './FriendFace.svelte';
  import Icon from './Icon.svelte';
  import Sheet from './Sheet.svelte';

  let { bucketId, projection, dark }: { bucketId: string | null; projection: Projection; dark: boolean } = $props();
  onMount(() => { if (!friends.loaded) void friends.load(); });
  // a new bucket's id, so the page stays on it once it exists
  const newId = sync.newId();
  const id = $derived(bucketId ?? newId);
  const bucket = $derived(buckets(projection).find((b) => b.id === id) ?? null);
  const person = $derived(!!selfMember(projection));
  const inside = $derived(bucketAssignments(projection).get(id) ?? new Set<string>());
  let name = $state('');
  let icon = $state(DEFAULT_BUCKET_ICON);
  let color = $state<string | null>(null);
  let preset = $state('gentle');
  let deleting = $state(false);
  let look = $state(false);
  let named = false;
  $effect(() => { if (bucket && !named) { name = bucket.name; named = true; } });
  const set = (payload: Record<string, unknown>) => sync.create('bucket.set', sync.accountScope, id, payload);
  function saveName() { if (bucket && name.trim() && name.trim() !== bucket.name) set({ name: name.trim() }); }
  function create() {
    set({ name: name.trim(), icon, color, ceiling: person ? {} : presetCeiling(preset) });
    named = true;
  }
  const choice = $derived(bucket ? presetOf(bucket.ceiling) : 'inherit');
</script>

<section class="fr-page">
  <header class="fr-bar">
    <a class="fr-icon ink" href="#/people" aria-label="Back" onclick={saveName}><Icon name="back" /></a>
    <div class="fr-titles"><strong>{bucket?.name ?? 'New bucket'}</strong><span>Bucket{bucket ? ` · ${inside.size} ${inside.size === 1 ? 'friend' : 'friends'}` : ''}</span></div>
    {#if bucket}<a class="fr-icon accent" href="#/people" aria-label="Done" onclick={saveName}><Icon name="check" /></a>{/if}
  </header>
  <div class="look">
    <button class="fr-tile tile" aria-label="Icon and colour" onclick={() => (look = true)}
      style:--fr-tile={(bucket?.color ?? color) ? `color-mix(in srgb, ${bucket?.color ?? color} 22%, transparent)` : null}>
      <BucketGlyph icon={bucket?.icon ?? icon} color={bucket?.color ?? color} size={24} /></button>
    <label class="fr-field name"><span>NAME</span><input bind:value={name} maxlength="60" onblur={saveName} onkeydown={(e) => e.key === 'Enter' && saveName()} /></label>
  </div>
  <div class="swatches" role="radiogroup" aria-label="Colour">
    {#each BUCKET_SWATCHES.slice(0, 7) as s (s)}
      {@const on = (bucket?.color ?? color) === s}
      <button role="radio" aria-checked={on} aria-label="Colour {s}" class:on style:--c={s} onclick={() => { color = s; if (bucket) set({ color: s }); }}></button>
    {/each}
  </div>
  {#if !bucket}
    {#if !person}
      <SectionLabel inset label="Preset" />
      <div class="fr-seg" role="group" aria-label="Preset">{#each PRESET_IDS as p (p)}<button aria-pressed={preset === p} onclick={() => (preset = p)}>{presetName(p)}</button>{/each}</div>
      <p class="fr-about">{presetAbout(preset)}</p>
    {/if}
    <div class="create"><button class="fr-wide" disabled={!name.trim()} onclick={create}>Create bucket</button></div>
  {:else}
    {#if !person}
      <SectionLabel inset label="Preset" aside={!(PRESET_IDS as readonly string[]).includes(choice) ? choice === 'inherit' ? 'Everyone else’s' : 'Custom' : null} />
      <div class="fr-seg" role="group" aria-label="Preset">{#each PRESET_IDS as p (p)}<button aria-pressed={choice === p} onclick={() => set({ ceiling: presetCeiling(p, bucket.ceiling) })}>{presetName(p)}</button>{/each}</div>
      <SectionLabel inset label="Details" aside="inherits from Everyone else" />
      <CeilingDetails {projection} ceiling={bucket.ceiling} inherited="Everyone else" beneath={accountDefault(projection)} onchange={(c) => set({ ceiling: c })} />
    {/if}
    <SectionLabel inset label="Who’s in it" aside={inside.size} />
    {#if !friends.activeFollowers.length}<p class="fr-line quiet pad">No followers yet.</p>{/if}
    {#each friends.activeFollowers as f (f.id)}
      {@const on = inside.has(f.account.id)}
      <button class="fr-toggle" role="checkbox" aria-checked={on} onclick={() => sync.create(on ? 'bucket.unassign' : 'bucket.assign', sync.accountScope, id, { follower_account_id: f.account.id })}>
        <FriendFace name={personName(f.account)} color={accountColor(f.account.id, BUCKET_SWATCHES)} blob={f.account.avatar_blob} size={36} {dark} />
        <span class="fr-text"><strong>{personName(f.account)}</strong></span>
        <span class="check" class:on>{#if on}<Icon name="check" size={16} />{/if}</span>
      </button>
    {/each}
    <button class="fr-danger" onclick={() => (deleting = true)}>Delete bucket</button>
  {/if}
</section>

{#if look}
  <Sheet title="Icon and colour" onclose={() => (look = false)}>
    <BucketIconPicker icon={bucket?.icon ?? icon} color={bucket?.color ?? color} name={bucket?.name ?? (name || 'New bucket')}
      onicon={(i) => { icon = i; if (bucket) set({ icon: i }); }} oncolor={(c) => { color = c; if (bucket) set({ color: c }); }} />
  </Sheet>
{/if}
{#if deleting && bucket}
  <Sheet title="Delete “{bucket.name}”?" onclose={() => (deleting = false)}>
    <p class="fr-line">Anything shared only with it goes back to just you. The friends in it stay friends.</p>
    {#snippet footer()}
      <button class="fr-wide neutral" onclick={() => (deleting = false)}>Keep</button>
      <button class="fr-wide remove" onclick={() => { sync.create('bucket.delete', sync.accountScope, id, {}); deleting = false; location.hash = '#/people'; }}>Delete</button>
    {/snippet}
  </Sheet>
{/if}

<style>
  .look { display: flex; align-items: center; gap: var(--s-3); padding: var(--s-4) var(--s-4) 0; }
  .tile { width: 56px; height: 56px; border-radius: var(--r-control); border: 0; cursor: pointer; color: var(--ink-2); }
  .name { flex: 1; }
  .swatches { display: flex; gap: 10px; padding: 14px var(--s-4) var(--s-1); }
  .swatches button { width: 30px; height: 30px; border-radius: 50%; border: 2px solid var(--bg); background: var(--c); cursor: pointer; padding: 0; }
  .swatches button.on { border-color: var(--ink); }
  .create { display: flex; padding: var(--s-4); }
  .pad { padding: 0 var(--s-4); }
  .check { width: 24px; height: 24px; border-radius: 7px; border: 2px solid var(--ink-3); display: grid; place-items: center; color: var(--on-accent); }
  .check.on { background: var(--accent); border-color: var(--accent); }
  .remove { background: var(--danger); }
</style>
