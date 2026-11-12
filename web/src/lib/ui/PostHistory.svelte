<script lang="ts">
  import { apiFetch } from '../http';
  import { apiBase } from '../sync/device';
  import { sync } from '../sync/client';
  import type { Entity } from '../core';
  import RichText from './RichText.svelte';
  let { id, open = false }: { id: string; open?: boolean } = $props();
  let items = $state<{ rev: number; title?: string; text: string; entities: Entity[]; cw?: string; at: number }[]>([]);
  let loading = $state(false);
  let error = $state('');
  async function load() {
    loading = true; error = ''; items = [];
    try {
      const r = await apiFetch(`${apiBase()}/posts/${encodeURIComponent(id)}/revisions`, { headers: { authorization: `Bearer ${sync.device?.session ?? ''}` } });
      if (!r.ok) throw new Error(r.status === 404 ? 'This history is no longer available.' : 'Could not load history. Try again when connected.');
      items = (await r.json()).items;
    } catch (e) { error = e instanceof Error ? e.message : String(e); }
    finally { loading = false; }
  }
  $effect(() => { if (open) void load(); });
</script>
<details {open} ontoggle={(e) => { if (e.currentTarget.open) void load(); else items = []; }}>
  <summary>Edit history</summary>
  {#if loading}<p role="status">Loading history…</p>{/if}
  {#if error}<p role="alert">{error}</p><button onclick={load}>Retry</button>{/if}
  {#each items as item (item.rev)}
    <section><p>{item.rev === 0 ? 'Original' : `Edit ${item.rev}`} · {new Date(item.at).toLocaleString()}</p>
      {#if item.cw}<details><summary>Content warning: {item.cw}</summary>{#if item.title}<strong>{item.title}</strong>{/if}<RichText text={item.text} entities={item.entities} /></details>
      {:else}{#if item.title}<strong>{item.title}</strong>{/if}<RichText text={item.text} entities={item.entities} />{/if}
    </section>
  {/each}
</details>
<style>
  details { color: var(--ink-2); font-size: var(--fs-sm); } summary, button { cursor: pointer; padding: 8px 0; } section { border-top: 1px solid var(--line); padding: 12px 0; white-space: pre-wrap; overflow-wrap: anywhere; } p { color: var(--ink-3); } button { color: var(--accent); background: none; border: 0; }
</style>
