<script lang="ts">
  import { core } from '../core';
  import type { FrontEntry, Member } from './types';
  import AvatarImage from './AvatarImage.svelte';

  let { members, front, since, dark }: { members: Member[]; front: FrontEntry[]; since: number; dark: boolean } =
    $props();

  let now = $state(Date.now());
  $effect(() => {
    void since; // a new switch restarts the clock, so durations never go negative
    now = Date.now();
    const t = setInterval(() => (now = Date.now()), 30_000);
    return () => clearInterval(t);
  });

  const byId = $derived(new Map(members.map((m) => [m.id, m])));
  const shown = $derived(front.map((f) => ({ f, m: byId.get(f.id)! })).filter((x) => x.m));
  const names = $derived(
    shown
      .filter((x) => x.f.level === 'front')
      .map((x) => x.m.name)
      .join(' & '),
  );

  function duration(ms: number): string {
    const m = Math.floor(Math.max(0, ms) / 60_000);
    if (m < 1) return 'just now';
    return m < 60 ? `${m}m` : `${Math.floor(m / 60)}h ${m % 60}m`;
  }
  const sinceLabel = $derived(new Date(since).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }));
</script>

<section class="card" aria-label="Who's here">
  <div class="front-heading"><span class="front-dot"></span><strong>Current front</strong><span>{shown.length} here</span></div>
  <div class="nearby layer">
    <div class="conscious layer">
      <div class="front layer">
        <div class="layer-heading"><strong>Fronting</strong><span>{names ? `since ${sinceLabel}` : 'No one fronting'}</span></div>
        {#each shown.filter((x) => x.f.level === 'front') as { f, m } (m.id)}
          {@const c = core.adaptColor(m.color, dark)}
          <div class="front-person" style="--ring: {c.ring}; --avatar-ink: {c.avatar_ink}; --name: {c.name}">
            <span class="avatar"><AvatarImage hash={m.avatarBlob} glyph={m.sigil ?? m.name[0]} name={m.name} /></span>
            <span class="person-name">{m.name}{#if f.primary}<span class="primary">Primary</span>{/if}</span>
            <span class="duration">{duration(now - since)}</span>
          </div>
        {:else}<p class="empty">Tap to choose who's here.</p>{/each}
      </div>
      <div class="layer-footer"><span>Co-conscious</span><div class="chips">{#each shown.filter((x) => x.f.level === 'cocon') as { m } (m.id)}<span class="member-chip">{m.name}</span>{:else}<span class="none">No one</span>{/each}</div></div>
    </div>
    <div class="layer-footer"><span>Present</span><div class="chips">{#each shown.filter((x) => x.f.level === 'present') as { m } (m.id)}<span class="member-chip">{m.name}</span>{:else}<span class="none">No one</span>{/each}</div></div>
  </div>
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
