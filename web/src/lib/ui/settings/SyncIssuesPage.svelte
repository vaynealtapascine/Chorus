<script lang="ts">
  // Sync issues (Figma F10.4): what the server refused, why, and the text so nothing is lost.
  // Retry needs a core export the web build does not have yet; Copy text and Discard are here.
  import Button from '../kit/Button.svelte';
  import Icon from '../Icon.svelte';
  import ConfirmSheet from '../kit/ConfirmSheet.svelte';
  import EmptyState from '../kit/EmptyState.svelte';
  import PageHeader from '../kit/PageHeader.svelte';
  import { issueText, issueTitle } from '../../sync-issues';
  import { sync, type Projection, type SyncIssue } from '../../sync/client';

  let { projection }: { projection: Projection } = $props();
  const issues = $derived(projection ? sync.syncIssues() : []);
  let discarding = $state<SyncIssue | null>(null);
  const copy = (issue: SyncIssue) => void navigator.clipboard?.writeText(issueText(issue));
</script>

<section class="page">
  <PageHeader title="Sync issues" sub={issues.length ? `${issues.length} ${issues.length === 1 ? 'issue' : 'issues'}` : null} />
  {#each issues as issue (issue.id)}
    <div class="card">
      <div class="head">
        <span class="tile"><Icon name="target" size={20} /></span>
        <div class="titles"><strong>{issueTitle(issue)}</strong><span>{issue.message}</span></div>
      </div>
      {#if issueText(issue)}<blockquote>{issueText(issue)}</blockquote>{/if}
      <div class="actions">
        {#if issueText(issue)}<Button size="sm" class="pill" onclick={() => copy(issue)}><Icon name="copy" size={16} />Copy text</Button>{/if}
        <span class="grow"></span>
        <Button variant="danger-soft" size="sm" class="pill danger" onclick={() => (discarding = issue)}>Discard</Button>
      </div>
    </div>
  {:else}
    <EmptyState icon="check" label="Everything is saved" />
  {/each}
</section>

{#if discarding}
  <ConfirmSheet title="Discard this?" message="It is deleted from this device too." confirm="Discard" danger
    onconfirm={() => { sync.dismissIssue(discarding!.id); discarding = null; }} onclose={() => (discarding = null)} />
{/if}

<style>
  .page { display: grid; gap: var(--s-3); max-width: 720px; align-content: start; }
  .card { display: grid; gap: var(--s-3); padding: 14px; border-radius: var(--r-md); background: var(--surface-2); }
  .head { display: flex; align-items: center; gap: var(--s-3); }
  .tile { display: grid; place-items: center; width: 40px; height: 40px; border-radius: var(--r-row); flex: none; color: var(--danger); background: color-mix(in srgb, var(--danger) 16%, transparent); }
  .titles { display: grid; min-width: 0; }
  .titles span { color: var(--danger); font-size: var(--fs-sm); }
  blockquote { margin: 0; padding: 10px var(--s-3); border-radius: var(--r-sm); border-left: 3px solid var(--accent); background: var(--surface); white-space: pre-wrap; overflow-wrap: anywhere; max-height: 9em; overflow: hidden; }
  .actions { display: flex; gap: var(--s-2); }
  .grow { flex: 1; }
</style>
