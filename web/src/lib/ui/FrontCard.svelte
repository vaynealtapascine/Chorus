<script lang="ts">
  import { core } from '../core';
  import type { FrontEntry, Member } from './types';
  import AvatarImage from './AvatarImage.svelte';

  /** `layers` inner to outer; the innermost lists people, outer layers nest around it (D-080). */
  let { members, front, since, dark, layers = [
    { id: 'front', name: 'Fronting' }, { id: 'cocon', name: 'Co-conscious' }, { id: 'present', name: 'Present' },
  ], title = 'Current front' }: {
    members: Member[]; front: FrontEntry[]; since: number | null; dark: boolean;
    layers?: { id: string; name: string }[]; title?: string;
  } = $props();

  let now = $state(Date.now());
  $effect(() => {
    void since; // a new switch restarts the clock, so durations never go negative
    now = Date.now();
    const t = setInterval(() => (now = Date.now()), 30_000);
    return () => clearInterval(t);
  });

  const byId = $derived(new Map(members.map((m) => [m.id, m])));
  const shown = $derived(front.map((f) => ({ f, m: byId.get(f.id)! })).filter((x) => x.m));
  const inner = $derived(layers[0]?.id ?? 'front');
  const names = $derived(
    shown
      .filter((x) => x.f.level === inner)
      .map((x) => x.m.name)
      .join(' & '),
  );
  // entries in a layer this card doesn't list (e.g. one deleted since) still show, outermost
  const unlisted = $derived(shown.filter((x) => !layers.some((l) => l.id === x.f.level)));

  function duration(ms: number): string {
    const m = Math.floor(Math.max(0, ms) / 60_000);
    if (m < 1) return 'just now';
    return m < 60 ? `${m}m` : `${Math.floor(m / 60)}h ${m % 60}m`;
  }
  const sinceLabel = $derived(since == null ? '' : new Date(since).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }));
</script>

{#snippet ring(i: number)}
  {#if i === 0}
    <div class="front layer">
      <div class="layer-heading"><strong>{layers[0]?.name ?? 'Fronting'}</strong><span>{names ? (sinceLabel ? `since ${sinceLabel}` : '') : `No one in ${layers[0]?.name ?? 'front'}`}</span></div>
      {#each shown.filter((x) => x.f.level === inner) as { f, m } (m.id)}
        {@const c = core.adaptColor(m.color, dark)}
        <div class="front-person" style="--ring: {c.ring}; --avatar-ink: {c.avatar_ink}; --name: {c.name}">
          <span class="avatar"><AvatarImage hash={m.avatarBlob} glyph={m.sigil ?? m.name[0]} name={m.name} /></span>
          <span class="person-name">{m.name}{#if f.primary}<span class="primary">Primary</span>{/if}</span>
          {#if since != null}<span class="duration">{duration(now - since)}</span>{/if}
        </div>
      {:else}<p class="empty">Tap to choose who's here.</p>{/each}
    </div>
  {:else}
    <div class="layer" class:conscious={i === 1}>
      {@render ring(i - 1)}
      <div class="layer-footer"><span>{layers[i].name}</span><div class="chips">{#each shown.filter((x) => x.f.level === layers[i].id) as { m } (m.id)}<span class="member-chip">{m.name}</span>{:else}<span class="none">No one</span>{/each}</div></div>
    </div>
  {/if}
{/snippet}

<section class="card" aria-label="Who's here">
  <div class="front-heading"><span class="front-dot"></span><strong>{title}</strong><span>{shown.length} here</span></div>
  {@render ring(Math.max(0, layers.length - 1))}
  {#if unlisted.length}
    <div class="layer-footer"><span>Other</span><div class="chips">{#each unlisted as { m } (m.id)}<span class="member-chip">{m.name}</span>{/each}</div></div>
  {/if}
</section>

<style>
  .card { background: var(--surface); padding: 16px; border-radius: var(--r-lg); display: grid; gap: 16px; }
  .front-heading { display: flex; align-items: center; gap: 8px; }
  .front-heading > span:last-child { color: var(--ink-3); margin-left: auto; font-size: var(--fs-xs); }
  .front-dot { width: 14px; height: 14px; border: 2px solid var(--accent); border-radius: 50%; box-shadow: inset 0 0 0 2px var(--surface); background: var(--accent); }
  .layer { display: grid; gap: 10px; padding: 12px; border-radius: 20px; border: 1px dashed var(--line); }
  .conscious { border-color: color-mix(in srgb, var(--accent) 65%, transparent); background: color-mix(in srgb, var(--accent) 3%, transparent); }
  .front { border-style: solid; border-color: var(--accent); background: var(--accent-soft); border-radius: var(--r-md); padding: 10px; }
  .layer-heading { display: flex; gap: 8px; align-items: baseline; padding: 0 2px 2px; color: var(--accent); font-size: var(--fs-xs); }
  .layer-heading > span { color: var(--ink-3); }
  .front-person { display: flex; align-items: center; gap: 10px; background: var(--surface); padding: 10px; border-radius: 12px; min-width: 0; }
  .avatar { display: grid; place-items: center; width: 42px; height: 42px; flex: none; background: var(--ring); color: var(--avatar-ink); border: 2px solid var(--ring); border-radius: 50%; overflow: hidden; font-weight: 700; }
  .person-name { display: grid; gap: 2px; font-size: var(--fs-md); font-weight: 600; overflow-wrap: anywhere; }
  .primary { color: var(--ink-3); font-size: var(--fs-xs); font-weight: 400; }
  .duration { margin-left: auto; color: var(--ink-3); font-size: var(--fs-xs); white-space: nowrap; font-variant-numeric: tabular-nums; }
  .layer-footer { display: flex; flex-wrap: wrap; gap: 8px; justify-content: space-between; align-items: center; color: var(--ink-2); font-size: var(--fs-xs); }
  .chips { display: flex; flex-wrap: wrap; justify-content: end; gap: 4px; }
  .member-chip { padding: 4px 10px; border-radius: 999px; background: var(--surface-2); color: var(--ink); }
  .none, .empty { color: var(--ink-3); font-size: var(--fs-xs); }
  .empty { margin: 4px 0; }
  @media (max-width: 420px) { .card { padding: 12px; } .layer { padding: 10px; } .front { padding: 8px; } }
</style>
