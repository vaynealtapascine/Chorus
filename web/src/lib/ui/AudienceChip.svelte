<script lang="ts">
  // Who can see one profile part, field, or the whole profile (D-101). A chip that opens a small
  // menu: (follow the profile / field default), Only us, Friends, Followers, Following (D-129), chosen
  // buckets (past eight, the rest fold under "N more", D-115) and everyone on this server.
  import { audienceLabel, isPrivate, sameAudience, type Audience } from '../profile-audience';
  import Icon from './Icon.svelte';
  import BucketLabel from './BucketLabel.svelte';
  import BucketGlyph from './BucketGlyph.svelte';
  import { listedBuckets, type BucketPresentation } from '../bucket-controls';

  let {
    value,
    own,
    inherit,
    buckets,
    label,
    limited = false,
    onchange,
    defaultAudience,
    onusedefault,
  }: {
    /** What it's actually shared with now. */
    value: Audience;
    /** Whether that's its own choice (else it follows [inherit]). */
    own: boolean;
    /** "Same as profile" / "Field default" — absent for the profile itself. */
    inherit?: string;
    buckets: BucketPresentation[];
    /** What this chip is for, for screen readers: "Pronouns". */
    label: string;
    /** The profile itself is only us, so this can't reach anyone yet. */
    limited?: boolean;
    onchange: (a: Audience | null) => void;
    /** A new post's chip (D-117): the audience new posts start with, and making the one shown it. */
    defaultAudience?: Audience;
    onusedefault?: (on: boolean) => void;
  } = $props();

  let open = $state(false);
  let allBuckets = $state(false);
  const names = $derived(new Map(buckets.map((b) => [b.id, b.name])));
  const live = $derived(new Set(buckets.map((b) => b.id)));
  const priv = $derived(isPrivate(value, live) || limited);
  // while it follows the profile, ticking a bucket starts its own choice from scratch
  const chosenBuckets = $derived(value.mode === 'buckets' && (own || !inherit) ? value.bucket_ids.filter((id) => live.has(id)) : []);
  const listed = $derived(listedBuckets(buckets, chosenBuckets, allBuckets));
  const single = $derived(value.mode === 'buckets' && chosenBuckets.length === 1 ? buckets.find((b) => b.id === chosenBuckets[0]) : undefined);
  const text = $derived(limited && !isPrivate(value, live) ? `${audienceLabel(value, names)} (once shared)` : audienceLabel(value, names));

  function pick(a: Audience | null) {
    onchange(a);
    open = false;
  }
  function toggleBucket(id: string, on: boolean) {
    const ids = new Set(chosenBuckets);
    if (on) ids.add(id); else ids.delete(id);
    onchange(ids.size ? { mode: 'buckets', bucket_ids: [...ids] } : { mode: 'private' });
  }
</script>

<svelte:window onclick={() => (open = false)} onkeydown={(e) => { if (e.key === 'Escape') open = false; }} />

