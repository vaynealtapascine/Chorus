<script lang="ts">
  // A poll on a message or post (D-081). Totals are the server's counts-only tally; the acting
  // member's own choice shows at once, before the next tally arrives.
  import { ownChoices, tallyOf, toggled, vote, type Poll, type PollTally } from '../polls';
  import type { Projection } from '../sync/client';

  let { projection, parentId, poll, member, remoteTally = null }: {
    projection: Projection; parentId: string; poll: Poll; member: string | null | undefined; remoteTally?: PollTally | null;
  } = $props();

  const tally = $derived(tallyOf(projection, parentId) ?? remoteTally);
  const mine = $derived(ownChoices(projection, parentId, member));
  const most = $derived(Math.max(1, ...Object.values(tally?.counts ?? {})));
  const pick = (option: string) => { if (member) vote(parentId, member, toggled(poll, mine, option)); };
</script>

<div class="poll" role="group" aria-label={poll.question ?? 'Poll'}>
  {#if poll.question}<p class="question">{poll.question}</p>{/if}
  <p class="kind">{poll.multiple ? 'Choose any' : 'Choose one'}</p>
  <ul>
    {#each poll.options as option (option.id)}
      {@const n = tally?.counts[option.id] ?? 0}
      <li>
        <button type="button" class:on={mine.includes(option.id)} aria-pressed={mine.includes(option.id)} disabled={!member} onclick={() => pick(option.id)}>
          <span class="bar" style="width: {(n / most) * 100}%"></span>
          <span class="text">{option.text}</span>
          <span class="count">{tally ? n : ''}</span>
        </button>
      </li>
    {/each}
  </ul>
  <p class="foot">
    {#if tally}{tally.voters} {tally.voters === 1 ? 'vote' : 'votes'}{:else}No totals yet{/if}
    {#if !member} · Pick who is speaking to vote{:else if mine.length}<button type="button" class="link" onclick={() => member && vote(parentId, member, [])}>Remove my vote</button>{/if}
  </p>
</div>

<style>
  .poll { display: grid; gap: var(--s-2); margin-top: var(--s-2); max-width: 32rem; }
  .question { margin: 0; color: var(--ink); font-weight: 600; }
  .kind, .foot { margin: 0; color: var(--ink-3); font-size: var(--fs-xs); display: flex; gap: var(--s-2); align-items: center; }
  ul { list-style: none; margin: 0; padding: 0; display: grid; gap: var(--s-1); }
  li button {
    position: relative; overflow: hidden; width: 100%; min-height: 40px; display: flex; align-items: center; gap: var(--s-2);
    padding: var(--s-1) var(--s-3); border: 1px solid var(--line); border-radius: var(--r-sm);
    background: var(--surface); color: var(--ink); text-align: left; cursor: pointer;
  }
  li button:disabled { cursor: default; }
  li button.on { border-color: var(--accent); }
  .bar { position: absolute; inset: 0 auto 0 0; background: var(--accent-soft); pointer-events: none; }
  .text, .count { position: relative; }
  .text { flex: 1; }
  .count { color: var(--ink-2); font-size: var(--fs-sm); font-variant-numeric: tabular-nums; }
  .link { background: none; border: 0; padding: 0; color: var(--accent); cursor: pointer; font-size: inherit; }
</style>
