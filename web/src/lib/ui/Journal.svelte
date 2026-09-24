<script lang="ts">
  import { channels, groups, members, reactions, type MemberRow } from '../data';
  import { canWriteAs, postReaction, posts, type PostRow } from '../posts';
  import { sync, type Projection } from '../sync/client';
  import PostCard from './PostCard.svelte';
  import PostComposer from './PostComposer.svelte';
  import PostReplies from './PostReplies.svelte';
  import JournalLists from './JournalLists.svelte';

  let { projection, dark }: { projection: Projection; dark: boolean } = $props();
  const people = $derived(new Map(members(projection).map((m) => [m.id, m] as [string, MemberRow])));
  const subjectNames = $derived(new Map([...members(projection).map((m) => [m.id, m.display_name ?? m.name] as [string, string]), ...groups(projection).map((g) => [g.id, g.name] as [string, string])]));
  const entries = $derived(posts(projection).filter((p) => !p.deleted));
  const reacts = $derived(reactions(projection));
  const channelById = $derived(new Map(channels(projection).map((c) => [c.id, c.name])));
  const pinned = $derived(Object.entries(projection.rows.message ?? {}).filter(([, row]) => row.exists && row.fields.deleted_at == null && typeof row.fields.pinned_at === 'number')
    .map(([id, row]) => ({ id, at: row.fields.pinned_at as number, text: String(row.fields.text ?? ''), channelId: String(row.fields.channel_id ?? '') })));
  const timeline = $derived([
    ...entries.map((post) => ({ kind: 'post' as const, id: post.id, at: post.occurred_at, post })),
    ...(projection.fronts[sync.accountId]?.switches ?? []).map((sw) => ({ kind: 'switch' as const, id: sw.id, at: sw.occurred_at, sw })),
    ...pinned.map((pin) => ({ kind: 'pin' as const, id: pin.id, at: pin.at, pin })),
  ].sort((a, b) => b.at - a.at || b.id.localeCompare(a.id)));
  const active = $derived(members(projection).filter((m) => canWriteAs(projection, m.id, sync.accountId)));
  const frontSpeaker = $derived((projection.fronts[sync.accountId]?.current ?? []).find((e) => e.subject_type === 'member' && e.level === 'front')?.subject_id);
  let actingAs = $state('');
  const speaker = $derived(actingAs || frontSpeaker || active[0]?.id || null);
  let replyTo = $state<PostRow | null>(null);
  let section = $state<'timeline' | 'lists'>('timeline');
  const name = (id: string) => people.get(id)?.display_name ?? people.get(id)?.name ?? 'Someone';
  const frontName = (e: { subject_id: string }) => subjectNames.get(e.subject_id) ?? 'Someone';
  function react(post: PostRow, emoji: string, add: boolean) {
    if (!speaker) return;
    sync.create(add ? 'post.react' : 'post.unreact', sync.accountScope, post.id, postReaction(post.id, emoji, speaker));
  }
</script>

<section class="journal">
  <header><h1 class="display">Journal</h1><label>React as <select bind:value={actingAs} aria-label="React as member"><option value="">Current front</option>{#each active as person (person.id)}<option value={person.id}>{person.display_name ?? person.name}</option>{/each}</select></label></header>
  <nav aria-label="Journal sections"><button class:on={section === 'timeline'} onclick={() => (section = 'timeline')}>Timeline</button><button class:on={section === 'lists'} onclick={() => (section = 'lists')}>Lists</button></nav>
  {#if section === 'lists'}<JournalLists {projection} {dark} />{:else}
  {#if replyTo}<p class="replying">Replying to {replyTo.authors.map(name).join(' & ')} <button onclick={() => (replyTo = null)}>Cancel</button></p>{/if}
  {#key replyTo?.id}<PostComposer {projection} initialAuthors={speaker ? [speaker] : []} replyTo={replyTo?.id} onsent={() => (replyTo = null)} />{/key}
  <div class="timeline">
    {#each timeline as item (`${item.kind}:${item.id}`)}
      {#if item.kind === 'post'}
        <PostCard post={item.post} {people} {dark} reactions={reacts.get(item.post.id) ?? new Map()} {speaker} compactEntry onreply={() => (replyTo = item.post)} onreact={(emoji, add) => react(item.post, emoji, add)} />
        <PostReplies postId={item.post.id} />
      {:else if item.kind === 'switch'}
        <div class="event"><span class="event-icon">◌</span><span>{item.sw.retracted ? 'Undone switch' : `Front: ${item.sw.resulting_front.filter((e) => e.level === 'front').map(frontName).join(' & ') || 'no one'}`}</span><time>{new Date(item.at).toLocaleString()}</time></div>
      {:else}
        <div class="event"><span class="event-icon">⌁</span><span>Pinned in #{channelById.get(item.pin.channelId) ?? 'channel'}: {item.pin.text.slice(0, 120)}</span><time>{new Date(item.at).toLocaleString()}</time></div>
      {/if}
    {:else}<p class="empty">No posts or switches yet. Write the first note above.</p>{/each}
  </div>
  {/if}
</section>

<style>
  .journal { max-width: 760px; margin: 0 auto; display: grid; gap: var(--s-4); }
  header { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: var(--s-3); }
  h1, p { margin: 0; }
  nav { display: flex; gap: var(--s-2); border-bottom: 1px solid var(--line); }
  nav button { border: 0; background: none; padding: var(--s-2) var(--s-3); cursor: pointer; color: var(--ink); }
  nav button.on { border-bottom: 2px solid var(--accent); font-weight: 600; }
  header label { color: var(--ink-3); font-size: var(--fs-sm); }
  select { border: 1px solid var(--line); border-radius: var(--r-sm); background: var(--surface); color: var(--ink); padding: var(--s-2); }
  .timeline { display: grid; gap: var(--s-3); }
  .event { display: flex; gap: var(--s-3); align-items: center; padding: var(--s-3); border: 1px solid var(--line); border-radius: var(--r-md); background: var(--surface); color: var(--ink-2); }
  .event-icon { color: var(--accent); }
  time { margin-left: auto; color: var(--ink-3); font-size: var(--fs-xs); white-space: nowrap; }
  .replying { color: var(--ink-3); font-size: var(--fs-sm); }
  .replying button { color: var(--accent); border: 0; background: none; cursor: pointer; }
  .empty { color: var(--ink-3); }
</style>
