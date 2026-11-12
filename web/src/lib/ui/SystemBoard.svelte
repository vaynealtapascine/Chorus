<script lang="ts">
  // D-135 / F6.9: our system's own private posts, with Posts and Polls tabs. A view over posts this
  // device already holds, so it reads offline. "+" writes a private post (the Journal composer).
  import { members, reactions, type MemberRow } from '../data';
  import { posts, postReaction, type PostRow } from '../posts';
  import { pollOf } from '../polls';
  import { boardPosts } from '../board';
  import { savedPosts, socialIndex } from '../journal-social';
  import { quoteView } from '../post-threads';
  import { remotePost } from '../remote-posts.svelte';
  import { bodyAuthors } from '../front-view';
  import { sync, type Projection } from '../sync/client';
  import { router } from '../router.svelte';
  import PostCard from './PostCard.svelte';
  import Icon from './Icon.svelte';
  import EmptyState from './kit/EmptyState.svelte';
  import UnderlineTabs from './kit/UnderlineTabs.svelte';

  let { projection, dark }: { projection: Projection; dark: boolean } = $props();
  const people = $derived(new Map(members(projection).map((m) => [m.id, m] as [string, MemberRow])));
  const all = $derived(posts(projection));
  const postById = $derived(new Map(all.map((p) => [p.id, p])));
  const social = $derived(socialIndex(all));
  const saved = $derived(savedPosts(projection.rows.pref ?? {}, sync.accountId));
  const reacts = $derived(reactions(projection));
  const speaker = $derived(bodyAuthors(projection, sync.accountId)[0] || members(projection)[0]?.id || null);
  const systemName = $derived(String(projection.rows.system?.[sync.accountId]?.fields.name ?? ''));
  let tab = $state<'posts' | 'polls'>('posts');
  const shown = $derived(boardPosts(all, (id) => !!pollOf(projection, id), tab));
  const name = (id: string) => people.get(id)?.display_name ?? people.get(id)?.name ?? 'Someone';
  const quoteOf = (post: PostRow) => quoteView(post.quote_post_id, postById, name, post.quote_post_id && !postById.has(post.quote_post_id) ? remotePost(post.quote_post_id) : null);
  function react(post: PostRow, emoji: string, add: boolean, as = speaker) {
    if (!as) return;
    sync.create(add ? 'post.react' : 'post.unreact', sync.accountScope, post.id, postReaction(post.id, emoji, as));
  }
</script>

<section class="board">
  <header>
    <a class="back" href="#/journal" aria-label="Back"><Icon name="chevron" size={20} /></a>
    <div><h1>System board</h1><p>{systemName ? `${systemName} · ` : ''}internal</p></div>
    <a class="new" href="#/journal" aria-label="New post" title="New post"><Icon name="plus" size={22} /></a>
  </header>
  <UnderlineTabs label="System board" bind:value={tab} tabs={[{ id: 'posts', label: 'Posts' }, { id: 'polls', label: 'Polls' }]} />
  <div class="list">
    {#each shown as post (post.id)}
      <PostCard {projection} {post} quote={quoteOf(post)} {people} {dark} reactions={reacts.get(post.id) ?? new Map()} {speaker} compactEntry {social} {saved}
        replyCount={social.replies.get(post.id) ?? 0} onreply={() => router.go(`/journal/post/${encodeURIComponent(post.id)}`)}
        onreact={(emoji, add, as) => react(post, emoji, add, as)} />
    {:else}
      <EmptyState icon={tab === 'polls' ? 'poll' : 'journal'} label={tab === 'polls' ? 'No polls yet' : 'Nothing yet'} action="New post" href="#/journal" />
    {/each}
  </div>
</section>

<style>
  .board { width: 100%; max-width: 760px; margin: 0 auto; display: grid; gap: var(--s-2); align-content: start; min-width: 0; }
  header { display: flex; align-items: center; gap: var(--s-2); }
  header div { flex: 1; min-width: 0; }
  h1 { margin: 0; font-size: 19px; line-height: 24px; font-weight: 600; }
  p { margin: 0; color: var(--ink-3); font-size: var(--fs-sm); }
  .back, .new { display: grid; place-items: center; width: 44px; height: 44px; border-radius: 50%; color: var(--ink); text-decoration: none; }
  .back { transform: rotate(180deg); }
  .new { color: var(--accent); }
  .back:hover, .new:hover { background: var(--surface-2); }
  .list { display: grid; }
</style>
