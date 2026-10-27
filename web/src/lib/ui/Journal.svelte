<script lang="ts">
  import { channels, groups, members, reactions, type MemberRow } from '../data';
  import { canWriteAs, postReaction, posts, type PostRow } from '../posts';
  import { sync, type Projection } from '../sync/client';
  import { bodyAuthors } from '../front-view';
  import PostCard from './PostCard.svelte';
  import PostComposer from './PostComposer.svelte';
  import PostReplies from './PostReplies.svelte';
  import JournalLists from './JournalLists.svelte';
  import JournalFeeds from './JournalFeeds.svelte';

  import Icon from './Icon.svelte';
  let { projection, dark }: { projection: Projection; dark: boolean } = $props();
  const people = $derived(new Map(members(projection).map((m) => [m.id, m] as [string, MemberRow])));
  const subjectNames = $derived(new Map([...members(projection).map((m) => [m.id, m.display_name ?? m.name] as [string, string]), ...groups(projection).map((g) => [g.id, g.name] as [string, string])]));
  const entries = $derived(posts(projection).filter((p) => !p.deleted));
  const reacts = $derived(reactions(projection));
  const channelById = $derived(new Map(channels(projection).map((c) => [c.id, c.name])));
  const pinned = $derived(Object.entries(projection.rows.message ?? {}).filter(([, row]) => row.exists && row.fields.deleted_at == null && typeof row.fields.pinned_at === 'number')
    .map(([id, row]) => ({ id, at: row.fields.pinned_at as number, text: String(row.fields.text ?? ''), channelId: String(row.fields.channel_id ?? '') })));
  // Body's changes on the Fronting layer (exact ID); a configured account reads canonical history
  const configured = $derived(!!projection.front_systems?.[sync.accountId]);
  const named = $derived.by(() => { projection.front_systems; return configured ? sync.frontHistory() : null; });
  const switchRows = $derived(configured
    ? (named?.effective.changes ?? []).map((c) => ({ id: c.id, occurred_at: c.occurred_at, retracted: false, fronting: c.entries.filter((e) => e.layer_id === 'front') }))
    : (projection.fronts[sync.accountId]?.switches ?? []).map((sw) => ({ id: sw.id, occurred_at: sw.occurred_at, retracted: sw.retracted, fronting: sw.resulting_front.filter((e) => e.level === 'front') })));
  const timeline = $derived([
    ...entries.map((post) => ({ kind: 'post' as const, id: post.id, at: post.occurred_at, post })),
    ...switchRows.map((sw) => ({ kind: 'switch' as const, id: sw.id, at: sw.occurred_at, sw })),
    ...pinned.map((pin) => ({ kind: 'pin' as const, id: pin.id, at: pin.at, pin })),
  ].sort((a, b) => b.at - a.at || b.id.localeCompare(a.id)));
  const active = $derived(members(projection).filter((m) => canWriteAs(projection, m.id, sync.accountId)));
  const frontSpeaker = $derived(bodyAuthors(projection, sync.accountId)[0]);
  let actingAs = $state('');
  const speaker = $derived(actingAs || frontSpeaker || active[0]?.id || null);
  let replyTo = $state<PostRow | null>(null);
  let section = $state<'timeline' | 'lists' | 'feeds'>('timeline');
  const name = (id: string) => people.get(id)?.display_name ?? people.get(id)?.name ?? 'Someone';
  const frontName = (e: { subject_id: string }) => subjectNames.get(e.subject_id) ?? 'Someone';
  function react(post: PostRow, emoji: string, add: boolean) {
    if (!speaker) return;
    sync.create(add ? 'post.react' : 'post.unreact', sync.accountScope, post.id, postReaction(post.id, emoji, speaker));
  }
</script>

