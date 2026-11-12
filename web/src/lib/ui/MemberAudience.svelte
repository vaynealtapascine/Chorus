<script lang="ts">
  // Who can see a member (F4.3): the whole profile as one choice, then each part, never wider than
  // the profile (D-101, D-126). Choices save as they are made; who hears when they front is the
  // member's notify policy (NOTIFICATIONS §2.4).
  import { buckets as bucketRows, fieldDefs, members, selfMember } from '../data';
  import { sync, type Projection } from '../sync/client';
  import { audienceLabel, audienceOf, effectiveAudience, isPrivate, ownAudience, sameAudience, type Audience } from '../profile-audience';
  import { KEY_LABELS, keysOf, label, ownOf, PART_GROUPS, summary, withAll, type PartGroup } from '../member-sharing';
  import AudienceMenuSheet from './AudienceMenuSheet.svelte';
  import BucketGlyph from './BucketGlyph.svelte';
  import Icon from './Icon.svelte';
  import Sheet from './Sheet.svelte';
  import FormCard from './kit/FormCard.svelte';
  import MenuRow from './kit/MenuRow.svelte';
  import MenuSheet from './kit/MenuSheet.svelte';
  import SectionLabel from './kit/SectionLabel.svelte';
  import ValueRow from './kit/ValueRow.svelte';

  let { projection, id }: { projection: Projection; id: string } = $props();
  const m = $derived(members(projection).find((x) => x.id === id));
  const person = $derived(!!selfMember(projection));
  const raw = $derived((projection.rows.member?.[id]?.fields ?? {}) as Record<string, unknown>);
  const buckets = $derived(bucketRows(projection));
  const names = $derived(new Map(buckets.map((b) => [b.id, b.name])));
  const live = $derived(new Set(buckets.map((b) => b.id)));
  const defs = $derived(fieldDefs(projection));
  const defaults = $derived(new Map(defs.map((d) => [d.id, d.default_visibility])));
  const profile = $derived(audienceOf(raw.visibility));
  const closed = $derived(isPrivate(profile, live));
  const parts = $derived(raw.field_visibility);
  const who = $derived(m?.display_name ?? m?.name ?? '');

  function set(fields: Record<string, unknown>) {
    const changed = Object.fromEntries(Object.entries(fields).filter(([k, v]) => JSON.stringify(raw[k] ?? null) !== JSON.stringify(v ?? null)));
    if (Object.keys(changed).length) sync.create('member.set', sync.accountScope, id, changed);
  }

  // the whole profile: Only us, each bucket, Friends, Everyone here (and a mode set elsewhere, so it still shows)
  const choices = $derived.by((): Audience[] => {
    const list: Audience[] = [{ mode: 'private' }, ...buckets.map((b): Audience => ({ mode: 'buckets', bucket_ids: [b.id] })), { mode: 'friends' }, { mode: 'server' }];
    if (!list.some((a) => sameAudience(a, profile)) && !closed) list.splice(list.length - 1, 0, profile);
    return list;
  });
  const choiceLabel = (a: Audience) => (a.mode === 'private' && person ? 'Only me' : audienceLabel(a, names));

  let choosing = $state<PartGroup | null>(null);
  let each = $state<PartGroup | null>(null);
  let key = $state<string | null>(null);
  let announcing = $state(false);
  const fieldIds = $derived(defs.map((d) => d.id));
  const nameOf = (k: string) => defs.find((d) => d.id === k)?.name ?? KEY_LABELS[k] ?? k;

  const own_ = (k: string) => ownAudience(parts, k);
  const effective_ = (k: string) => effectiveAudience(raw.visibility, parts, k, defaults.has(k) ? defaults.get(k) : undefined);
  const rowFor = (g: PartGroup) => {
    const keys = keysOf(g, fieldIds);
    const s = summary(raw.visibility, parts, keys, defaults, live);
    return { keys, s, text: closed ? 'Only us' : label(s, names), quiet: closed || !s || s.mode === 'private' };
  };

  // who hears when they front
  const announce = $derived.by(() => {
    const p = raw.notify_policy as { announce?: unknown } | undefined;
    return p?.announce === 'nobody' ? 'nobody' : typeof p?.announce === 'object' ? 'buckets' : 'everyone';
  });
  const announceIds = $derived.by(() => {
    const p = raw.notify_policy as { announce?: { buckets?: unknown } } | undefined;
    return Array.isArray(p?.announce?.buckets) ? p.announce.buckets.filter((x): x is string => typeof x === 'string') : [];
  });
  const announceText = $derived(announce === 'nobody' ? 'Nobody' : announce === 'everyone' ? 'Everyone' : announceIds.map((b) => names.get(b)).filter(Boolean).join(', ') || 'Nobody');
  function setAnnounce(v: unknown) {
    const p = (raw.notify_policy as Record<string, unknown> | undefined) ?? {};
    set({ notify_policy: { ...p, announce: v } });
  }
  function toggleAnnounce(bucket: string) {
    const ids = new Set(announceIds);
    if (!ids.delete(bucket)) ids.add(bucket);
    setAnnounce(ids.size ? { buckets: [...ids] } : 'nobody');
  }
