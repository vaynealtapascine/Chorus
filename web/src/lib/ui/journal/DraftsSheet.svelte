<script lang="ts">
  // F6.2 "Drafts" (Android DraftsDrawer): the drafts kept for later; one opens in the composer (what it
  // held goes on the shelf first), the bin discards after asking, New draft shelves the current one.
  import Icon from '../Icon.svelte';
  import Sheet from '../Sheet.svelte';
  import Button from '../kit/Button.svelte';
  import ConfirmSheet from '../kit/ConfirmSheet.svelte';
  import EmptyState from '../kit/EmptyState.svelte';
  import { discardShelved, draftHeadline, shelf } from '../../journal-drafts';
  import type { Composer } from '../../journal-composer.svelte';

  let { c, onclose }: { c: Composer; onclose: () => void } = $props();
  let version = $state(0);
  const rows = $derived.by(() => { void version; return shelf(c.accountId); });
  let confirm = $state<string | null>(null);
  const when = (at: number) => new Date(at).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
</script>

<Sheet title="Drafts" {onclose}>
  {#each rows as row (row.id)}
    {@const h = draftHeadline(row.draft)}
    <div class="row">
      <button type="button" class="open" disabled={c.busy} onclick={() => { c.unshelve(row.id); onclose(); }}>
        <strong>{h.head}</strong><span>{h.meta} · {when(row.savedAt)}</span>
      </button>
      <button type="button" class="bin" aria-label="Discard draft" title="Discard draft" onclick={() => (confirm = row.id)}><Icon name="delete" size={20} /></button>
    </div>
  {:else}<EmptyState icon="pencil" label="No other drafts" />{/each}
  {#snippet footer()}{#if c.hasContent && !c.busy}<Button variant="neutral" grow onclick={() => { c.shelve(); version++; }}>New draft</Button>{/if}{/snippet}
</Sheet>
{#if confirm}
  <ConfirmSheet title="Discard this draft?" confirm="Discard" danger layer={1} onclose={() => (confirm = null)}
    onconfirm={() => { discardShelved(c.accountId, confirm!); confirm = null; version++; }} />
{/if}

<style>
  .row { display: flex; align-items: center; gap: var(--s-1); border-radius: var(--r-row); background: var(--surface-2); }
  .open { flex: 1; min-width: 0; display: grid; gap: 2px; padding: 10px 0 10px 14px; border: 0; background: none; color: var(--ink); font: inherit; text-align: left; cursor: pointer; }
  .open strong { font-size: var(--fs-md); font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .open span { color: var(--ink-3); font-size: var(--fs-xs); }
  .bin { display: grid; place-items: center; width: 44px; height: 44px; flex: none; border: 0; border-radius: 50%; background: none; color: var(--ink-3); cursor: pointer; }
</style>
