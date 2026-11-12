<script lang="ts">
  // A poll on a message or post (D-081; F6.10). Totals are the server's counts-only tally; the voter's
  // own choice shows at once. Results show once the voter has chosen (or it closed, or no one votes
  // here). Internal polls (our own system's posts) say how many of our members voted and let the
  // voter be changed. Holding an option (or a right click) chooses which of our members pick it, all
  // at once (D-142). `onclose` is the author's early close (D-141).
  import { closedAt, closingLabel, ownChoices, percent, tallyOf, toggled, vote, votedCount, type Poll, type PollTally } from '../polls';
  import { sync, type Projection } from '../sync/client';
  import { longPress } from '../long-press';
  import { actors, faceOf, frontingIds, isDark, votesFor, type MemberChange } from '../many-members';
  import ManyMembersSheet from './ManyMembersSheet.svelte';
  import MenuSheet from './kit/MenuSheet.svelte';
  import MenuRow from './kit/MenuRow.svelte';
  import ConfirmSheet from './kit/ConfirmSheet.svelte';
  import Face from './Face.svelte';
  import Icon from './Icon.svelte';

  let { projection, parentId, poll, member, remoteTally = null, remoteClosedAt = null, internal = false, onclose }: {
    projection: Projection; parentId: string; poll: Poll; member: string | null | undefined; remoteTally?: PollTally | null;
    remoteClosedAt?: number | null; internal?: boolean; onclose?: () => void;
  } = $props();

  // D-136: once closed (by the device clock, for display; the server decides) the result is final
  const closesAt = $derived(closedAt(projection, parentId, poll, remoteClosedAt));
  let now = $state(Date.now());
  $effect(() => {
    if (closesAt == null || now >= closesAt) return;
    const t = setTimeout(() => (now = Date.now()), Math.min(30_000, Math.max(1_000, closesAt - now)));
    return () => clearTimeout(t);
  });
  const closed = $derived(closesAt != null && now >= closesAt);

  const tally = $derived(tallyOf(projection, parentId) ?? remoteTally);
  const ours = $derived(actors(projection, sync.accountId));
  // who is voting: the acting member, unless the voter picks another of ours
  let picked = $state<string | null>(null);
  let changing = $state(false);
  let confirmClose = $state(false);
  const voter = $derived((internal ? ours.find((m) => m.id === picked)?.id : null) ?? member);
  const who = $derived(ours.find((m) => m.id === voter));
  const mine = $derived(ownChoices(projection, parentId, voter));
  const voters = $derived(Math.max(1, tally?.voters ?? 0));
  const showResults = $derived(!!tally && (mine.length > 0 || !voter || closed));
  const pick = (option: string) => { if (voter && !closed) vote(parentId, voter, toggled(poll, mine, option)); };

  const canHold = $derived(voter && !closed ? ours : []);
  let holding = $state<string | null>(null);
  const held = $derived(poll.options.find((o) => o.id === holding));
  const choicesOf = (id: string) => ownChoices(projection, parentId, id);
  const hadFor = (option: string) => new Set(canHold.filter((m) => choicesOf(m.id).includes(option)).map((m) => m.id));
  function hold(option: string, e?: Event) {
    if (canHold.length < 2) return;
    e?.preventDefault();
    holding = option;
  }
  function applyMany(option: string, change: MemberChange) {
    holding = null;
    const chosen = new Set([...hadFor(option), ...change.add].filter((id) => !change.remove.includes(id)));
    for (const [id, choices] of votesFor(poll, choicesOf, canHold.map((m) => m.id), option, chosen)) vote(parentId, id, choices);
  }
  const otherChoices = (id: string, option: string) => {
    const rest = choicesOf(id).filter((c) => c !== option).map((c) => poll.options.find((o) => o.id === c)?.text).filter(Boolean);
    return rest.length ? `Voted ${rest.join(', ')}` : null;
  };
  const line = $derived(internal && ours.length > 1 ? `${votedCount(projection, parentId, ours.map((m) => m.id))} of ${ours.length} voted`
    : tally ? `${tally.voters} ${tally.voters === 1 ? 'vote' : 'votes'}` : '');
  const closing = $derived(closesAt != null ? closingLabel(closesAt, now) : '');
  const foot = $derived(line && closing ? `${line} · ${closing[0].toLowerCase()}${closing.slice(1)}` : line || closing);
  const dark = isDark();
</script>

