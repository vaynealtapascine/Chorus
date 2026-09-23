<script lang="ts">
  import { members, reactions, type MemberRow } from '../data';
  import { canWriteAs, memberPosts, postReaction, posts, type PostRow } from '../posts';
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
  let tab = $state<'posts' | 'replies'>('posts');
  let replying = $state<PostRow | null>(null);
  const shown = $derived(memberPosts(all, id, tab === 'replies'));
  function react(post: PostRow, emoji: string, add: boolean) {
    if (writeAs) sync.create(add ? 'post.react' : 'post.unreact', sync.accountScope, post.id, postReaction(post.id, emoji, writeAs));
  }
</script>

<section class="profile">
  {#if member}
    <header><span class="avatar"><AvatarImage hash={member.avatar_blob} glyph={member.sigils[0] ?? member.name[0]} name={member.display_name ?? member.name} /></span>
      <div><h1 class="display">{member.display_name ?? member.name}</h1>{#if member.pronouns}<p>{member.pronouns}</p>{/if}<a href="#/members/{id}">Edit member</a></div></header>
    {#if member.description}<p class="bio">{member.description}</p>{/if}
    {#if writeAs}
      {#if replying}<p>Replying to a post <button onclick={() => (replying = null)}>Cancel</button></p>{/if}
      {#key replying?.id}<PostComposer {projection} initialAuthors={[writeAs]} replyTo={replying?.id} onsent={() => (replying = null)} />{/key}
    {/if}
    <nav aria-label="Profile posts"><button class:on={tab === 'posts'} onclick={() => (tab = 'posts')}>Posts</button><button class:on={tab === 'replies'} onclick={() => (tab = 'replies')}>Replies</button></nav>
    <div class="list">{#each shown as post (post.id)}<PostCard {post} {people} {dark} reactions={reacts.get(post.id) ?? new Map()} speaker={writeAs} onreply={() => (replying = post)} onreact={(emoji, add) => react(post, emoji, add)} />{:else}<p class="empty">No {tab} yet.</p>{/each}</div>
  {:else}<p>This member is unavailable.</p>{/if}
</section>

<style>
  .profile { max-width: 760px; margin: 0 auto; display: grid; gap: var(--s-4); }
  header { display: flex; align-items: center; gap: var(--s-4); }
  .avatar { display: inline-grid; width: 72px; height: 72px; border-radius: 50%; overflow: hidden; flex: none; }
  h1, p { margin: 0; }
  header p, .bio, .empty { color: var(--ink-2); }
  a, button { color: var(--accent); }
  nav { display: flex; gap: var(--s-2); border-bottom: 1px solid var(--line); }
  nav button { border: 0; background: none; padding: var(--s-2) var(--s-3); cursor: pointer; }
  nav button.on { border-bottom: 2px solid var(--accent); font-weight: 600; }
  .list { display: grid; gap: var(--s-3); }
</style>