<div class="journal-layout"><section class="journal">
  <header><h1 class="display">Journal</h1><label>React as <select bind:value={actingAs} aria-label="React as member"><option value="">Current front</option>{#each active as person (person.id)}<option value={person.id}>{person.display_name ?? person.name}</option>{/each}</select></label></header>
  <nav aria-label="Journal sections"><button class:on={section === 'timeline'} onclick={() => (section = 'timeline')}>Timeline</button><button class:on={section === 'lists'} onclick={() => (section = 'lists')}>Lists</button><button class:on={section === 'feeds'} onclick={() => (section = 'feeds')}>Feeds</button></nav>
  {#if section === 'lists'}<JournalLists {projection} {dark} />{:else if section === 'feeds'}<JournalFeeds {projection} {dark} />{:else}
  {#if replyTo}<p class="replying">Replying to {replyTo.authors.map(name).join(' & ')} <button onclick={() => (replyTo = null)}>Cancel</button></p>{/if}
  {#key replyTo?.id}<PostComposer {projection} initialAuthors={speaker ? [speaker] : []} replyTo={replyTo?.id} onsent={() => (replyTo = null)} />{/key}
  <div class="timeline">
    {#each timeline as item (`${item.kind}:${item.id}`)}
      {#if item.kind === 'post'}
        <PostCard {projection} post={item.post} {people} {dark} reactions={reacts.get(item.post.id) ?? new Map()} {speaker} compactEntry onreply={() => (replyTo = item.post)} onreact={(emoji, add) => react(item.post, emoji, add)} />
        <PostReplies {projection} speaker={speaker} postId={item.post.id} />
      {:else if item.kind === 'switch'}
        <div class="event"><span class="event-icon">◌</span><span>{item.sw.retracted ? 'Undone switch' : `Front: ${item.sw.fronting.map(frontName).join(' & ') || 'no one'}`}</span><time>{new Date(item.at).toLocaleString()}</time></div>
      {:else}
        <div class="event"><span class="event-icon">⌁</span><span>Pinned in #{channelById.get(item.pin.channelId) ?? 'channel'}: {item.pin.text.slice(0, 120)}</span><time>{new Date(item.at).toLocaleString()}</time></div>
      {/if}
    {:else}<p class="empty">No posts or switches yet. Write the first note above.</p>{/each}
  </div>
  {/if}
</section>
<aside class="journal-context" aria-label="Journal shortcuts">
  <a class="context-search" href="#/search"><Icon name="search" size={18} />Search posts and members</a>
  <section><h2>Your journal</h2><button onclick={() => (section = 'timeline')}><Icon name="journal" size={18} />System timeline</button><button onclick={() => (section = 'feeds')}><Icon name="layers" size={18} />Your feeds</button><button onclick={() => (section = 'lists')}><Icon name="members" size={18} />Member lists</button></section>
  <section><h2>Writing together</h2><p>Choose who's speaking before posting. The audience on each post controls who can read it.</p><a href="#/settings">Privacy and preferences</a></section>
</aside></div>

<style>
  .journal-layout { display: grid; grid-template-columns: minmax(0, 1fr) 280px; gap: 24px; }
  .journal-context { display: grid; align-content: start; gap: 16px; min-width: 0; }
  .journal-context section { display: grid; gap: 12px; background: var(--surface); border-radius: 16px; padding: 16px; }
  .journal-context h2 { font-size: var(--fs-sm); }
  .journal-context p { color: var(--ink-3); font-size: var(--fs-sm); line-height: 1.5; }
  .journal-context a { color: var(--accent); text-decoration: none; font-size: var(--fs-xs); }
  .journal-context button, .context-search { display: flex; align-items: center; gap: 10px; border: 0; background: none; color: var(--ink-2); padding: 8px 0; text-align: left; font-size: var(--fs-sm); cursor: pointer; }
  .journal-context .context-search { background: var(--surface-2); border-radius: 999px; padding: 12px 16px; color: var(--ink-3); }
  @media (max-width: 1279px) { .journal-layout { grid-template-columns: minmax(0, 1fr); } .journal-context { display: none; } }
  .journal { max-width: 760px; margin: 0 auto; display: grid; gap: var(--s-4); }
  header { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: var(--s-3); }
  h1, p { margin: 0; }
  nav { display: flex; gap: var(--s-2); border-bottom: 1px solid var(--line); }
  nav button { border: 0; background: none; padding: var(--s-2) var(--s-3); cursor: pointer; color: var(--ink); }
  nav button.on { border-bottom: 2px solid var(--accent); font-weight: 600; }
  header label { color: var(--ink-3); font-size: var(--fs-sm); }
  select { border: 1px solid var(--line); border-radius: var(--r-sm); background: var(--surface); color: var(--ink); padding: var(--s-2); }
  .timeline { display: grid; gap: 0; border-top: 1px solid var(--line); }
  .event { display: flex; gap: var(--s-3); align-items: center; padding: var(--s-3); border: 1px solid var(--line); border-radius: var(--r-md); background: var(--surface); color: var(--ink-2); }
  .event-icon { color: var(--accent); }
  time { margin-left: auto; color: var(--ink-3); font-size: var(--fs-xs); white-space: nowrap; }
  .replying { color: var(--ink-3); font-size: var(--fs-sm); }
  .replying button { color: var(--accent); border: 0; background: none; cursor: pointer; }
  .empty { color: var(--ink-3); }
</style>
