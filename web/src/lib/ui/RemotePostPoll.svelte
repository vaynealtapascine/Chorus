<script lang="ts">
  import { parsePoll, type PollTally } from '../polls';
  import type { Projection } from '../sync/client';
  import PollCard from './PollCard.svelte';

  // Foreign parents/totals arrive through audience-filtered REST; selections stay account-local.
  let { projection, post, member }: {
    projection: Projection; post: { id: string; poll?: unknown; poll_tally?: PollTally | null };
    member: string | null | undefined;
  } = $props();
  const poll = $derived(parsePoll(post.poll));
</script>

{#if poll}<PollCard {projection} parentId={post.id} {poll} {member} remoteTally={post.poll_tally} />{/if}
