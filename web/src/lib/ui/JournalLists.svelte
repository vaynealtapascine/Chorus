<script lang="ts">
  import Button from './kit/Button.svelte';
  import { memberListItems, memberLists, members, reactions, type MemberRow } from '../data';
  import { canWriteAs, postReaction, posts, type PostRow } from '../posts';
  import { sync, type Projection } from '../sync/client';
  import PostCard from './PostCard.svelte';
  import { quoteView } from '../post-threads';
  import { savedPosts, socialIndex } from '../journal-social';
  import { router } from '../router.svelte';
  import Icon from './Icon.svelte';
  import Face from './Face.svelte';
  import EmptyState from './kit/EmptyState.svelte';
  import { savedFeeds } from '../feeds';
  import { faceOf } from '../many-members';

  let { projection, dark, open = '' }: { projection: Projection; dark: boolean; open?: string } = $props();
  const lists = $derived(memberLists(projection));
  const items = $derived(memberListItems(projection));
  const people = $derived(members(projection).filter((m) => !m.deleted));
  const byId = $derived(new Map(people.map((m) => [m.id, m] as [string, MemberRow])));
  const reacts = $derived(reactions(projection));
  // svelte-ignore state_referenced_locally
  let selected = $state(open);
  const list = $derived(lists.find((row) => row.id === selected));
  const selectedMembers = $derived(items.get(list?.id ?? '') ?? new Set<string>());
  const social = $derived(socialIndex(posts(projection)));
  const saved = $derived(savedPosts(projection.rows.pref ?? {}, sync.accountId));
  const postById = $derived(new Map(posts(projection).map((p) => [p.id, p])));
  const memberName = (m: string) => byId.get(m)?.display_name ?? byId.get(m)?.name ?? 'Someone';
  const timeline = $derived(posts(projection).filter((post) => !post.deleted && !post.repost_of && post.authors.some((id) => selectedMembers.has(id))));
  const speaker = $derived(people.find((m) => canWriteAs(projection, m.id, sync.accountId))?.id ?? null);
  // svelte-ignore state_referenced_locally
  let opened = $state(!!open);
  let creating = $state(false);
  const feeds = $derived(savedFeeds(projection));
  // a saved feed that reads from this list: its query says from:list:"name"
  const feedOf = (listName: string) => feeds.find((f) => f.query.toLowerCase().includes(`from:list:"${listName.toLowerCase()}"`) || f.query.toLowerCase().includes(`from:list:${listName.toLowerCase()}`))?.name;
  let name = $state('');
  let description = $state('');

  function createList(e: SubmitEvent) {
    e.preventDefault();
    if (!name.trim()) return;
    const id = sync.newId();
    sync.create('list.set', sync.accountScope, id, { name: name.trim(), description: description.trim() || null,
      visibility: { mode: 'private' } });
    selected = id;
    opened = true;
    creating = false;
    name = description = '';
  }
  function toggle(memberId: string) {
    if (!list) return;
    sync.create(selectedMembers.has(memberId) ? 'list.remove' : 'list.add', sync.accountScope, list.id, { member_id: memberId });
  }
  function react(post: PostRow, emoji: string, add: boolean, as = speaker) {
    if (as) sync.create(add ? 'post.react' : 'post.unreact', sync.accountScope, post.id, postReaction(post.id, emoji, as));
  }
</script>

