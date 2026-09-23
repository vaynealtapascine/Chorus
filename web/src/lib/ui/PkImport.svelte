<script lang="ts">
  // Import from PluralKit (D-005): members, groups and switch history from `pk;export`.
  import { planPluralkit } from '../core/pkg/chorus_wasm.js';
  import { sync } from '../sync/client';
  import { flushUploads, stageBlob } from '../sync/uploads';

  let { onclose }: { onclose: () => void } = $props();

  let text = $state('');
  let plan = $state<{ members: number; groups: number; switches: number; warnings: string[] } | null>(null);
  let error = $state('');
  let done = $state<number | null>(null);
  let busy = $state(false);
  let avatarNotes = $state<string[]>([]);
  const notes = $derived([...(plan?.warnings ?? []).filter((w) => done === null || !w.includes('avatar not imported yet')), ...avatarNotes]);

  async function pick(e: Event) {
    const file = (e.currentTarget as HTMLInputElement).files?.[0];
    if (!file) return;
    error = '';
    done = null;
    try {
      text = await file.text();
      plan = JSON.parse(planPluralkit(text, sync.accountScope));
    } catch (err) {
      plan = null;
      error = err instanceof Error ? err.message : String(err);
    }
  }

  async function run() {
    busy = true;
    avatarNotes = [];
    try {
      done = sync.importPluralkit(text);
      const exported = JSON.parse(text) as { members?: { id?: string; avatar_url?: string }[] };
      const rows = sync.projection()?.rows.member ?? {};
      const byPkId = new Map(Object.entries(rows).map(([id, row]) => [row.fields.pk_id, id]));
      for (const member of exported.members ?? []) {
        if (!member.id || !member.avatar_url) continue;
        try {
          const url = new URL(member.avatar_url);
          if (!['http:', 'https:'].includes(url.protocol)) throw new Error('unsupported URL');
          const response = await fetch(url.href, { mode: 'cors' });
          if (!response.ok) throw new Error(`HTTP ${response.status}`);
          const blob = await response.blob();
          if (!blob.type.startsWith('image/')) throw new Error('not an image');
          const id = byPkId.get(member.id);
          if (!id) throw new Error('member not found after import');
          const upload = await stageBlob(blob, blob.type, sync.accountId);
          sync.create('member.set', sync.accountScope, id, { avatar_blob: upload.hash });
        } catch (err) {
          avatarNotes = [...avatarNotes, `${member.id}: avatar could not be fetched (${String(err)}). You can add it in the member editor.`];
        }
      }
      if (sync.status === 'live') void flushUploads(sync.device);
    } catch (err) {
      error = err instanceof Error ? err.message : String(err);
    } finally {
      busy = false;
    }
  }
</script>

<div class="panel" role="dialog" aria-label="Import from PluralKit">
  <div class="top">
    <h2>Import from PluralKit</h2>
    <button class="ghost" onclick={onclose}>Close</button>
  </div>
  <p class="muted">In Discord, run <code>pk;export</code> and download the file PluralKit sends you. Importing again later only adds what's new.</p>
  <input type="file" accept=".json,application/json" onchange={pick} aria-label="PluralKit export file" />
  {#if error}<p class="error" role="alert">{error}</p>{/if}
  {#if plan}
    <ul class="counts">
      <li><strong>{plan.members}</strong> members</li>
      <li><strong>{plan.groups}</strong> groups</li>
      <li><strong>{plan.switches}</strong> switches (imported silently — no notifications)</li>
    </ul>
    {#if notes.length}
      <details>
        <summary>{notes.length} notes</summary>
        <ul>{#each notes as w (w)}<li>{w}</li>{/each}</ul>
      </details>
    {/if}
    {#if done !== null}
      <p class="ok">Imported {done} new item{done === 1 ? '' : 's'}.{done === 0 ? ' Everything was already here.' : ''}</p>
    {/if}
    <button class="primary" onclick={() => void run()} disabled={busy}>{busy ? 'Importing avatars…' : done === null ? 'Import' : 'Import again'}</button>
  {/if}
</div>

<style>
  .panel {
    background: var(--surface);
    border: 1px solid var(--line);
    border-radius: var(--r-lg);
    padding: var(--s-5);
    display: grid;
    gap: var(--s-3);
  }
  .top {
    display: flex;
    justify-content: space-between;
    align-items: center;
  }
  h2 {
    font-size: var(--fs-lg);
  }
  .muted {
    color: var(--ink-2);
    margin: 0;
  }
  code {
    font-family: var(--font-mono);
    background: var(--surface-2);
    padding: 0 0.3em;
    border-radius: 4px;
  }
  .counts {
    margin: 0;
    padding-left: var(--s-5);
  }
  .error {
    color: var(--danger);
    margin: 0;
  }
  .ok {
    color: var(--ok);
    margin: 0;
  }
  .primary {
    justify-self: start;
    background: var(--accent);
    color: var(--surface);
    border: 0;
    border-radius: var(--r-full);
    padding: var(--s-2) var(--s-5);
    font-weight: 600;
    cursor: pointer;
  }
  .ghost {
    background: none;
    border: 0;
    color: var(--accent);
    cursor: pointer;
  }
</style>
