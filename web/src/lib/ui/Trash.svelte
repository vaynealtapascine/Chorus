<script lang="ts">
  import { trashItems, type TrashKind } from '../data';
  import { sync, type Projection } from '../sync/client';

  let { projection, channelId }: { projection: Projection; channelId?: string } = $props();
  let query = $state('');
  let kind = $state<TrashKind | 'all'>('all');
  const all = $derived(trashItems(projection, sync.accountId));
  const shown = $derived(all.filter((item) =>
    (!channelId || (item.kind === 'message' && item.channelId === channelId)) &&
    (kind === 'all' || item.kind === kind) &&
    (!query.trim() || `${item.label} ${item.detail}`.toLowerCase().includes(query.trim().toLowerCase())),
  ));
  const kinds: { id: TrashKind | 'all'; label: string }[] = [
    { id: 'all', label: 'All' }, { id: 'message', label: 'Messages' },
    { id: 'post', label: 'Posts & entries' }, { id: 'member', label: 'Members' },
    { id: 'group', label: 'Groups' }, { id: 'channel', label: 'Channels' },
  ];
  const restoreOp: Record<TrashKind, string> = {
    message: 'message.restore', post: 'post.restore', member: 'member.restore',
    group: 'group.restore', channel: 'channel.restore',
  };
  const date = (ms: number) => new Date(ms).toLocaleString();
</script>

<section class="trash-page">
  <a class="back" href={channelId ? `#/chat/${channelId}` : '#/'}>‹ Back</a>
  <header>
    <h1 class="display">Trash</h1>
    <p>Deleted items stay here until restored.</p>
  </header>
  <input type="search" bind:value={query} placeholder="Search deleted items" aria-label="Search Trash" />
  {#if !channelId}
    <div class="filters" role="group" aria-label="Item type">
      {#each kinds as option (option.id)}
        <button class:on={kind === option.id} onclick={() => (kind = option.id)}>{option.label}</button>
      {/each}
    </div>
  {/if}
  <div class="items">
    {#each shown as item (`${item.kind}:${item.id}`)}
      <article>
        <div>
          <span class="kind">{item.label}</span>
          <p>{item.detail}</p>
          <time>{date(item.deletedAt)}</time>
        </div>
        <button disabled={!item.scope} onclick={() => sync.create(restoreOp[item.kind], item.scope, item.id, {})}>Restore</button>
      </article>
    {:else}
      <p class="empty">{query || kind !== 'all' ? 'No deleted items match.' : 'Trash is empty.'}</p>
    {/each}
  </div>
</section>

<style>
  .trash-page { display: grid; gap: var(--s-4); }
  .back { color: var(--accent); text-decoration: none; font-size: var(--fs-sm); }
  header h1 { margin: 0; }
  header p { margin: var(--s-1) 0 0; color: var(--ink-3); font-size: var(--fs-sm); }
  input { width: 100%; padding: var(--s-2) var(--s-3); border: 1px solid var(--line); border-radius: var(--r-md); background: var(--surface); color: var(--ink); font: inherit; }
  .filters { display: flex; gap: var(--s-1); overflow-x: auto; }
  .filters button { flex: none; background: none; border: 0; border-radius: var(--r-full); padding: var(--s-1) var(--s-3); color: var(--ink-2); cursor: pointer; }
  .filters button.on { color: var(--accent); background: var(--accent-soft); }
  .items { display: grid; gap: var(--s-2); }
  article { display: flex; align-items: center; justify-content: space-between; gap: var(--s-3); padding: var(--s-3); background: var(--surface); border: 1px solid var(--line); border-radius: var(--r-md); }
  article div { min-width: 0; }
  .kind, time { color: var(--ink-3); font-size: var(--fs-xs); }
  article p { margin: var(--s-1) 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  article button { border: 0; background: var(--accent-soft); color: var(--accent); border-radius: var(--r-full); padding: var(--s-2) var(--s-3); cursor: pointer; }
  article button:disabled { opacity: .5; cursor: default; }
  .empty { padding: var(--s-5); color: var(--ink-3); text-align: center; }
</style>
