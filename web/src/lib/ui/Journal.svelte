<script lang="ts">
  import { onMount, untrack } from 'svelte';
  import { channels, groups, members, reactions, selfMember, type MemberRow } from '../data';
  import { canWriteAs, postReaction, posts, type PostRow } from '../posts';
  import { sync, type Projection } from '../sync/client';
  import { bodyAuthors } from '../front-view';
  import PostCard from './PostCard.svelte';
  import Lazy from './Lazy.svelte';
  import { screens } from '../lazy-screens';
  import { composerFor } from '../journal-composer.svelte';
  import { journalCompose, noteSent, openComposer, sentRecently } from '../journal-compose.svelte';
  import { journalUi } from '../journal-ui.svelte';
  import { arrive, seen, type Arrivals } from '../feed-arrivals';
  import { pinMarker, switchMarker } from '../journal-marker';
  import NewPostsPill from './journal/NewPostsPill.svelte';
  import ComposeFab from './journal/ComposeFab.svelte';
  import TimelineEvent from './journal/TimelineEvent.svelte';
  import { MediaQuery } from 'svelte/reactivity';
  import { quoteView } from '../post-threads';
  import { repostOriginal, savedList, savedPosts, socialIndex } from '../journal-social';
  import JournalFriends from './JournalFriends.svelte';
  import { router } from '../router.svelte';
  import JournalLists from './JournalLists.svelte';
  import JournalFeeds from './JournalFeeds.svelte';
  import JournalRail from './journal/JournalRail.svelte';
  import FeedPosts from './FeedPosts.svelte';
  import { savedFeeds } from '../feeds';
  import { BUILT_IN_NAMES, pinnedTabs, storedPins } from '../feed-pins';
  import { inbox, refreshActivity } from '../inbox-state.svelte';
  import { remotePost } from '../remote-posts.svelte';

  import Icon from './Icon.svelte';
  import EmptyState from './kit/EmptyState.svelte';
  import UnderlineTabs from './kit/UnderlineTabs.svelte';
  import MenuSheet from './kit/MenuSheet.svelte';
  import MenuRow from './kit/MenuRow.svelte';
  let { projection, dark }: { projection: Projection; dark: boolean } = $props();
  const self = $derived(selfMember(projection));
  const people = $derived(new Map(members(projection).map((m) => [m.id, m] as [string, MemberRow])));
  const subjectNames = $derived(new Map([...members(projection).map((m) => [m.id, m.display_name ?? m.name] as [string, string]), ...groups(projection).map((g) => [g.id, g.name] as [string, string])]));
  const entries = $derived(posts(projection).filter((p) => !p.deleted));
  const social = $derived(socialIndex(posts(projection)));
  const saved = $derived(savedPosts(projection.rows.pref ?? {}, sync.accountId));
  const byId = $derived(new Map(entries.map((p) => [p.id, p])));
  const postById = $derived(new Map(posts(projection).map((p) => [p.id, p])));
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
  const speaker = $derived(journalUi.as || frontSpeaker || active[0]?.id || null);
  // Android's tabs (Our system, Friends, Journals), then what Android keeps in its menu
  // the tabs are the pinned feeds (F6.8): the built-ins, then saved feeds, as the Feeds screen arranges them
  const feedRows = $derived(savedFeeds(projection));
  const tabIds = $derived(pinnedTabs(storedPins(projection, sync.accountId), new Set(feedRows.map((f) => f.id))));
  const tabs = $derived(tabIds.map((id) => ({ id: id === 'system' ? 'timeline' : id, label: BUILT_IN_NAMES[id] ?? feedRows.find((f) => f.id === id)?.name ?? 'Feed' })));
  const SUB: Record<string, string> = { saved: 'Saved', lists: 'Lists', feeds: 'Feeds' };
  const section = $derived(journalUi.section);
  const setSection = (id: string) => (journalUi.section = id);
  const pinnedFeed = $derived(feedRows.find((f) => f.id === section));
  const shownTimeline = $derived(section === 'entries' ? timeline.filter((item) => item.kind === 'post' && item.post.kind === 'entry') : timeline);
  const savedPostsList = $derived(savedList(entries, saved));
  // the quick box tops the timeline on wider screens; a phone starts a post from the full composer
  const wide = new MediaQuery('min-width: 641px', true);
  const name = (id: string) => people.get(id)?.display_name ?? people.get(id)?.name ?? theirNames.get(id) ?? 'Someone';
  // other accounts' replies and hearts on our posts (GET /posts/activity, D-127)
  $effect(() => { void sync.device?.session; void entries.length; void refreshActivity(); });
  const theirs = $derived(inbox.account === sync.accountId ? inbox.activity : null);
  const theirNames = $derived(new Map(Object.values(theirs?.reactions ?? {}).flat().map((r) => [r.member_id, r.member_name] as [string, string])));
  const repliesTo = (id: string) => (social.replies.get(id) ?? 0) + (theirs?.reply_counts[id] ?? 0);
  function reactionsOf(id: string): Map<string, string[]> {
    const own = reacts.get(id) ?? new Map<string, string[]>();
    const other = theirs?.reactions[id];
    if (!other?.length) return own;
    const out = new Map([...own].map(([e, ids]) => [e, [...ids]] as [string, string[]]));
    for (const r of other) { const ids = out.get(r.emoji) ?? []; if (!ids.includes(r.member_id)) out.set(r.emoji, [...ids, r.member_id]); }
    return out;
  }
  const quoteOf = (post: PostRow) => quoteView(post.quote_post_id, postById, memberName,
    post.quote_post_id && !postById.has(post.quote_post_id) ? remotePost(post.quote_post_id) : null);
  const memberName = name;
  const frontName = (e: { subject_id: string }) => subjectNames.get(e.subject_id) ?? 'Someone';
  function react(post: PostRow, emoji: string, add: boolean, as = speaker) {
    if (!as) return;
    sync.create(add ? 'post.react' : 'post.unreact', sync.accountScope, post.id, postReaction(post.id, emoji, as));
  }

  // posts that arrive while the reader is scrolled down raise a pill instead of moving the page (Android's rule)
  const reduced = typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches;
  const systemTab = $derived(section === 'timeline' || section === 'entries');
  const newest = $derived(systemTab ? shownTimeline.find((item) => item.kind === 'post')?.id ?? null : null);
  let arrivals = $state<Arrivals | null>(null);
  let atTop = true;
  const scrollUp = () => window.scrollTo({ top: 0, behavior: reduced ? 'auto' : 'smooth' });
  const toTop = () => { scrollUp(); arrivals = seen(arrivals); };
  $effect(() => {
    const tab = section, latest = newest;
    if (!systemTab) { arrivals = null; return; }
    const r = arrive(untrack(() => arrivals), tab, latest, { atTop, justPosted: sentRecently() ? latest : null });
    arrivals = r.state;
    if (r.scrollTop) scrollUp();
  });
  onMount(() => {
    const onscroll = () => { atTop = window.scrollY < 48; if (atTop) arrivals = seen(arrivals); };
    onscroll();
    addEventListener('scroll', onscroll, { passive: true });
    return () => { removeEventListener('scroll', onscroll); journalUi.section = 'timeline'; journalUi.menu = false; };
  });
  // a post entering the list after it settled slides in; the first list and a tab change do not
  const arrives = (node: HTMLElement) => { if (settled && !reduced) node.animate([{ opacity: 0, transform: 'translateY(-8px)' }, { opacity: 1, transform: 'none' }], { duration: 200, easing: 'ease-out' }); };
  let settled = false;
  $effect(() => { void section; settled = false; const t = setTimeout(() => (settled = true), 800); return () => clearTimeout(t); });
