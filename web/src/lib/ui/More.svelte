<script lang="ts">
  // F10.1 More, as Android's MoreScreen: "Go to" what is not on the bar, then "Settings", each row
  // with its state as a line under it where there is one (counts, the current look).
  import { onMount } from 'svelte';
  import { groups, members, selfMember } from '../data';
  import { appearance } from '../appearance.svelte';
  import { friends } from '../friends-state.svelte';
  import { sync, type Projection } from '../sync/client';
  import { cap } from '../words';
  import Icon from './Icon.svelte';
  import InstallCard from './kit/InstallCard.svelte';

  let { projection }: { projection: Projection } = $props();
  const self = $derived(selfMember(projection));
  const person = $derived(!!self);
  onMount(() => { void friends.load(); });

  const own = $derived(members(projection).filter((m) => !m.deleted && !m.archived && !m.is_self));
  const count = (n: number, word: string) => (n > 0 ? `${n} ${word}${n === 1 ? '' : 's'}` : '');
  const membersLine = $derived([count(own.length, 'member'), count(groups(projection).length, 'group')].filter(Boolean).join(' · '));
  const issues = $derived(sync.syncIssues().length);
  const systemName = $derived(String(projection.rows.system?.[sync.accountId]?.fields.name ?? '').trim());
  const handle = $derived(String(projection.rows.account?.[sync.accountId]?.fields.handle ?? '').trim());
  const look = $derived([appearance.mode === 'system' ? 'Device' : cap(appearance.mode), appearance.accent, appearance.density].join(' · '));

  interface Row { href: string; title: string; icon: string; line?: string; badge?: number; danger?: boolean }
  const goTo = $derived<Row[]>([
    { href: '#/', title: 'Home', icon: 'home' },
    ...(person ? [] : [{ href: '#/members', title: 'Members', icon: 'members', line: membersLine }]),
    { href: '#/people', title: 'Friends', icon: 'friends', line: friends.loaded ? `${friends.following.filter((f) => f.status === 'active').length} following` : '', badge: friends.requests.length },
    ...(person ? [] : [{ href: '#/insights', title: 'Fronting insights', icon: 'layers' }]),
    { href: '#/history', title: 'Front history', icon: 'history' },
  ]);
  const settings = $derived<Row[]>([
    ...(issues ? [{ href: '#/settings/issues', title: `Sync issues · ${issues}`, icon: 'target', danger: true }] : []),
    { href: '#/settings/appearance', title: 'Appearance', icon: 'sliders', line: look },
    { href: '#/settings/notifications', title: 'Notification settings', icon: 'bell' },
    ...(person ? [] : [{ href: '#/settings/sharing', title: 'Privacy and sharing', icon: 'shield' }]),
    { href: '#/settings/data', title: 'Your data and devices', icon: 'copy' },
    { href: '#/settings/account', title: person ? 'Account' : 'System settings', icon: 'user',
      line: [!person && systemName, handle && `@${handle}`].filter(Boolean).join(' · ') },
  ]);
</script>

<section class="more">
  <h1 class="display">More</h1>
  <h2 class="label">Go to</h2>
  {#each goTo as r (r.href)}{@render row(r)}{/each}
  <h2 class="label">Settings</h2>
  {#each settings as r (r.href)}{@render row(r)}{/each}
  <InstallCard />
</section>

{#snippet row(r: Row)}
  <a href={r.href} class:danger={r.danger}>
    <Icon name={r.icon} size={22} />
    <span class="text"><strong>{r.title}</strong>{#if r.line}<span>{r.line}</span>{/if}</span>
    {#if r.badge}<span class="badge">{r.badge}</span>{/if}
    <Icon name="chevron" size={16} />
  </a>
{/snippet}

<style>
  .more { display: grid; max-width: 720px; margin: auto; }
  h1 { font-size: var(--fs-xl); margin: 0 0 var(--s-2); }
  .label { margin: 0; padding: var(--s-4) var(--s-1) 6px; color: var(--accent); font-size: var(--fs-sm); font-weight: 600; line-height: 18px; }
  a { display: flex; gap: var(--s-4); align-items: center; min-height: 62px; padding: var(--s-2) var(--s-1); color: var(--ink); text-decoration: none; }
  a:hover { background: var(--surface-2); }
  a > :global(.icon) { color: var(--ink-2); }
  .text { display: grid; flex: 1; min-width: 0; }
  .text strong { font-size: var(--fs-base); font-weight: 400; }
  .text span { font-size: var(--fs-sm); color: var(--ink-3); }
  .badge { min-width: 22px; padding: 1px 6px; border-radius: 11px; background: var(--accent); color: var(--on-accent); font-size: var(--fs-xs); font-weight: 600; text-align: center; }
  .danger, .danger .text strong, .danger > :global(.icon) { color: var(--danger); }
</style>