</script>

{#if !m}
  <p class="muted">This member doesn't exist (yet). <a href="#/members">Back to members</a></p>
{:else}
  <section class="page">
    <header>
      <a class="back" href="#/members/{id}" aria-label="Back">‹</a>
      <div><h1>Who can see {who}</h1></div>
      <a class="done" href="#/members/{id}" aria-label="Done"><Icon name="check" size={22} /></a>
    </header>

    <SectionLabel label="The whole profile" />
    <FormCard label="The whole profile">
      {#each choices as a (JSON.stringify(a))}
        {@const on = sameAudience(a, profile)}
        <button class="choice" role="radio" aria-checked={on} onclick={() => set({ visibility: a })}>
          <span class="radio" class:on aria-hidden="true"></span>
          <span class="text" class:on>{choiceLabel(a)}</span>
          <span class="tile" aria-hidden="true">
            {#if a.mode === 'private'}<Icon name="lock" size={18} />
            {:else if a.mode === 'buckets'}{@const b = buckets.find((x) => x.id === a.bucket_ids[0])}{#if b}<BucketGlyph icon={b.icon} color={b.color} size={18} />{:else}<Icon name="shield" size={18} />{/if}
            {:else if a.mode === 'server'}<Icon name="globe" size={18} />
            {:else}<Icon name="users" size={18} />{/if}
          </span>
        </button>
      {/each}
    </FormCard>

    <SectionLabel label="Each part" />
    <FormCard label="Each part">
      {#each PART_GROUPS as g (g.id)}
        {@const r = rowFor(g)}
        <button class="part" onclick={() => (r.keys.length > 1 ? (each = g) : (choosing = g))}>
          <span class="title">{g.title}</span>
          <span class="pill" class:quiet={r.quiet}>{#if r.quiet}<Icon name="lock" size={13} />{/if}{r.text}</span>
          <Icon name="chevron-right" size={18} />
        </button>
      {/each}
      {#if !person}
        <button class="part" onclick={() => (announcing = true)}>
          <span class="title">Fronting status</span>
          <span class="pill" class:quiet={announce === 'nobody'}>{#if announce === 'nobody'}<Icon name="lock" size={13} />{/if}{announceText}</span>
          <Icon name="chevron-right" size={18} />
        </button>
      {/if}
    </FormCard>
  </section>

  {#if choosing}
    {@const g = choosing}
    {@const keys = keysOf(g, fieldIds)}
    {@const r = rowFor(g)}
    <AudienceMenuSheet title="Who can see {g.title.toLowerCase()}" value={r.s ?? { mode: 'private' }} own={!!ownOf(parts, keys)} inherit="Same as profile" {buckets}
      onpick={(a) => set({ field_visibility: withAll(parts, keys, a) })} onclose={() => (choosing = null)} />
  {/if}
  {#if each}
    {@const g = each}
    {@const keys = keysOf(g, fieldIds)}
    {@const r = rowFor(g)}
    <Sheet title={g.title} onclose={() => (each = null)}>
      <FormCard>
        <MenuRow label="Same for all" sub={r.s ? label(r.s, names) : 'Mixed'} onclick={() => (choosing = g)} />
        {#each keys as k (k)}
          <ValueRow label={nameOf(k)} value={audienceLabel(effective_(k), names)} onclick={() => (key = k)} />
        {/each}
      </FormCard>
      {#snippet footer()}<button class="go" onclick={() => (each = null)}>Done</button>{/snippet}
    </Sheet>
  {/if}
  {#if key}
    <AudienceMenuSheet title="Who can see {nameOf(key).toLowerCase()}" value={effective_(key)} own={!!own_(key)} inherit="Same as profile" {buckets} layer={1}
      onpick={(a) => set({ field_visibility: withAll(parts, [key!], a) })} onclose={() => (key = null)} />
  {/if}
  {#if announcing}
    <MenuSheet title="Who hears when {who} fronts" onclose={() => (announcing = false)}>
      <MenuRow label="Every follower" selected={announce === 'everyone'} onclick={() => setAnnounce('everyone')} />
      {#each buckets as b (b.id)}
        <MenuRow label={b.name} selected={announce === 'buckets' && announceIds.includes(b.id)} onclick={() => toggleAnnounce(b.id)}>
          {#snippet leading()}<BucketGlyph icon={b.icon} color={b.color} size={22} />{/snippet}
        </MenuRow>
      {/each}
      <MenuRow label="Nobody" selected={announce === 'nobody'} onclick={() => setAnnounce('nobody')} />
    </MenuSheet>
  {/if}
{/if}

<style>
  .page { display: grid; gap: var(--s-1); align-content: start; }
  header { display: flex; align-items: center; gap: var(--s-2); }
  header div { flex: 1; min-width: 0; }
  h1 { margin: 0; font-size: 20px; line-height: 26px; }
  .back, .done { display: grid; place-items: center; width: 40px; height: 40px; color: var(--ink); text-decoration: none; }
  .back { font-size: var(--fs-2xl); }
  .done { color: var(--accent); }
  .choice, .part { display: flex; align-items: center; gap: 14px; width: 100%; min-height: 56px; padding: var(--s-2) 14px; border: 0; background: none; color: var(--ink); font: inherit; text-align: left; cursor: pointer; }
  .radio { width: 22px; height: 22px; flex: none; border-radius: 50%; border: 2px solid var(--ink-3); box-sizing: border-box; }
  .radio.on { border: 6px solid var(--accent); }
  .text { flex: 1; font-size: var(--fs-base); }
  .text.on { font-weight: 600; }
  .tile { width: 32px; height: 32px; display: grid; place-items: center; border-radius: 8px; background: var(--surface-3, var(--surface)); color: var(--ink-2); flex: none; }
  .title { flex: 1; font-size: var(--fs-base); }
  .pill { display: inline-flex; align-items: center; gap: var(--s-1); border-radius: var(--r-full); background: var(--accent-soft); color: var(--accent); font-size: var(--fs-sm); font-weight: 500; padding: 5px 10px; white-space: nowrap; }
  .pill.quiet { background: var(--surface); color: var(--ink-2); }
  .part > :global(.icon) { color: var(--ink-3); }
  .go { flex: 1; min-height: 44px; border: 0; border-radius: var(--r-control); background: var(--accent); color: var(--on-accent); font: inherit; font-weight: 600; cursor: pointer; }
  .muted { color: var(--ink-3); }
</style>
