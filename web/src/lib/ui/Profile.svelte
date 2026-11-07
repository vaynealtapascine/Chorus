<script lang="ts">
  import { channels, fieldDefs, fieldValues, members, messages, reactions, type MemberRow } from '../data';
  import { canWriteAs, highlightedPostIds, memberPosts, postReaction, posts, type PostRow } from '../posts';
  import { frontDaily, namedFrontDays, windowStart } from '../insights';
  import { sync, type Projection } from '../sync/client';
  import PostCard from './PostCard.svelte';
  import PostComposer from './PostComposer.svelte';
  import { quoteView } from '../post-threads';
  import { savedPosts, socialIndex } from '../journal-social';
  import { router } from '../router.svelte';
  import ProfileRelationships from './ProfileRelationships.svelte';
  import ProfileHeader from './ProfileHeader.svelte';
  import { memberHandles } from '../handles';

  let { projection, id, dark }: { projection: Projection; id: string; dark: boolean } = $props();
  const people = $derived(new Map(members(projection).map((m) => [m.id, m] as [string, MemberRow])));
  const member = $derived(people.get(id));
  const handle = $derived(memberHandles(projection, sync.accountId).get(id)?.shown);
  const own = $derived(canWriteAs(projection, id, sync.accountId));
  const writeAs = $derived(own ? id : members(projection).find((m) => canWriteAs(projection, m.id, sync.accountId))?.id ?? null);
  const all = $derived(posts(projection));
  const social = $derived(socialIndex(all));
  const saved = $derived(savedPosts(projection.rows.pref ?? {}, sync.accountId));
  const postById = $derived(new Map(all.map((p) => [p.id, p])));
  const memberName = (m: string) => people.get(m)?.display_name ?? people.get(m)?.name ?? 'Someone';
  const reacts = $derived(reactions(projection));
  let tab = $state<'posts' | 'replies' | 'highlights' | 'relationships'>('posts');
  const highlighted = $derived(highlightedPostIds(projection, id));
  const shown = $derived(tab === 'highlights' ? all.filter((post) => highlighted.has(post.id) && !post.deleted) : memberPosts(all, id, tab === 'replies').filter((post) => !post.repost_of));
  const pinned = $derived(all.find((post) => post.id === member?.pinned_post_id && !post.deleted));
  const custom = $derived(fieldDefs(projection).map((def) => ({
    name: def.name, value: fieldValues(projection).get(id)?.get(def.id),
  })).filter((field) => field.value !== undefined && field.value !== null && field.value !== '' && !(Array.isArray(field.value) && !field.value.length)));
  const shownValue = (value: unknown) => Array.isArray(value) ? value.join(', ') : typeof value === 'boolean' ? (value ? 'Yes' : 'No') : String(value);
  const now = Date.now();
  const timeZone = $derived(typeof projection.rows.system?.[sync.accountId]?.fields.timezone === 'string' ? projection.rows.system[sync.accountId].fields.timezone as string : undefined);
  // Body's Fronting layer by its exact ID; a configured account reads its canonical history
  const configured = $derived(!!projection.front_systems?.[sync.accountId]);
  const named = $derived.by(() => { projection.front_systems; return configured ? sync.frontHistory() : null; });
  const intervals = $derived(configured
    ? (named?.effective.intervals ?? []).filter((i) => i.layer_id === 'front').map((i) => ({ ...i, level: i.layer_id }))
    : (projection.fronts[sync.accountId]?.intervals ?? []).filter((i) => i.level === 'front'));
  const mine = $derived(intervals.filter((row) => row.subject_type === 'member' && row.subject_id === id));
  const hours = (from: number) => ((configured
    ? namedFrontDays(named?.effective.intervals ?? [], sync.accountId, 'front', from, now, timeZone)
    : frontDaily(mine, from, now, timeZone)
  ).filter((row) => row.subject_type === 'member' && row.subject_id === id && row.level === 'front')
    .reduce((total, row) => total + row.seconds, 0) / 3600).toFixed(1);
  const monthHours = $derived(hours(windowStart(now, 28, timeZone)));
  const weekHours = $derived(hours(windowStart(now, 7, timeZone)));
  const lastFront = $derived(mine.reduce((latest, row) => Math.max(latest, row.end_at ?? now), 0));
  const messageCount = $derived(channels(projection).reduce((count, channel) => count + messages(projection, channel.id).filter((message) => message.authors.includes(id) && !message.deleted).length, 0));
  function pin(postId: string | null) {
    if (own) sync.create('member.set', sync.accountScope, id, { pinned_post_id: postId });
  }
  function highlight(postId: string) {
    if (!own) return;
    const payload = { profile_member_id: id, post_id: postId };
    sync.create(highlighted.has(postId) ? 'highlight.remove' : 'highlight.add', sync.accountScope, id, payload);
  }
  function react(post: PostRow, emoji: string, add: boolean) {
    if (writeAs) sync.create(add ? 'post.react' : 'post.unreact', sync.accountScope, post.id, postReaction(post.id, emoji, writeAs));
  }
