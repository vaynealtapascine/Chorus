<script lang="ts">
  import { memberListItems, memberLists, members, reactions, type MemberRow } from '../data';
  import { canWriteAs, postReaction, posts, type PostRow } from '../posts';
  import { sync, type Projection } from '../sync/client';
  import PostCard from './PostCard.svelte';
  import PostReplies from './PostReplies.svelte';
  import PostComposer from './PostComposer.svelte';

  let { projection, dark }: { projection: Projection; dark: boolean } = $props();
  const lists = $derived(memberLists(projection));
  const items = $derived(memberListItems(projection));
  const people = $derived(members(projection).filter((m) => !m.deleted));
  const byId = $derived(new Map(people.map((m) => [m.id, m] as [string, MemberRow])));
  const reacts = $derived(reactions(projection));
  let selected = $state('');
  const list = $derived(lists.find((row) => row.id === selected) ?? lists[0]);
  const selectedMembers = $derived(items.get(list?.id ?? '') ?? new Set<string>());
  const timeline = $derived(posts(projection).filter((post) => !post.deleted && post.authors.some((id) => selectedMembers.has(id))));
  const speaker = $derived(people.find((m) => canWriteAs(projection, m.id, sync.accountId))?.id ?? null);
  let name = $state('');
  let description = $state('');
  let replyTo = $state<PostRow | null>(null);

  function createList(e: SubmitEvent) {
    e.preventDefault();
    if (!name.trim()) return;
    const id = sync.newId();
    sync.create('list.set', sync.accountScope, id, { name: name.trim(), description: description.trim() || null,
      visibility: { mode: 'private' } });
    selected = id;
    name = description = '';
  }
  function toggle(memberId: string) {
    if (!list) return;
    sync.create(selectedMembers.has(memberId) ? 'list.remove' : 'list.add', sync.accountScope, list.id, { member_id: memberId });
  }
  function react(post: PostRow, emoji: string, add: boolean) {
    if (speaker) sync.create(add ? 'post.react' : 'post.unreact', sync.accountScope, post.id, postReaction(post.id, emoji, speaker));
  }
</script>

<section class="lists" aria-label="Member lists">
  <p class="muted">Lists gather posts from members in this account. They stay private to this account.</p>
  <form class="create" onsubmit={createList}>
    <label>List name <input bind:value={name} required maxlength="80" placeholder="Close friends" /></label>
    <label>Description <input bind:value={description} maxlength="200" /></label>
    <button>Create list</button>
  </form>
  {#if list}
    <nav aria-label="Lists">{#each lists as row (row.id)}<button class:on={list.id === row.id} onclick={() => (selected = row.id)}>{row.name}</button>{/each}</nav>
    <div class="list-head"><div><h2>{list.name}</h2>{#if list.description}<p>{list.description}</p>{/if}</div>
      <button onclick={() => { sync.create('list.delete', sync.accountScope, list.id, {}); selected = ''; }}>Delete list</button></div>
    <fieldset><legend>Members</legend><div class="members">{#each people as member (member.id)}<label><input type="checkbox" checked={selectedMembers.has(member.id)} onchange={() => toggle(member.id)} /> {member.display_name ?? member.name}</label>{/each}</div></fieldset>
    {#if replyTo}<p>Replying to a list post <button onclick={() => (replyTo = null)}>Cancel</button></p><PostComposer {projection} initialAuthors={speaker ? [speaker] : []} replyTo={replyTo.id} onsent={() => (replyTo = null)} />{/if}
    <div class="timeline">{#each timeline as post (post.id)}<PostCard {post} people={byId} {dark} reactions={reacts.get(post.id) ?? new Map()} {speaker} onreply={() => (replyTo = post)} onreact={(emoji, add) => react(post, emoji, add)} /><PostReplies postId={post.id} />{:else}<p class="muted">No posts from these members yet.</p>{/each}</div>
  {:else}<p class="muted">Create a list to start collecting posts.</p>{/if}
</section>

<style>
  .lists, .create, .timeline { display: grid; gap: var(--s-3); }
  .muted { color: var(--ink-3); font-size: var(--fs-sm); }
  p, h2 { margin: 0; }
  .create { padding: var(--s-3); border: 1px solid var(--line); border-radius: var(--r-md); background: var(--surface); }
  .create label { display: flex; flex-wrap: wrap; align-items: center; gap: var(--s-2); }
  input:not([type='checkbox']) { flex: 1; min-width: 10ch; padding: var(--s-2); border: 1px solid var(--line); border-radius: var(--r-sm); background: var(--surface-2); color: var(--ink); font: inherit; }
  button { justify-self: start; color: var(--accent); cursor: pointer; }
  nav, .members { display: flex; flex-wrap: wrap; gap: var(--s-2); }
  nav button { border: 1px solid var(--line); border-radius: var(--r-full); padding: var(--s-1) var(--s-3); background: var(--surface); }
  nav button.on { background: var(--accent-soft); }
  .list-head { display: flex; justify-content: space-between; gap: var(--s-2); }
  fieldset { border: 1px solid var(--line); border-radius: var(--r-md); }
  legend { color: var(--ink-3); }
</style>