<div class="poll" role="group" aria-label={poll.question ?? 'Poll'}>
  {#if poll.question}<p class="question">{poll.question}</p>{/if}
  {#if internal && ours.length > 1 && who}
    <button type="button" class="voting-as" onclick={() => (changing = true)}><Face who={faceOf(who)} size={24} {dark} />Voting as {who.display_name ?? who.name}<span>Change</span></button>
  {/if}
  <ul>
    {#each poll.options as option (option.id)}
      {@const n = tally?.counts[option.id] ?? 0}
      {@const on = mine.includes(option.id)}
      <li>
        <button type="button" class:on class:plain={!showResults} aria-pressed={on} disabled={!voter || closed} onclick={() => pick(option.id)}
          oncontextmenu={(e) => hold(option.id, e)} use:longPress={{ enabled: canHold.length > 1, onpress: () => hold(option.id) }}>
          {#if showResults}<span class="bar" style="width: {(n / voters) * 100}%"></span>{/if}
          {#if showResults && on}<span class="check"><Icon name="check" size={18} /></span>{/if}
          <span class="text">{option.text}</span>
          {#if showResults}<span class="count">{percent(n, voters)}%</span>{/if}
        </button>
      </li>
    {/each}
  </ul>
  {#if foot || (onclose && !closed)}
    <p class="foot"><span>{foot}</span>{#if onclose && !closed}<button type="button" class="link" onclick={() => (confirmClose = true)}>Close poll</button>{/if}</p>
  {/if}
</div>

{#if changing}
  <MenuSheet title="Vote as" onclose={() => (changing = false)}>
    {#each ours as m (m.id)}
      <MenuRow label={m.display_name ?? m.name} selected={m.id === voter} onclick={() => { picked = m.id; changing = false; }}>
        {#snippet leading()}<Face who={faceOf(m)} size={36} {dark} />{/snippet}
      </MenuRow>
    {/each}
  </MenuSheet>
{/if}
{#if confirmClose && onclose}
  <ConfirmSheet title="Close this poll?" confirm="Close poll" icon="poll" onconfirm={() => { confirmClose = false; onclose?.(); }} onclose={() => (confirmClose = false)} />
{/if}
{#if held}
  <ManyMembersSheet title="Who picks “{held.text}”?" subtitle={poll.multiple ? 'Their other choices stay' : 'Moves each vote here'}
    members={canHold} had={hadFor(held.id)} fronting={frontingIds(projection, sync.accountId)} line={(id) => otherChoices(id, held.id)}
    onclose={() => (holding = null)} onapply={(change) => applyMany(held.id, change)} />
{/if}

<style>
  .poll { display: grid; gap: var(--s-2); margin-top: var(--s-2); max-width: 32rem; }
  .question { margin: 0; color: var(--ink); font-weight: 600; }
  .voting-as { justify-self: start; display: inline-flex; align-items: center; gap: 8px; padding: 4px 12px 4px 4px; border: 0; border-radius: var(--r-full); background: var(--surface-2); color: var(--ink); font: inherit; font-size: 13px; font-weight: 500; cursor: pointer; }
  .voting-as span { color: var(--accent); }
  .foot { margin: 0; color: var(--ink-3); font-size: var(--fs-xs); display: flex; gap: var(--s-2); align-items: center; justify-content: space-between; }
  ul { list-style: none; margin: 0; padding: 0; display: grid; gap: var(--s-2); }
  li button {
    position: relative; overflow: hidden; width: 100%; min-height: 48px; display: flex; align-items: center; gap: 10px;
    padding: 12px 14px; border: 1px solid var(--line); border-radius: 12px;
    background: var(--surface); color: var(--ink); font: inherit; font-weight: 500; text-align: left; cursor: pointer;
  }
  li button.plain { border-color: color-mix(in srgb, var(--accent) 60%, transparent); color: var(--accent); justify-content: center; text-align: center; }
  li button:disabled { cursor: default; }
  li button.on { border-color: var(--accent); }
  .bar { position: absolute; inset: 0 auto 0 0; background: var(--accent-soft); pointer-events: none; }
  .text, .count, .check { position: relative; }
  .check { color: var(--accent); display: grid; }
  .text { flex: 1; }
  .plain .text { flex: none; }
  .count { color: var(--ink); font-size: 14px; font-variant-numeric: tabular-nums; }
  .link { background: none; border: 0; padding: 6px 8px; color: var(--accent); cursor: pointer; font: inherit; font-weight: 500; }
</style>
