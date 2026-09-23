<script lang="ts">
  import { core } from '../core';
  import { buckets, members } from '../data';
  import { canWriteAs } from '../posts';
  import { sync, type Projection } from '../sync/client';

  let { projection, initialAuthors = [], replyTo, onsent }: { projection: Projection; initialAuthors?: string[]; replyTo?: string; onsent?: () => void } = $props();
  const active = $derived(members(projection).filter((m) => canWriteAs(projection, m.id, sync.accountId)));
  const availableBuckets = $derived(buckets(projection));
  const frontAuthors = () => (projection.fronts[sync.accountId]?.current ?? []).filter((e) => e.subject_type === 'member' && e.level === 'front').map((e) => e.subject_id);
  let authors = $state<string[]>([]);
  let initialized = $state(false);
  $effect(() => {
    if (!initialized) { authors = initialAuthors.length ? [...initialAuthors] : frontAuthors(); initialized = true; }
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

  function toggle(id: string) {
    authors = authors.includes(id) ? authors.filter((x) => x !== id) : [...authors, id];
  }
  function toggleBucket(id: string) {
    bucketIds = bucketIds.includes(id) ? bucketIds.filter((x) => x !== id) : [...bucketIds, id];
  }

  function send(e: SubmitEvent) {
    e.preventDefault();
    if (!authors.length || !body.trim() || authors.some((id) => !canWriteAs(projection, id, sync.accountId)) || (visibility === 'buckets' && !bucketIds.length)) return;
    try {
      const rich = core.parseMarkup(body);
      sync.create('post.create', sync.accountScope, sync.newId(), {
        kind, authors, title: kind === 'entry' ? title.trim() || null : null,
        text: rich.text, entities: rich.entities, mood: mood.trim() || null,
        tags: tags.split(',').map((t) => t.trim().replace(/^#/, '')).filter(Boolean),
        cw: cw.trim() || null, visibility: visibility === 'buckets' ? { mode: 'buckets', bucket_ids: bucketIds } : { mode: visibility },
        ...(replyTo ? { reply_to: replyTo } : {}),
      });
      title = body = cw = mood = tags = error = '';
      onsent?.();
    } catch (cause) { error = cause instanceof Error ? cause.message : String(cause); }
  }
</script>

<form class="composer" onsubmit={send}>
  <div class="head"><h2>{replyTo ? 'Write a reply' : 'Write a post'}</h2>
    <div class="seg"><button type="button" class:on={kind === 'note'} onclick={() => (kind = 'note')}>Note</button><button type="button" class:on={kind === 'entry'} onclick={() => (kind = 'entry')}>Entry</button></div>
  </div>
  <fieldset><legend>Writing as</legend><div class="authors">{#each active as member (member.id)}<label class:on={authors.includes(member.id)}><input type="checkbox" checked={authors.includes(member.id)} onchange={() => toggle(member.id)} />{member.display_name ?? member.name}</label>{/each}</div></fieldset>
  {#if kind === 'entry'}<input bind:value={title} placeholder="Title (optional)" aria-label="Entry title" />{/if}
  <textarea bind:value={body} rows={kind === 'entry' ? 9 : 4} placeholder={replyTo ? 'Reply…' : kind === 'entry' ? 'Write your entry…' : 'Write a note…'} aria-label="Post text"></textarea>
  <div class="extras"><input bind:value={cw} placeholder="Content warning (optional)" aria-label="Content warning" /><input bind:value={mood} placeholder="Mood" aria-label="Mood" /><input bind:value={tags} placeholder="Tags, separated by commas" aria-label="Tags" />
    <label>Visible to <select bind:value={visibility}><option value="private">Only this account</option>{#if availableBuckets.length}<option value="buckets">Selected buckets</option>{/if}<option value="followers">Followers</option><option value="server">Everyone on server</option></select></label></div>
  {#if visibility === 'buckets'}<div class="authors" aria-label="Visible buckets">{#each availableBuckets as bucket (bucket.id)}<label class:on={bucketIds.includes(bucket.id)}><input type="checkbox" checked={bucketIds.includes(bucket.id)} onchange={() => toggleBucket(bucket.id)} />{bucket.name}</label>{/each}</div>{/if}
  {#if error}<p class="error" role="alert">{error}</p>{/if}
  <button class="primary" disabled={!authors.length || !body.trim() || (visibility === 'buckets' && !bucketIds.length)}>Post</button>
</form>

<style>
  .composer { display: grid; gap: var(--s-3); background: var(--surface); border: 1px solid var(--line); border-radius: var(--r-lg); padding: var(--s-4); }
  .head { display: flex; align-items: center; justify-content: space-between; gap: var(--s-3); }
  h2 { margin: 0; font-size: var(--fs-lg); }
  .seg { display: flex; background: var(--surface-2); border-radius: var(--r-full); padding: 2px; }
  .seg button { border: 0; background: none; color: var(--ink-2); padding: var(--s-1) var(--s-3); border-radius: var(--r-full); cursor: pointer; }
  .seg button.on { background: var(--surface); color: var(--ink); }
  fieldset { border: 0; padding: 0; margin: 0; } legend { color: var(--ink-3); font-size: var(--fs-sm); }
  .authors { display: flex; flex-wrap: wrap; max-height: 6rem; overflow: auto; gap: var(--s-1); }
  .authors label { display: flex; align-items: center; gap: 4px; font-size: var(--fs-sm); padding: 2px var(--s-2); border: 1px solid var(--line); border-radius: var(--r-full); cursor: pointer; }
  .authors label.on { background: var(--accent-soft); }
  input:not([type='checkbox']), textarea, select { border: 1px solid var(--line); border-radius: var(--r-sm); background: var(--surface-2); color: var(--ink); padding: var(--s-2); font: inherit; }
  textarea { width: 100%; resize: vertical; box-sizing: border-box; }
  .extras { display: flex; flex-wrap: wrap; gap: var(--s-2); }
  .extras label { color: var(--ink-3); display: flex; gap: var(--s-2); align-items: center; font-size: var(--fs-sm); }
  .extras input { flex: 1 1 9rem; min-width: 0; }
  .primary { justify-self: end; border: 0; border-radius: var(--r-sm); background: var(--accent); color: var(--bg); padding: var(--s-2) var(--s-4); cursor: pointer; }
  .primary:disabled { opacity: .5; cursor: default; }
  .error { color: var(--danger); margin: 0; }
</style>
