<script lang="ts">
  // After accepting someone's request (D-129): they see what we share, not the other way round.
  // Following back is a request like any other, which they accept.
  import { api, friends } from '../friends-state.svelte';
  import Sheet from './Sheet.svelte';

  let error = $state('');
  const them = $derived(friends.followBack!);
  const close = () => (friends.followBack = null);
  async function followBack() {
    try {
      await api('/follows', { method: 'POST', body: JSON.stringify({ target: them.id }) });
      close();
      await friends.load();
    } catch { error = 'Couldn’t send the request.'; }
  }
</script>

<Sheet title="Follow {them.name} back?" subtitle="{them.name} can now see what you share with followers." onclose={close}>
  <p>You won’t see their posts or profiles until you follow back and they accept. Then you’re friends.</p>
  {#if error}<p class="fr-error" role="alert">{error}</p>{/if}
  {#snippet footer()}
    <button class="fr-wide neutral" onclick={close}>Not now</button>
    <button class="fr-wide" onclick={followBack}>Follow back</button>
  {/snippet}
</Sheet>

<style>
  p { margin: 0 var(--s-1); color: var(--ink-2); font-size: 14px; line-height: 20px; }
</style>
