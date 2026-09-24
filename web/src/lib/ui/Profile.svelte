<script lang="ts">
  import { channels, fieldDefs, fieldValues, members, messages, reactions, type MemberRow } from '../data';
  import { canWriteAs, highlightedPostIds, memberPosts, postReaction, posts, type PostRow } from '../posts';
  import { frontDaily, windowStart } from '../insights';
  import { sync, type Projection } from '../sync/client';
  import PostCard from './PostCard.svelte';
  import PostComposer from './PostComposer.svelte';
  import AvatarImage from './AvatarImage.svelte';

  let { projection, id, dark }: { projection: Projection; id: string; dark: boolean } = $props();
  const people = $derived(new Map(members(projection).map((m) => [m.id, m] as [string, MemberRow])));
  const member = $derived(people.get(id));
  const own = $derived(canWriteAs(projection, id, sync.accountId));
  const writeAs = $derived(own ? id : members(projection).find((m) => canWriteAs(projection, m.id, sync.accountId))?.id ?? null);
  const all = $derived(posts(projection));
  const reacts = $derived(reactions(projection));
  let tab = $state<'posts' | 'replies' | 'highlights'>('posts');
  let replying = $state<PostRow | null>(null);
  const highlighted = $derived(highlightedPostIds(projection, id));
  const shown = $derived(tab === 'highlights' ? all.filter((post) => highlighted.has(post.id) && !post.deleted) : memberPosts(all, id, tab === 'replies'));
  const pinned = $derived(all.find((post) => post.id === member?.pinned_post_id && !post.deleted));
  const custom = $derived(fieldDefs(projection).map((def) => ({
    name: def.name, value: fieldValues(projection).get(id)?.get(def.id),
  })).filter((field) => field.value !== undefined && field.value !== null && field.value !== ''));
  const shownValue = (value: unknown) => Array.isArray(value) ? value.join(', ') : typeof value === 'boolean' ? (value ? 'Yes' : 'No') : String(value);
  const now = Date.now();
  const timeZone = $derived(typeof projection.rows.system?.[sync.accountId]?.fields.timezone === 'string' ? projection.rows.system[sync.accountId].fields.timezone as string : undefined);
  const intervals = $derived(projection.fronts[sync.accountId]?.intervals ?? []);
  const frontDays = $derived(frontDaily(intervals, windowStart(now, 28, timeZone), now, timeZone).filter((row) => row.subject_type === 'member' && row.subject_id === id && row.level === 'front'));
  const monthHours = $derived((frontDays.reduce((total, row) => total + row.seconds, 0) / 3600).toFixed(1));
  const weekStart = $derived(windowStart(now, 7, timeZone));
  const weekHours = $derived((frontDaily(intervals, weekStart, now, timeZone).filter((row) => row.subject_type === 'member' && row.subject_id === id && row.level === 'front').reduce((total, row) => total + row.seconds, 0) / 3600).toFixed(1));
  const lastFront = $derived(intervals.filter((row) => row.subject_type === 'member' && row.subject_id === id && row.level === 'front').reduce((latest, row) => Math.max(latest, row.end_at ?? now), 0));
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
    {#if member.banner_blob}<div class="banner" aria-label="Profile banner"><AvatarImage hash={member.banner_blob} glyph="" name="Profile banner" /></div>{/if}
    <header><span class="avatar"><AvatarImage hash={member.avatar_blob} glyph={member.sigils[0] ?? member.name[0]} name={member.display_name ?? member.name} /></span>
      <div><h1 class="display">{member.display_name ?? member.name}</h1>{#if member.pronouns}<p>{member.pronouns}</p>{/if}{#if own}<a href="#/members/{id}">Edit member</a>{/if}</div></header>
    {#if member.description}<p class="bio">{member.description}</p>{/if}
    {#if custom.length}<dl class="fields">{#each custom as field (field.name)}<div><dt>{field.name}</dt><dd>{shownValue(field.value)}</dd></div>{/each}</dl>{/if}
    <div class="stats" aria-label="Member stats">
      <span>Front: {weekHours} h this week · {monthHours} h in 28 days</span>
      <span>{lastFront ? `Last fronted ${new Date(lastFront).toLocaleDateString()}` : 'No front recorded'}</span>
      <span>{messageCount} messages</span>
    </div>
    {#if pinned}<section class="pinned"><h2>Pinned post</h2><PostCard post={pinned} {people} {dark} reactions={reacts.get(pinned.id) ?? new Map()} speaker={writeAs} onreply={() => (replying = pinned)} onreact={(emoji, add) => react(pinned, emoji, add)} />{#if own}<button onclick={() => pin(null)}>Unpin post</button>{/if}</section>{/if}
    {#if writeAs}
      {#if replying}<p>Replying to a post <button onclick={() => (replying = null)}>Cancel</button></p>{/if}
      {#key replying?.id}<PostComposer {projection} initialAuthors={[writeAs]} replyTo={replying?.id} onsent={() => (replying = null)} />{/key}
    {/if}
    <nav aria-label="Profile posts"><button class:on={tab === 'posts'} onclick={() => (tab = 'posts')}>Posts</button><button class:on={tab === 'replies'} onclick={() => (tab = 'replies')}>Replies</button><button class:on={tab === 'highlights'} onclick={() => (tab = 'highlights')}>Highlights</button></nav>
    <div class="list">{#each shown as post (post.id)}<PostCard {post} {people} {dark} reactions={reacts.get(post.id) ?? new Map()} speaker={writeAs} onreply={() => (replying = post)} onreact={(emoji, add) => react(post, emoji, add)} />{#if own}<div class="post-tools">{#if member.pinned_post_id !== post.id}<button onclick={() => pin(post.id)}>Pin to profile</button>{/if}<button onclick={() => highlight(post.id)}>{highlighted.has(post.id) ? 'Remove highlight' : 'Highlight post'}</button></div>{/if}{:else}<p class="empty">No {tab} yet.</p>{/each}</div>
  {:else}<p>This member is unavailable.</p>{/if}
</section>

<style>
  .profile { max-width: 760px; margin: 0 auto; display: grid; gap: var(--s-4); }
  .banner { height: clamp(130px, 24vw, 240px); overflow: hidden; border-radius: var(--r-lg); background: var(--surface-2); }
  .banner :global(img) { width: 100%; height: 100%; object-fit: cover; }
  header { display: flex; align-items: center; gap: var(--s-4); }
  .avatar { display: inline-grid; width: 72px; height: 72px; border-radius: 50%; overflow: hidden; flex: none; }
  h1, p { margin: 0; }
  header p, .bio, .empty { color: var(--ink-2); }
  .fields, .stats { display: flex; flex-wrap: wrap; gap: var(--s-2) var(--s-4); color: var(--ink-2); }
  .fields div { display: flex; gap: var(--s-1); }
  dt { font-weight: 600; } dd { margin: 0; }
  .stats { font-size: var(--fs-sm); }
  .pinned { display: grid; gap: var(--s-2); }
  .pinned h2 { margin: 0; font-size: var(--fs-md); }
  .pinned > button { justify-self: start; }
  .post-tools { display: flex; gap: var(--s-2); }
  a, button { color: var(--accent); }
  nav { display: flex; gap: var(--s-2); border-bottom: 1px solid var(--line); }
  nav button { border: 0; background: none; padding: var(--s-2) var(--s-3); cursor: pointer; }
  nav button.on { border-bottom: 2px solid var(--accent); font-weight: 600; }
  .list { display: grid; gap: var(--s-3); }
</style>
