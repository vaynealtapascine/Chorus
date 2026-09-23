<script lang="ts">
  import { core } from '../core';
  import type { MemberRow } from '../data';
  import type { PostRow } from '../posts';
  import RichText from './RichText.svelte';
  import AvatarImage from './AvatarImage.svelte';

  let { post, people, dark, reactions, speaker, compactEntry = false, onreply, onreact }: {
    post: PostRow; people: Map<string, MemberRow>; dark: boolean;
    reactions: Map<string, string[]>; speaker: string | null; compactEntry?: boolean;
    onreply: () => void; onreact: (emoji: string, add: boolean) => void;
  } = $props();
  let expanded = $state(false);
  const PALETTE = ['💜', '👍', '🥹', '🎉'];
  const name = (id: string) => people.get(id)?.display_name ?? people.get(id)?.name ?? 'Someone';
  const lead = $derived(people.get(post.authors[0] ?? ''));
  const color = $derived(core.adaptColor(lead?.color ?? '#A09184', dark));
</script>

<article class="post" data-post-id={post.id} style="--author: {color.name}; --tint: {color.tint}">
  <div class="top">
    <span class="avatar" aria-hidden="true"><AvatarImage hash={lead?.avatar_blob} glyph={lead?.sigils[0] ?? name(post.authors[0] ?? '')[0]} name={name(post.authors[0] ?? '')} /></span>
    <div class="meta"><span class="authors">{#each post.authors as id, i (id)}{#if i} &amp; {/if}<a href="#/profile/{id}">{name(id)}</a>{/each}</span>
      <span class="when">{post.kind === 'entry' ? 'Entry' : 'Note'} · {new Date(post.occurred_at).toLocaleString()}</span></div>
    {#if post.visibility.mode !== 'private'}<span class="visibility">{post.visibility.mode === 'buckets' ? 'selected buckets' : post.visibility.mode}</span>{/if}
  </div>
  {#if post.reply_to}<p class="reply-ref">↪ Reply</p>{/if}
  {#if post.cw}<button class="cw" aria-expanded={expanded} onclick={() => (expanded = !expanded)}>Content warning: {post.cw} · {expanded ? 'Hide' : 'Show'}</button>{/if}
  {#if !post.cw || expanded}
    {#if post.title}<h3 class="display">{post.title}</h3>{/if}
    {#if !(compactEntry && post.kind === 'entry')}<p class="body"><RichText text={post.text} entities={post.entities} /></p>{/if}
    {#if post.mood || post.tags.length}<p class="tags">{#if post.mood}Mood: {post.mood}{/if}{#each post.tags as tag (tag)} <span>#{tag}</span>{/each}</p>{/if}
  {/if}
  <div class="actions">
    <button onclick={onreply}>Reply</button>
    <a href="#/stage-post/{post.id}">Stage</a>
    {#each PALETTE as emoji (emoji)}
      {@const selected = speaker ? (reactions.get(emoji) ?? []).includes(speaker) : false}
      <button class:selected disabled={!speaker} onclick={() => onreact(emoji, !selected)} aria-label={`${selected ? 'Remove' : 'Add'} ${emoji} reaction`}>{emoji}{reactions.get(emoji)?.length ? ` ${reactions.get(emoji)?.length}` : ''}</button>
    {/each}
  </div>
</article>

<style>
  .post { display: grid; gap: var(--s-3); padding: var(--s-4); background: var(--surface); border: 1px solid var(--line); border-radius: var(--r-lg); min-width: 0; }
  .top { display: flex; align-items: center; gap: var(--s-3); }
  .avatar { display: inline-grid; width: 40px; height: 40px; border-radius: 50%; overflow: hidden; flex: none; }
  .meta { display: grid; min-width: 0; }
  .authors a { color: var(--author); text-decoration: none; font-weight: 600; }
  .when, .reply-ref, .tags, .visibility { color: var(--ink-3); font-size: var(--fs-xs); }
  .visibility { margin-left: auto; }
  h3, p { margin: 0; }
  .body { white-space: pre-wrap; overflow-wrap: anywhere; }
  .cw { border: 1px solid var(--line); border-radius: var(--r-sm); background: var(--surface-2); color: var(--ink); padding: var(--s-2); text-align: left; cursor: pointer; }
  .actions { display: flex; flex-wrap: wrap; gap: var(--s-2); align-items: center; }
  .actions button, .actions a { border: 0; background: none; color: var(--ink-2); padding: var(--s-1); font: inherit; font-size: var(--fs-sm); text-decoration: none; cursor: pointer; }
  .actions button.selected { background: var(--accent-soft); border-radius: var(--r-sm); }
</style>
