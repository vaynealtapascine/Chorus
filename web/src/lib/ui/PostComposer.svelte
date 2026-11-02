<script lang="ts">
  import Icon from './Icon.svelte';
  import { core } from '../core';
  import { buckets, members, selfMember } from '../data';
  import { canWriteAs } from '../posts';
  import { sync, type Projection } from '../sync/client';
  import { bodyAuthors } from '../front-view';
  import { pollPayload } from '../polls';

  let { projection, initialAuthors = [], replyTo, defaultVisibility = 'private', onsent }: { projection: Projection; initialAuthors?: string[]; replyTo?: string; defaultVisibility?: 'private' | 'followers' | 'server'; onsent?: () => void } = $props();
  const active = $derived(members(projection).filter((m) => canWriteAs(projection, m.id, sync.accountId)));
  const availableBuckets = $derived(buckets(projection));
  const frontAuthors = () => bodyAuthors(projection, sync.accountId);
  let authors = $state<string[]>([]);
  let initialized = $state(false);
  $effect(() => {
    if (!initialized) { authors = initialAuthors.length ? [...initialAuthors] : selfMember(projection) ? [selfMember(projection)!.id] : frontAuthors(); visibility = defaultVisibility; initialized = true; }
  });
  let kind = $state<'note' | 'entry'>('note');
  let title = $state('');
  let body = $state('');
  let cw = $state('');
  let mood = $state('');
  let tags = $state('');
  let visibility = $state<'private' | 'buckets' | 'followers' | 'server'>('private');
  let bucketIds = $state<string[]>([]);
  let error = $state('');
  // an optional poll (D-081): options and single/multiple are fixed once posted
  let pollOn = $state(false);
  let pollOptions = $state(['', '']);
  let pollMultiple = $state(false);
  const authorLabel = $derived(active.filter((m) => authors.includes(m.id)).map((m) => m.display_name ?? m.name).join(' & ') || 'Choose author');

  function toggle(id: string) {
    authors = authors.includes(id) ? authors.filter((x) => x !== id) : [...authors, id];
  }
  function toggleBucket(id: string) {
    bucketIds = bucketIds.includes(id) ? bucketIds.filter((x) => x !== id) : [...bucketIds, id];
  }

  let optionsOpen = $state(false);
  function send(e: SubmitEvent) {
    e.preventDefault();
    if (!authors.length || !body.trim() || authors.some((id) => !canWriteAs(projection, id, sync.accountId)) || (visibility === 'buckets' && !bucketIds.length)) return;
    const pollDraft = pollOn ? pollPayload(pollOptions, pollMultiple) : {};
    if (pollDraft.error) { error = pollDraft.error; return; }
    try {
      const rich = core.parseMarkup(body);
      sync.create('post.create', sync.accountScope, sync.newId(), {
        kind, authors, title: kind === 'entry' ? title.trim() || null : null,
        text: rich.text, entities: rich.entities, mood: mood.trim() || null,
        tags: tags.split(',').map((t) => t.trim().replace(/^#/, '')).filter(Boolean),
        cw: cw.trim() || null, visibility: visibility === 'buckets' ? { mode: 'buckets', bucket_ids: bucketIds } : { mode: visibility },
        ...(replyTo ? { reply_to: replyTo } : {}),
        ...(pollDraft.poll ? { poll: pollDraft.poll } : {}),
      });
      title = body = cw = mood = tags = error = '';
      pollOn = pollMultiple = false;
      optionsOpen = false; // the popover would otherwise cover the feed it just posted to
      pollOptions = ['', ''];
      onsent?.();
    } catch (cause) { error = cause instanceof Error ? cause.message : String(cause); }
  }
</script>

<form class="composer" onsubmit={send}>
  <div class="head">
    <details class="author-picker"><summary>{authorLabel}</summary><fieldset><legend>Writing as</legend><div class="authors">{#each active as member (member.id)}<label class:on={authors.includes(member.id)}><input type="checkbox" checked={authors.includes(member.id)} onchange={() => toggle(member.id)} />{member.display_name ?? member.name}</label>{/each}</div></fieldset></details>
    <div class="seg"><button type="button" class:on={kind === 'note'} onclick={() => (kind = 'note')}>Note</button><button type="button" class:on={kind === 'entry'} onclick={() => (kind = 'entry')}>Entry</button></div>
  </div>
  {#if kind === 'entry'}<input bind:value={title} placeholder="Title (optional)" aria-label="Entry title" />{/if}
  <textarea bind:value={body} rows={kind === 'entry' ? 9 : 2} placeholder={replyTo ? 'Reply…' : kind === 'entry' ? 'Write your entry…' : "What's on your mind?"} aria-label="Post text"></textarea>
  <div class="compose-footer">
    <details class="extras" bind:open={optionsOpen}><summary><Icon name="plus" size={16} />Post options</summary><div class="extra-fields"><input bind:value={cw} placeholder="Content warning (optional)" aria-label="Content warning" /><input bind:value={mood} placeholder="Mood" aria-label="Mood" /><input bind:value={tags} placeholder="Tags, separated by commas" aria-label="Tags" />
      <label><input type="checkbox" bind:checked={pollOn} /> Add a poll</label>
      {#if pollOn}
        {#each pollOptions as _, i (i)}<input bind:value={pollOptions[i]} placeholder="Option {i + 1}" aria-label="Poll option {i + 1}" maxlength="200" />{/each}
        {#if pollOptions.length < 20}<button type="button" onclick={() => (pollOptions = [...pollOptions, ''])}>Add option</button>{/if}
        <label><input type="checkbox" bind:checked={pollMultiple} /> Allow more than one choice</label>
      {/if}
    </div></details>
    <label class="audience"><Icon name="lock" size={14} /><span class="sr-label">Visible to</span><select aria-label="Visible to" bind:value={visibility}><option value="private">{selfMember(projection) ? 'Only me' : 'Only our system'}</option>{#if availableBuckets.length}<option value="buckets">Selected buckets</option>{/if}<option value="followers">Followers</option><option value="server">Everyone on server</option></select></label>
    <button class="primary" disabled={!authors.length || !body.trim() || (visibility === 'buckets' && !bucketIds.length)}>{replyTo ? 'Reply' : 'Post'}</button>
  </div>
  {#if visibility === 'buckets'}<div class="authors" aria-label="Visible buckets">{#each availableBuckets as bucket (bucket.id)}<label class:on={bucketIds.includes(bucket.id)}><input type="checkbox" checked={bucketIds.includes(bucket.id)} onchange={() => toggleBucket(bucket.id)} />{bucket.name}</label>{/each}</div>{/if}
  {#if error}<p class="error" role="alert">{error}</p>{/if}
</form>

<style>
  .composer { display: grid; gap: var(--s-3); background: var(--bg); border: 0; border-bottom: 1px solid var(--line); border-radius: 0; padding: var(--s-4); }
  .head { display: flex; align-items: center; justify-content: space-between; gap: var(--s-3); }
  .seg { display: flex; background: var(--surface-2); border-radius: var(--r-full); padding: 2px; }
  .seg button { border: 0; background: none; color: var(--ink-2); padding: var(--s-1) var(--s-3); border-radius: var(--r-full); cursor: pointer; }
  .seg button.on { background: var(--surface); color: var(--ink); }
  fieldset { border: 0; padding: 0; margin: 0; } legend { color: var(--ink-3); font-size: var(--fs-sm); }
  .authors { display: flex; flex-wrap: wrap; max-height: 6rem; overflow: auto; gap: var(--s-1); }
  .authors label { display: flex; align-items: center; gap: 4px; font-size: var(--fs-sm); padding: 2px var(--s-2); border: 1px solid var(--line); border-radius: var(--r-full); cursor: pointer; }
  .authors label.on { background: var(--accent-soft); }
  input:not([type='checkbox']), textarea, select { border: 1px solid var(--line); border-radius: var(--r-sm); background: var(--surface-2); color: var(--ink); padding: var(--s-2); font: inherit; }
  textarea { width: 100%; resize: vertical; box-sizing: border-box; }
  .extras { margin-right: auto; position: relative; }
  .extras input { flex: 1 1 9rem; min-width: 0; }
  .primary { justify-self: end; min-height: 40px; border: 0; border-radius: var(--r-sm); background: var(--accent); color: var(--on-accent); padding: var(--s-2) var(--s-4); cursor: pointer; }
  .primary:disabled { opacity: .5; cursor: default; }
  .error { color: var(--danger); margin: 0; }
  .author-picker { min-width: 0; position: relative; flex: 1; }
  .author-picker summary { cursor: pointer; color: var(--ink); font-size: var(--fs-sm); min-height: 40px; display: flex; align-items: center; font-weight: 600; }
  .author-picker fieldset { position: absolute; z-index: 3; left: 0; top: 100%; width: min(420px, 75vw); background: var(--surface); padding: 16px; border: 1px solid var(--line); border-radius: 12px; }
  .author-picker legend { background: var(--surface); }
  .authors label { min-height: 40px; }
  textarea { border: 0; padding: 8px 0; background: transparent; min-height: 56px; }
  .compose-footer { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
  .extras summary { cursor: pointer; display: flex; gap: 6px; align-items: center; color: var(--ink-3); font-size: var(--fs-xs); min-height: 40px; }
  .extra-fields { display: grid; gap: 8px; position: absolute; left: 0; top: 100%; z-index: 3; padding: 12px; border: 1px solid var(--line); border-radius: 12px; background: var(--surface); width: min(280px, 75vw); }
  .audience { display: flex; align-items: center; gap: 4px; color: var(--ink-3); min-width: 0; }
  .audience select { padding: 6px; border: 0; border-radius: 999px; font-size: var(--fs-xs); max-width: 160px; }
  .sr-label { position: absolute; width: 1px; height: 1px; clip-path: inset(50%); overflow: hidden; white-space: nowrap; }
</style>
