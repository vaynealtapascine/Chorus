<script lang="ts">
  // Change who can read an existing post (`post.set_visibility`, queued offline like any op), as on
  // Android: widening says so before the change is written; narrowing needs no warning.
  import Button from './kit/Button.svelte';
  import { buckets as bucketRows } from '../data';
  import { possiblyWidens, sameAudience, type Audience } from '../profile-audience';
  import { sync, type Projection } from '../sync/client';
  import AudienceChip from './AudienceChip.svelte';

  let { projection, postId, current, onclose }: { projection: Projection; postId: string; current: Audience; onclose: () => void } = $props();
  const buckets = $derived(bucketRows(projection).map((b) => ({ id: b.id, name: b.name, icon: b.icon })));
  // svelte-ignore state_referenced_locally
  let choice = $state<Audience>(current);
  let error = $state('');
  let dialog = $state<HTMLDialogElement | undefined>();
  const widening = $derived(possiblyWidens(choice, current));
  const live = $derived(new Set(buckets.map((b) => b.id)));
  $effect(() => { dialog?.showModal(); });

  function save() {
    if (choice.mode === 'buckets' && (!choice.bucket_ids.length || choice.bucket_ids.some((id) => !live.has(id)))) {
      error = 'Choose existing buckets before sharing.';
      return;
    }
    try {
      sync.create('post.set_visibility', sync.accountScope, postId, { visibility: choice });
      dialog?.close();
    } catch { error = 'Could not change the audience. Try again.'; }
  }
</script>

<dialog bind:this={dialog} class="audience-dialog" aria-labelledby="audience-{postId}" onclose={onclose}>
  <h2 id="audience-{postId}">Who can see this post?</h2>
  <div class="row"><span>Shared with</span><AudienceChip label="This post" value={choice} own {buckets} onchange={(a) => { choice = a ?? { mode: 'private' }; error = ''; }} /></div>
  {#if widening}<p class="note">More people will be able to read this post.</p>{/if}
  {#if error}<p class="error" role="alert">{error}</p>{/if}
  <div class="dialog-actions">
    <Button size="sm" variant="quiet" onclick={() => dialog?.close()}>Cancel</Button>
    <Button size="sm" variant="primary" disabled={sameAudience(choice, current)} onclick={save}>{widening ? 'Make visible' : 'Change audience'}</Button>
  </div>
</dialog>

<style>
  .audience-dialog { width: min(380px, 90vw); border: 1px solid var(--line); border-radius: var(--r-md); background: var(--surface); color: var(--ink); padding: var(--s-5); overflow: visible; }
  .audience-dialog::backdrop { background: var(--scrim); }
  h2 { margin: 0 0 var(--s-3); font-size: var(--fs-lg); }
  /* the chip sits right so its right-aligned menu floats below the dialog like a popup */
  .row { display: flex; align-items: center; justify-content: space-between; gap: var(--s-3); color: var(--ink-2); font-size: var(--fs-sm); }

  .note, .error { margin: 10px 0 0; font-size: var(--fs-sm); color: var(--ink-2); }
  .error { color: var(--danger); }
  .dialog-actions { display: flex; justify-content: flex-end; gap: var(--s-2); margin-top: var(--s-4); }
</style>