</script>

<div class="journal-layout"><section class="journal">
  {#if SUB[section]}<div class="sub"><button class="icon-btn back" aria-label="Back" onclick={() => setSection('timeline')}><Icon name="chevron" size={20} /></button><h2>{SUB[section]}</h2></div>
  {:else}<UnderlineTabs label="Journal sections" value={section} {tabs} onchange={setSection} />{#if arrivals?.pill}<NewPostsPill onclick={toTop} />{/if}{/if}
  {#if section === 'lists'}<div class="page"><JournalLists {projection} {dark} /></div>{:else if section === 'feeds'}<div class="page"><JournalFeeds {projection} {dark} onlists={() => setSection('lists')} /></div>
  {:else if pinnedFeed}<FeedPosts {projection} {dark} feed={pinnedFeed} />
  {:else if section === 'friends'}<JournalFriends {projection} {speaker} speakerName={speaker ? name(speaker) : ''} {dark} />
  {:else if section === 'saved'}
  <div class="timeline">
    {#each savedPostsList as post (post.id)}
      <PostCard {projection} {post} quote={quoteOf(post)} {people} {dark} reactions={reactionsOf(post.id)} reactorNames={theirNames} {speaker} compactEntry
        {social} {saved} replyCount={repliesTo(post.id)} replyToNames={post.reply_to ? byId.get(post.reply_to)?.authors.map(name).join(' & ') : undefined}
        onreply={() => router.go(`/journal/post/${encodeURIComponent(post.id)}`)} onreact={(emoji, add, as) => react(post, emoji, add, as)} />
    {:else}<EmptyState icon="bookmark" label="Nothing saved" />{/each}
  </div>
  {:else}
  {#if wide.current}<Lazy load={screens.composer} c={composerFor('main')} {projection} {dark} surface="quick" authors={speaker ? [speaker] : []} onexpand={() => openComposer({ inline: true })} onsent={noteSent} />{/if}
  <div class="timeline">
    {#each shownTimeline as item (`${item.kind}:${item.id}`)}
      {#if item.kind === 'post'}
        {@const original = item.post.repost_of ? repostOriginal(social, item.post) : item.post}
        {@const by = item.post.repost_of ? item.post.authors.map(name).join(' & ') || 'Someone' : undefined}
        {#if !original}<p class="repost-gone">{by} reposted a post that’s no longer available</p>
        {:else}
        <div use:arrives>
        <!-- replying opens the post's thread, where the reply box shows what it answers -->
        <PostCard {projection} post={original} quote={quoteOf(original)} {people} {dark} reactions={reactionsOf(original.id)} reactorNames={theirNames} {speaker} compactEntry
          {social} {saved} repostedBy={by} replyCount={repliesTo(original.id)} replyToNames={original.reply_to ? byId.get(original.reply_to)?.authors.map(name).join(' & ') : undefined}
          onreply={() => router.go(`/journal/post/${encodeURIComponent(original.id)}`)} onreact={(emoji, add, as) => react(original, emoji, add, as)} />
        </div>
        {/if}
      {:else if item.kind === 'switch'}
        <TimelineEvent marker={switchMarker({ retracted: item.sw.retracted, names: item.sw.fronting.map(frontName), at: item.at })} />
      {:else}
        <TimelineEvent marker={pinMarker({ channel: channelById.get(item.pin.channelId) ?? null, text: item.pin.text.slice(0, 160), at: item.at })} />
      {/if}
    {:else}<EmptyState icon={section === 'entries' ? 'book' : 'journal'} label="Nothing yet" />{/each}
  </div>
  {/if}
</section>
{#if journalUi.menu}
  <MenuSheet title="Journal" onclose={() => (journalUi.menu = false)}>
    {#if self}<MenuRow label="My profile" icon="user" href="#/profile/{self.id}" onclick={() => (journalUi.menu = false)} />{/if}
    <MenuRow label="Saved" icon="bookmark" onclick={() => { journalUi.menu = false; setSection('saved'); }} />
    <MenuRow label="Search saved posts" icon="search" href="#/search/saved" onclick={() => (journalUi.menu = false)} />
    <MenuRow label="Lists" icon="members" onclick={() => { journalUi.menu = false; setSection('lists'); }} />
    {#if !self}<MenuRow label="System board" icon="layers" href="#/journal/board" onclick={() => (journalUi.menu = false)} />{/if}
  </MenuSheet>
{/if}
<JournalRail {projection} {section} onsection={setSection} />
{#if !SUB[section] && !journalCompose.open}<ComposeFab kind={section === 'entries' ? 'entry' : 'note'} />{/if}</div>

<style>
  .journal-layout { display: grid; grid-template-columns: minmax(0, 1fr) 280px; gap: var(--s-6); }
  @media (max-width: 1279px) { .journal-layout { grid-template-columns: minmax(0, 1fr); } .journal-layout > :global(.journal-context) { display: none; } }
  .journal-layout > :global(.journal-context) { margin-top: var(--s-6); }
  /* one column: tabs, the quick box and the posts are cells that stack with no gaps, bordered at the sides as F6.13's */
  .journal { width: 100%; max-width: 760px; margin: 0 auto; display: grid; grid-template-columns: minmax(0, 1fr); gap: 0; align-content: start; min-width: 0; }
  /* down to the screen's foot (under the app bar; above the tab bar below 1024 px) */
  @media (min-width: 641px) { .journal { border-inline: 1px solid var(--line); min-height: calc(100dvh - 76px); } }
  @media (min-width: 641px) and (max-width: 1023px) { .journal { min-height: calc(100dvh - 144px - env(safe-area-inset-bottom)); } }
  p { margin: 0; }
  /* switching sections fades the new one in */
  .timeline { animation: chorus-fade-in var(--t-fast) var(--ease); }
  .icon-btn { display: grid; place-items: center; width: 44px; height: 44px; border: 0; border-radius: 50%; background: none; color: var(--ink-2); cursor: pointer; }
  .icon-btn:hover { background: var(--surface-2); }
  .icon-btn.back { transform: rotate(180deg); }
  .sub { display: flex; align-items: center; gap: var(--s-1); min-height: 52px; padding: 0 var(--s-4) 0 var(--s-2); border-bottom: 1px solid var(--line); }
  .sub h2 { margin: 0; font-size: 19px; font-weight: 600; }
  .page { padding: var(--s-2) var(--s-4) var(--s-4); }
  .timeline { display: grid; grid-template-columns: minmax(0, 1fr); gap: 0; }
  .repost-gone { margin: 0; padding: var(--s-3) var(--s-4); border-bottom: 1px solid var(--line); color: var(--ink-3); font-size: var(--fs-sm); }
</style>
