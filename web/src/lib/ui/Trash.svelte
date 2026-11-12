<script lang="ts">
  // Trash (Figma F9.5): what this account deleted or made, newest first, "This week" then "Earlier",
  // each with Restore. No Empty trash: nothing is purged from here (D-053).
  import Icon from './Icon.svelte';
  import EmptyState from './kit/EmptyState.svelte';
  import PageHeader from './kit/PageHeader.svelte';
  import SectionLabel from './kit/SectionLabel.svelte';
  import UnderlineTabs from './kit/UnderlineTabs.svelte';
  import { channels, trashItems, type TrashItem, type TrashKind } from '../data';
  import { chatShown } from '../chat-visible';
  import { sync, type Projection } from '../sync/client';
  import { trashDetail, trashFilter, trashSections, type TrashTab } from '../trash';

  let { projection, channelId }: { projection: Projection; channelId?: string } = $props();
  let query = $state('');
  let searching = $state(false);
  let tab = $state<TrashTab>('all');
  // deleted messages of a private chat that isn't ours to see now stay out (D-097, D-098)
  const chatOk = $derived(chatShown(projection, sync.accountId));
  const byChannel = $derived(new Map(channels(projection, undefined, true).map((c) => [c.id, c])));
  const all = $derived(trashItems(projection, sync.accountId).filter((item) =>
    (item.kind !== 'message' || !item.channelId || chatOk(byChannel.get(item.channelId))) &&
    (!channelId || (item.kind === 'message' && item.channelId === channelId))));
  const shown = $derived(trashFilter(all, tab, query));
  const now = $derived(shown.length || all.length ? Date.now() : 0);
  const sections = $derived(trashSections(shown, now));
  const restoreOp: Record<TrashKind, string> = {
    message: 'message.restore', post: 'post.restore', member: 'member.restore',
    group: 'group.restore', channel: 'channel.restore',
  };
  const tile: Record<TrashKind, { icon: string; tone: string }> = {
    message: { icon: 'message', tone: 'var(--trash-blue, #6f93e0)' }, channel: { icon: 'hash', tone: 'var(--trash-blue, #6f93e0)' },
    post: { icon: 'book', tone: 'var(--danger)' }, member: { icon: 'user', tone: 'var(--trash-violet, #b79ceb)' }, group: { icon: 'users', tone: 'var(--trash-violet, #b79ceb)' },
  };
  const restore = (item: TrashItem) => sync.create(restoreOp[item.kind], item.scope, item.id, {});
</script>

<section class="trash-page">
  {#if channelId}<a class="back" href="#/chat/{channelId}"><Icon name="back" size={18} />Back</a>{/if}
  <PageHeader title="Trash" sub={all.length ? `${all.length} ${all.length === 1 ? 'item' : 'items'}` : null}>
    {#snippet action()}
      <button class="icon" aria-label={searching ? 'Close search' : 'Search Trash'} onclick={() => { searching = !searching; if (!searching) query = ''; }}>
        <Icon name={searching ? 'close' : 'search'} size={20} />
      </button>
    {/snippet}
  </PageHeader>
  {#if !channelId}
    <UnderlineTabs label="Item type" bind:value={tab} tabs={[{ id: 'all', label: 'All' }, { id: 'messages', label: 'Messages' }, { id: 'posts', label: 'Posts' }, { id: 'members', label: 'Members' }]} />
  {/if}
  {#if searching}<input type="search" bind:value={query} placeholder="Search Trash" aria-label="Search Trash" />{/if}
  {#each sections as section (section.label)}
    <div>
      <SectionLabel label={section.label} />
      {#each section.items as item (`${item.kind}:${item.id}`)}
        <div class="row">
          <span class="tile" style="--tone: {tile[item.kind].tone}"><Icon name={tile[item.kind].icon} size={20} /></span>
          <div class="text"><strong>{item.title}</strong><span>{trashDetail(item, now)}</span></div>
          <button class="restore" disabled={!item.scope} onclick={() => restore(item)}><Icon name="history" size={16} />Restore</button>
        </div>
      {/each}
    </div>
  {:else}
    <EmptyState icon="delete" label={all.length ? 'Nothing here' : 'Trash is empty'} />
  {/each}
</section>

<style>
  .trash-page { display: grid; gap: var(--s-2); max-width: 720px; align-content: start; }
  .back { display: inline-flex; align-items: center; gap: var(--s-1); color: var(--accent); text-decoration: none; font-size: 14px; }
  .icon { display: grid; place-items: center; width: 44px; height: 44px; border: 0; border-radius: 50%; background: none; color: var(--ink); cursor: pointer; }
  input { font: inherit; color: var(--ink); background: var(--surface-2); border: 1px solid var(--line); border-radius: var(--r-row); padding: 10px 14px; }
  .row { display: flex; align-items: center; gap: var(--s-3); min-height: 60px; padding: 10px var(--s-1); }
  .tile { display: grid; place-items: center; width: 40px; height: 40px; border-radius: var(--r-row); flex: none; color: var(--tone); background: color-mix(in srgb, var(--tone) 16%, transparent); }
  .text { flex: 1; min-width: 0; display: grid; }
  .text strong { font-size: var(--fs-md); font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .text span { color: var(--ink-3); font-size: var(--fs-xs); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .restore { display: inline-flex; align-items: center; gap: 6px; min-height: 36px; padding: 0 14px; border: 0; border-radius: var(--r-full); background: var(--surface-2); color: var(--ink); font: inherit; font-size: 14px; font-weight: 500; cursor: pointer; }
  .restore:disabled { color: var(--ink-3); cursor: default; }
</style>
