<script lang="ts" module>
  import type { ThreadPost } from '../post-threads';
  // other accounts' posts, read once per page through the server's audience check
  const remote = new Map<string, Promise<ThreadPost | null>>();
</script>

<script lang="ts">
  // What a message links to (D-109), under it like a quote: a post or entry, or another message.
  // Only what this reader may already see: own posts from the replica, others' through the
  // server's audience check; messages through the same rule as replies (`lookup`: the chat is
  // shown to us and, in our own chat, the message's audience includes who is here).
  import { linksIn, type LinkTarget } from '../share-links';
  import type { Entity } from '../core';
  import type { MessageRow } from '../data';
  import type { PostRow } from '../posts';
  import { quoteView, type QuoteView } from '../post-threads';
  import { apiFetch } from '../http';
  import { apiBase } from '../sync/device';
  import { sync } from '../sync/client';
  import QuoteEmbed from './QuoteEmbed.svelte';

  let { text, entities, lookup, postOf, nameOf, onjump }: {
    text: string;
    entities: Entity[];
    lookup: (id: string) => (MessageRow & { channel_name?: string }) | undefined;
    postOf: (id: string) => PostRow | undefined;
    nameOf: (memberId: string) => string;
    onjump?: (target: MessageRow) => void;
  } = $props();

  const targets = $derived(linksIn(text, entities, location.origin).slice(0, 3));

  function fetchPost(id: string): Promise<ThreadPost | null> {
    let hit = remote.get(id);
    if (!hit) {
      hit = apiFetch(`${apiBase()}/posts/${encodeURIComponent(id)}`, { headers: { authorization: `Bearer ${sync.device?.session ?? ''}` } })
        .then((r) => (r.ok ? r.json() : null))
        .catch(() => null);
      remote.set(id, hit);
    }
    return hit;
  }

  function postView(t: LinkTarget): QuoteView | Promise<QuoteView | null> {
    const own = postOf(t.id);
    if (own) return quoteView(t.id, new Map([[t.id, own]]), nameOf)!;
    return fetchPost(t.id).then((p) => quoteView(t.id, new Map(), nameOf, p));
  }
</script>

{#each targets as t (`${t.kind}:${t.id}`)}
  {#if t.kind === 'post'}
    {@const view = postView(t)}
    {#if view instanceof Promise}
      {#await view then v}{#if v}<QuoteEmbed quote={v} gone="Post unavailable" />{/if}{/await}
    {:else}
      <QuoteEmbed quote={view} gone="Post unavailable" />
    {/if}
  {:else}
    {@const found = lookup(t.id)}
    {#if found && !found.deleted}
      <button class="message" onclick={(e) => { e.stopPropagation(); onjump?.(found); }} disabled={!onjump}>
        <span class="by"><strong>{found.authors.map(nameOf).join(' & ') || 'Someone'}</strong>{#if found.channel_name} · {found.channel_name}{/if} · {new Date(found.occurred_at).toLocaleDateString()}</span>
        <span class="text">{found.cw ? `Content warning: ${found.cw}` : found.text}</span>
      </button>
    {:else}
      <p class="gone">Message unavailable</p>
    {/if}
  {/if}
{/each}

<style>
  .message { display: grid; gap: var(--s-1); padding: var(--s-3); border: 1px solid var(--line); border-radius: var(--r-row); background: none; color: var(--ink); font: inherit; text-align: left; cursor: pointer; min-width: 0; width: 100%; }
  .message:hover:not(:disabled) { background: var(--surface); }
  .by { color: var(--ink-3); font-size: var(--fs-xs); }
  .by strong { color: var(--ink); }
  .text { display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 3; line-clamp: 3; overflow: hidden; white-space: pre-wrap; overflow-wrap: anywhere; font-size: var(--fs-sm); }
  .gone { margin: 0; color: var(--ink-3); font-size: var(--fs-sm); }
</style>