<section class="lists" aria-label="Member lists">
  {#if !list || !opened}
    {#each lists as row (row.id)}
      {@const ids = [...(items.get(row.id) ?? [])]}
      {@const feed = feedOf(row.name)}
      <button type="button" class="row" onclick={() => { selected = row.id; opened = true; }}>
        <span class="tile"><Icon name="members" size={20} /></span>
        <span class="text"><strong>{row.name}</strong><span>{ids.length} {ids.length === 1 ? "member" : "members"}</span>
          <span class="pills">{#if row.visibility.mode !== "private"}<em>Audience</em>{/if}{#if feed}<em>Feed: {feed}</em>{/if}</span></span>
        <span class="faces">{#each ids.slice(0, 3) as id (id)}{#if byId.get(id)}<Face who={faceOf(byId.get(id)!)} size={28} {dark} />{/if}{/each}</span>
      </button>
    {/each}
    {#if creating}
      <form class="create" onsubmit={createList}>
        <input bind:value={name} required maxlength="80" placeholder="List name" aria-label="List name" />
        <input bind:value={description} maxlength="200" placeholder="Description" aria-label="Description" />
        <div class="actions"><button type="button" onclick={() => (creating = false)}>Cancel</button><Button variant="primary" class="primary">Create list</Button></div>
      </form>
    {:else}<button type="button" class="new" onclick={() => (creating = true)}><Icon name="plus" size={20} />New list</button>{/if}
  {:else}
    <div class="list-head"><button type="button" class="back" aria-label="Back to lists" onclick={() => (opened = false)}><Icon name="chevron" size={20} /></button><h2>{list.name}</h2>
      <button type="button" onclick={() => { sync.create("list.delete", sync.accountScope, list.id, {}); selected = ""; opened = false; }}>Delete list</button></div>
    {#if list.description}<p class="muted">{list.description}</p>{/if}
    <fieldset><legend>Members</legend><div class="members">{#each people as member (member.id)}<label><input type="checkbox" checked={selectedMembers.has(member.id)} onchange={() => toggle(member.id)} /> {member.display_name ?? member.name}</label>{/each}</div></fieldset>
    <div class="timeline">{#each timeline as post (post.id)}<PostCard {projection} {post} quote={quoteView(post.quote_post_id, postById, memberName)} people={byId} {dark} reactions={reacts.get(post.id) ?? new Map()} {speaker} {social} {saved} replyCount={social.replies.get(post.id) ?? 0} onreply={() => router.go(`/journal/post/${encodeURIComponent(post.id)}`)} onreact={(emoji, add, as) => react(post, emoji, add, as)} />{:else}<EmptyState icon="journal" label="Nothing yet" />{/each}</div>
  {/if}
</section>

<style>
  .row { display: flex; align-items: center; gap: var(--s-3); width: 100%; padding: 10px var(--s-1); border: 0; border-bottom: 1px solid var(--line); background: none; color: var(--ink); font: inherit; text-align: left; justify-self: stretch; }
  .tile { display: grid; place-items: center; width: 44px; height: 44px; flex: none; border-radius: var(--r-row); background: var(--accent-soft); color: var(--accent); }
  .text { flex: 1; min-width: 0; display: grid; gap: 2px; }
  .text strong { font-weight: 600; }
  .text > span { color: var(--ink-3); font-size: var(--fs-sm); }
  .pills { display: flex; gap: 6px; flex-wrap: wrap; }
  .pills em { font-style: normal; padding: 1px var(--s-2); border-radius: var(--r-full); background: var(--surface-2); font-size: var(--fs-xs); }
  .faces { display: flex; }
  .faces :global(.face) { margin-left: -8px; box-shadow: 0 0 0 2px var(--bg); }
  .new { display: flex; align-items: center; gap: var(--s-2); justify-self: stretch; justify-content: center; padding: 14px; border: 1px dashed var(--line); border-radius: var(--r-row); background: none; }
  .back { display: grid; place-items: center; width: 40px; height: 40px; padding: 0; border: 0; background: none; transform: rotate(180deg); }
  .actions { display: flex; gap: var(--s-2); justify-content: flex-end; }
  .list-head h2 { flex: 1; }
  .lists, .create, .timeline { display: grid; gap: var(--s-3); }
  .muted { color: var(--ink-3); font-size: var(--fs-sm); }
  p, h2 { margin: 0; }
  .create { padding: var(--s-3); border: 1px solid var(--line); border-radius: var(--r-md); background: var(--surface); }
  input:not([type='checkbox']) { flex: 1; min-width: 10ch; padding: var(--s-2); border: 1px solid var(--line); border-radius: var(--r-sm); background: var(--surface-2); color: var(--ink); font: inherit; }
  button { justify-self: start; color: var(--accent); cursor: pointer; }
  .members { display: flex; flex-wrap: wrap; gap: var(--s-2); }
  .list-head { display: flex; align-items: center; gap: var(--s-2); }
  fieldset { border: 1px solid var(--line); border-radius: var(--r-md); }
  legend { color: var(--ink-3); }
</style>
