<script lang="ts">
  import { core } from '../core';
  import type { FrontEntry, Member } from './types';
  import AvatarImage from './AvatarImage.svelte';

  let { members, front, since, dark }: { members: Member[]; front: FrontEntry[]; since: number; dark: boolean } =
    $props();

  const levelLabel: Record<string, string> = { front: '', cocon: 'co-con', present: 'present' };
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
  <div class="avatars">
    {#each shown as { f, m }, i (m.id)}
      {@const c = core.adaptColor(m.color, dark)}
      <span class="avatar" class:primary={f.primary} style="--ring: {c.ring}; z-index: {10 - i}" title={m.name}>
        <AvatarImage hash={m.avatarBlob} glyph={m.sigil ?? m.name[0]} name={m.name} />
      </span>
    {/each}
  </div>
  <div class="text">
    <p class="names display">{names || 'No one fronting'}</p>
    <p class="meta">
      {#each shown.filter((x) => x.f.level !== 'front') as { f, m } (m.id)}
        {@const c = core.adaptColor(m.color, dark)}
        <span class="badge"><span style="color: {c.name}">{m.name}</span> · {levelLabel[f.level]}</span>
      {/each}
      {#if shown.length}
        <span class="since">since {sinceLabel} · {duration(now - since)}</span>
      {:else}
        <span class="since">Tap someone below to switch in</span>
      {/if}
    </p>
  </div>
</section>

<style>
  .card {
    display: flex;
    align-items: center;
    gap: var(--s-4);
    padding: var(--s-5);
    background: var(--surface);
    border: 1px solid var(--line);
    border-radius: var(--r-lg);
  }
  .avatars {
    display: flex;
  }
  .avatar {
    width: 44px;
    height: 44px;
    display: grid;
    place-items: center;
    font-size: 20px;
    border-radius: 50%;
    background: var(--surface-2);
    box-shadow:
      0 0 0 2px var(--ring),
      0 0 0 4px var(--surface);
    margin-left: -10px;
  }
  .avatar:first-child {
    margin-left: 0;
  }
  .avatar.primary {
    width: 56px;
    height: 56px;
    font-size: 26px;
  }
  .names {
    margin: 0;
    font-size: var(--fs-xl);
  }
  .meta {
    margin: var(--s-1) 0 0;
    display: flex;
    flex-wrap: wrap;
    gap: var(--s-1) var(--s-3);
    color: var(--ink-2);
    font-size: var(--fs-sm);
  }
  .since {
    color: var(--ink-3);
  }
</style>
