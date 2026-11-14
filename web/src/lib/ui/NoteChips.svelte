<script lang="ts">
  // Notes' tag chips (F5.19): All, the #tags most used first, and #from chats (messages saved from other chats).
  import Chip from './kit/Chip.svelte';
  import { FROM_CHATS } from '../special';

  let { chips, fromChats, value = $bindable('') }: { chips: { tag: string; count: number }[]; fromChats: boolean; value?: string } = $props();
  const pick = (v: string) => (value = value === v ? '' : v);
</script>

<div class="tags" role="group" aria-label="Tags">
  <Chip label="All" on={!value} onclick={() => (value = '')} />
  {#each chips as c (c.tag)}<Chip label="#{c.tag}" on={value === c.tag} onclick={() => pick(c.tag)} />{/each}
  {#if fromChats}<Chip label={FROM_CHATS} on={value === FROM_CHATS} onclick={() => pick(FROM_CHATS)} />{/if}
</div>

<style>
  .tags { display: flex; gap: var(--s-2); overflow-x: auto; padding: var(--s-2) var(--s-3); scrollbar-width: none; border-bottom: 1px solid var(--line); }
</style>
