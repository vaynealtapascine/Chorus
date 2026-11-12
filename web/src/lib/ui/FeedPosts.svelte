<script lang="ts">
  // A saved feed's posts as a Journal tab (F6.8): its query run over what this device holds.
  import { core } from '../core';
  import { members, reactions, type MemberRow } from '../data';
  import { feedCandidates, feedContext, filterFeed, type FeedRow } from '../feeds';
  import { canWriteAs, postReaction, posts, type PostRow } from '../posts';
  import { sync, type Projection } from '../sync/client';
  import { quoteView } from '../post-threads';
  import { savedPosts, socialIndex } from '../journal-social';
  import { router } from '../router.svelte';
  import PostCard from './PostCard.svelte';
  import EmptyState from './kit/EmptyState.svelte';

  let { projection, dark, feed }: { projection: Projection; dark: boolean; feed: FeedRow } = $props();
  const configured = $derived(!!projection.front_systems?.[sync.accountId]);
  const named = $derived.by(() => { projection.front_systems; return configured ? sync.frontHistory() : null; });
  const candidates = $derived(feedCandidates(projection, Date.now(), configured ? named?.effective.intervals ?? [] : undefined));
  const rows = $derived.by(() => {
    try {
      const ast = core.feedParse(feed.query);
      return filterFeed(candidates, ast, feedContext(projection, ast)).filter((r) => r.post).slice(0, 50);
    } catch { return []; }
  });
  const people = $derived(new Map(members(projection).map((m) => [m.id, m] as [string, MemberRow])));
  const social = $derived(socialIndex(posts(projection)));
  const saved = $derived(savedPosts(projection.rows.pref ?? {}, sync.accountId));
  const postById = $derived(new Map(posts(projection).map((p) => [p.id, p])));
  const reacts = $derived(reactions(projection));
  const speaker = $derived(members(projection).find((m) => canWriteAs(projection, m.id, sync.accountId))?.id ?? null);
  const memberName = (m: string) => people.get(m)?.display_name ?? people.get(m)?.name ?? 'Someone';
  function react(post: PostRow, emoji: string, add: boolean, as = speaker) {
    if (as) sync.create(add ? 'post.react' : 'post.unreact', sync.accountScope, post.id, postReaction(post.id, emoji, as));
  }
</script>

<div class="feed-posts">
  {#each rows as result (result.id)}
    {@const post = result.post!}
    <PostCard {projection} {post} quote={quoteView(post.quote_post_id, postById, memberName)} {people} {dark} reactions={reacts.get(post.id) ?? new Map()} {speaker} compactEntry {social} {saved}
      replyCount={social.replies.get(post.id) ?? 0} onreply={() => router.go(`/journal/post/${encodeURIComponent(post.id)}`)} onreact={(emoji, add, as) => react(post, emoji, add, as)} />
  {:else}<EmptyState icon="journal" label="Nothing yet" />{/each}
</div>

<style>
  .feed-posts { display: grid; border-top: 1px solid var(--line); }
</style>
