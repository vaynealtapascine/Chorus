<script lang="ts">
  import type { Entity } from '../core';
  import type { AttachmentRow } from '../data';
  import { apiBase } from '../sync/device';
  import { apiFetch } from '../http';
  import { sync } from '../sync/client';
  import AttachmentView from './AttachmentView.svelte';
  import RichText from './RichText.svelte';

  interface Reply {
    id: string;
    text: string;
    title?: string | null;
    cw?: string | null;
    entities: Entity[];
    attachments: AttachmentRow[];
    occurred_at: number;
    author_cards: { name?: string | null; display_name?: string | null }[];
  }
  let { postId }: { postId: string } = $props();
  let open = $state(false);
  let busy = $state(false);
  let error = $state('');
  let replies = $state<Reply[]>([]);
  // everyone's reactions, other accounts' included (theirs never sync into this replica)
  let reactions = $state<{ emoji: string; member_name: string }[]>([]);

  async function load() {
    busy = true;
    error = '';
    try {
      const response = await apiFetch(`${apiBase()}/posts/${encodeURIComponent(postId)}?depth=1`, {
        headers: { authorization: `Bearer ${sync.device?.session ?? ''}` },
      });
      if (!response.ok) throw new Error(`Could not load replies (HTTP ${response.status})`);
      const post = (await response.json()) as { replies?: Reply[]; reactions?: { emoji: string; member_name: string }[] };
      replies = post.replies ?? [];
      reactions = post.reactions ?? [];
    } catch (cause) {
      error = cause instanceof Error ? cause.message : String(cause);
    } finally {
      busy = false;
    }
  }

  function toggle() {
    open = !open;
    if (open) void load();
  }
  const names = (reply: Reply) => reply.author_cards.map((a) => a.display_name ?? a.name ?? 'Someone').join(' & ') || 'Someone';
</script>

<div class="thread">
  <button class="thread-toggle" onclick={toggle}>{open ? 'Hide replies' : 'View replies'}</button>
  {#if open}
    {#if busy}<p class="muted">Loading replies…</p>{/if}
    {#if error}<p class="error" role="alert">{error} <button onclick={load}>Retry</button></p>{/if}
    {#if !busy && !error}
      <button class="refresh" onclick={load}>Refresh replies</button>
      {#if reactions.length}<p class="reactions">{reactions.map((r) => `${r.emoji} ${r.member_name}`).join(' · ')}</p>{/if}
      {#each replies as reply (reply.id)}
        <article class="reply" data-reply-id={reply.id}>
          <p class="by">{names(reply)} · {new Date(reply.occurred_at).toLocaleString()}</p>
          {#if reply.cw}
            <details><summary>Content warning: {reply.cw}</summary>
              {#if reply.title}<strong>{reply.title}</strong>{/if}
              <p><RichText text={reply.text} entities={reply.entities ?? []} /></p>
              {#each reply.attachments ?? [] as attachment (attachment.id)}<AttachmentView {attachment} />{/each}
            </details>
          {:else}
            {#if reply.title}<strong>{reply.title}</strong>{/if}
            <p><RichText text={reply.text} entities={reply.entities ?? []} /></p>
            {#each reply.attachments ?? [] as attachment (attachment.id)}<AttachmentView {attachment} />{/each}
          {/if}
        </article>
      {:else}<p class="muted">No replies yet.</p>{/each}
    {/if}
  {/if}
</div>

<style>
  .thread { display: grid; gap: var(--s-2); margin: calc(-1 * var(--s-2)) 0 var(--s-2) var(--s-4); padding-left: var(--s-3); border-left: 2px solid var(--line); }
  .thread-toggle, .refresh, .error button { justify-self: start; color: var(--accent); background: none; border: 0; padding: var(--s-1); font: inherit; font-size: var(--fs-sm); cursor: pointer; }
  .refresh, .muted, .by, .reactions { color: var(--ink-3); font-size: var(--fs-sm); }
  .reply { display: grid; gap: var(--s-2); padding: var(--s-3); border: 1px solid var(--line); border-radius: var(--r-md); background: var(--surface); }
  p { margin: 0; overflow-wrap: anywhere; }
  .error { color: var(--danger); }
</style>