</script>

<section class="profile">
  {#if member}
    <ProfileHeader banner={member.banner_blob} avatar={member.avatar_blob} glyph={member.sigils[0] ?? member.name[0]} name={member.display_name ?? member.name}
      {handle} color={member.color} subtitle={[member.pronouns, member.sigils.join(' ')].filter(Boolean).join(' · ')} {dark}>
      {#snippet actions()}{#if own}<a class="action" href="#/members/{id}">Edit profile</a>{/if}{/snippet}
      {#if member.description}<p class="bio">{member.description}</p>{/if}
      {#if custom.length}<dl class="fields">{#each custom as field (field.name)}<div><dt>{field.name}</dt><dd>{shownValue(field.value)}</dd></div>{/each}</dl>{/if}
    </ProfileHeader>
    <div class="stats" aria-label="Member stats">
      <span>Front: {weekHours} h this week · {monthHours} h in 28 days</span>
      <span>{lastFront ? `Last fronted ${new Date(lastFront).toLocaleDateString()}` : 'No front recorded'}</span>
      <span>{messageCount} messages</span>
    </div>
    {#if pinned}<section class="pinned"><h2>Pinned post</h2><PostCard {projection} post={pinned} quote={quoteView(pinned.quote_post_id, postById, memberName)} {people} {dark} reactions={reacts.get(pinned.id) ?? new Map()} speaker={writeAs} {social} {saved} replyCount={social.replies.get(pinned.id) ?? 0} onreply={() => router.go(`/journal/post/${encodeURIComponent(pinned.id)}`)} onreact={(emoji, add) => react(pinned, emoji, add)} />{#if own}<button onclick={() => pin(null)}>Unpin post</button>{/if}</section>{/if}
    {#if writeAs}
      <PostComposer {projection} initialAuthors={[writeAs]} />
    {/if}
    <nav aria-label="Profile sections"><button class:on={tab === 'posts'} onclick={() => (tab = 'posts')}>Posts</button><button class:on={tab === 'replies'} onclick={() => (tab = 'replies')}>Replies</button><button class:on={tab === 'highlights'} onclick={() => (tab = 'highlights')}>Highlights</button><button class:on={tab === 'relationships'} onclick={() => (tab = 'relationships')}>Relationships</button></nav>
    {#if tab === 'relationships'}<ProfileRelationships {projection} {id} {own} />
    {:else}<div class="list">{#each shown as post (post.id)}<PostCard {projection} {post} quote={quoteView(post.quote_post_id, postById, memberName)} {people} {dark} reactions={reacts.get(post.id) ?? new Map()} speaker={writeAs} {social} {saved} replyCount={social.replies.get(post.id) ?? 0} onreply={() => router.go(`/journal/post/${encodeURIComponent(post.id)}`)} onreact={(emoji, add) => react(post, emoji, add)} />{#if own}<div class="post-tools">{#if member.pinned_post_id !== post.id}<button onclick={() => pin(post.id)}>Pin to profile</button>{/if}<button onclick={() => highlight(post.id)}>{highlighted.has(post.id) ? 'Remove highlight' : 'Highlight post'}</button></div>{/if}{:else}<p class="empty">No {tab} yet.</p>{/each}</div>{/if}
  {:else}<p>This member is unavailable.</p>{/if}
</section>

<style>
  .profile { max-width: 760px; margin: 0 auto; display: grid; gap: var(--s-4); }
  p { margin: 0; }
  .bio, .empty { color: var(--ink-2); }
  .action { padding: 7px 16px; border: 1px solid var(--line); border-radius: var(--r-full); color: var(--ink); font-weight: 600; text-decoration: none; }
  .action:hover { background: var(--surface-2); }
  .fields, .stats { display: flex; flex-wrap: wrap; gap: var(--s-2) var(--s-4); color: var(--ink-2); }
  .fields div { display: flex; gap: var(--s-1); }
  dt { font-weight: 600; } dd { margin: 0; }
  .stats { font-size: var(--fs-sm); }
  .pinned { display: grid; gap: var(--s-2); }
  .pinned h2 { margin: 0; font-size: var(--fs-md); }
  .pinned > button { justify-self: start; }
  .post-tools { display: flex; gap: var(--s-2); }
  a, button { color: var(--accent); }
  nav { display: flex; flex-wrap: wrap; gap: var(--s-2); border-bottom: 1px solid var(--line); }
  nav button { border: 0; background: none; padding: var(--s-2) var(--s-3); cursor: pointer; }
  nav button.on { border-bottom: 2px solid var(--accent); font-weight: 600; }
  .list { display: grid; gap: var(--s-3); }
</style>