<span class="wrap">
  <button type="button" class="chip" class:shared={!priv} class:own aria-haspopup="menu" aria-expanded={open}
    aria-label="{label}: {text}{own || !inherit ? '' : ` (${inherit.toLowerCase()})`}. Change who can see it"
    title={own || !inherit ? undefined : inherit}
    onclick={(e) => { e.stopPropagation(); open = !open; }}>
    {#if !priv && single}<BucketGlyph icon={single.icon} color={single.color} size={14} />
    {:else}<Icon name={priv ? 'lock' : value.mode === 'server' ? 'globe' : 'users'} size={14} />{/if}
    <span>{text}</span>
  </button>
  {#if open}
    <div class="menu" role="menu" tabindex="-1" onclick={(e) => e.stopPropagation()} onkeydown={() => {}}>
      <p class="menu-title">Who can see {label.toLowerCase()}</p>
      {#if inherit}
        <button role="menuitemradio" aria-checked={!own} onclick={() => pick(null)}>{inherit}</button>
      {/if}
      <button role="menuitemradio" aria-checked={own || !inherit ? value.mode === 'private' : false} onclick={() => pick({ mode: 'private' })}><Icon name="lock" size={16} /> Only us</button>
      <button role="menuitemradio" aria-checked={(own || !inherit) && value.mode === 'friends'} onclick={() => pick({ mode: 'friends' })}><Icon name="users" size={16} /> Friends <span class="sub">you follow each other</span></button>
      <button role="menuitemradio" aria-checked={(own || !inherit) && value.mode === 'followers'} onclick={() => pick({ mode: 'followers' })}><Icon name="users" size={16} /> Followers <span class="sub">everyone who follows us</span></button>
      <button role="menuitemradio" aria-checked={(own || !inherit) && value.mode === 'following'} onclick={() => pick({ mode: 'following' })}><Icon name="users" size={16} /> Following <span class="sub">everyone we follow</span></button>
      {#if buckets.length}
        <p class="menu-title">Only some followers</p>
        {#each listed.shown as b (b.id)}
          <label class="bucket"><input type="checkbox" checked={chosenBuckets.includes(b.id)} onchange={(e) => toggleBucket(b.id, e.currentTarget.checked)} /> <BucketLabel bucket={b} /></label>
        {/each}
        {#if listed.folded}<button class="more" onclick={() => (allBuckets = true)}>{listed.folded} more {listed.folded === 1 ? 'bucket' : 'buckets'}…</button>{/if}
      {/if}
      <button role="menuitemradio" aria-checked={(own || !inherit) && value.mode === 'server'} onclick={() => pick({ mode: 'server' })}><Icon name="globe" size={16} /> Everyone here <span class="sub">any account on this server</span></button>
      {#if onusedefault && defaultAudience}
        {@const isDefault = sameAudience(value, defaultAudience)}
        <!-- Only us is the built-in default: there's nothing to turn off -->
        <label class="bucket by-default"><input type="checkbox" checked={isDefault} disabled={isDefault && value.mode === 'private'} onchange={(e) => onusedefault(e.currentTarget.checked)} /> Use this audience by default</label>
      {/if}
    </div>
  {/if}
</span>

<style>
  .wrap { position: relative; display: inline-flex; }
  .chip { display: inline-flex; align-items: center; gap: 4px; font: inherit; font-size: var(--fs-xs); white-space: nowrap; padding: 3px 10px; border-radius: var(--r-full); cursor: pointer; color: var(--ink-3); background: none; border: 1px solid var(--line); }
  .chip.shared { color: var(--accent); border-color: color-mix(in srgb, var(--accent) 45%, transparent); background: color-mix(in srgb, var(--accent) 10%, transparent); }
  .chip:not(.own) { border-style: dashed; }
  .menu { position: absolute; right: 0; top: calc(100% + 4px); z-index: 20; width: 250px; display: grid; gap: 2px; padding: 6px; background: var(--surface); border: 1px solid var(--line); border-radius: 14px; box-shadow: var(--shadow-pop); }
  .menu-title { margin: 6px 8px 2px; font-size: var(--fs-xs); color: var(--ink-3); }
  .menu > button { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; text-align: left; border: 0; background: none; color: var(--ink); font: inherit; font-size: var(--fs-sm); padding: 8px; border-radius: 8px; cursor: pointer; }
  .menu > button:hover { background: var(--surface-2); }
  .menu > button[aria-checked='true'] { color: var(--accent); font-weight: 600; }
  .sub { width: 100%; padding-left: 24px; font-size: var(--fs-xs); color: var(--ink-3); font-weight: 400; }
  .bucket { display: flex; align-items: center; gap: 8px; padding: 6px 8px; font-size: var(--fs-sm); color: var(--ink); cursor: pointer; }
  .bucket input { accent-color: var(--accent); }
  .by-default { margin-top: 4px; padding-top: 10px; border-top: 1px solid var(--line); }
  .more { color: var(--ink-3) !important; font-size: var(--fs-xs) !important; }
</style>
