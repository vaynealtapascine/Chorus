<script lang="ts">
  // A new system's first minutes (F1.2-F1.4, SPEC 3.1 step 3; Android's ui/SystemSetup.kt): the
  // words it uses, who's in it (or Import from PluralKit), and who's steering now. Every step can be
  // left with Continue as it is; `ondone` ends setup.
  import { sync, type Projection } from '../sync/client';
  import { ownMembers } from '../fronting-subjects';
  import { doSwitch } from '../front.svelte';
  import { appearance } from '../appearance.svelte';
  import { cap, customFront, customSystem, FRONT_CHOICES, parseWords, SYSTEM_CHOICES, wordsPayload, type Words } from '../words';
  import Face from './Face.svelte';
  import Icon from './Icon.svelte';
  import Sheet from './Sheet.svelte';
  import PkImport from './PkImport.svelte';
  import Wordmark from './kit/Wordmark.svelte';
  import WelcomeBlobs from './kit/WelcomeBlobs.svelte';

  let { projection, name, ondone }: { projection: Projection; name: string; ondone: () => void } = $props();

  const stored = $derived(projection.rows.system?.[sync.accountId]?.fields);
  const current = $derived(parseWords(stored?.terminology as string | undefined));
  let step = $state(0);
  let words = $state<Words | null>(null);
  const shown = $derived(words ?? current);
  let chosen = $state<string[]>([]);
  let error = $state('');
  let custom = $state<0 | 1 | null>(null);
  let typed = $state('');
  let adding = $state(false);
  let newName = $state('');
  let importing = $state(false);
  const people = $derived(ownMembers(projection));
  const swatches = ['#C0694E', '#7AA99D', '#ACA2D0', '#E0AE20', '#8DBE2B', '#D98FA8'];

  function next() {
    error = '';
    try {
      if (step === 0) {
        // the name typed at welcome becomes the system's name (it went to the account's display name)
        const patch: Record<string, unknown> = {};
        if (words && (words.system !== current.system || words.fronting !== current.fronting || words.systems !== current.systems || words.front !== current.front)) {
          patch.terminology = wordsPayload(stored?.terminology as string | undefined, words);
        }
        if (!String(stored?.name ?? '').trim() && name.trim()) patch.name = name.trim();
        if (Object.keys(patch).length) sync.create('system.set', sync.accountScope, sync.accountId, patch);
        step = 1;
      } else if (step === 1) {
        if (people.length) step = 2; else ondone();
      } else {
        if (chosen.length) {
          const entries = chosen.map((id, i) => ({ subject_type: 'member' as const, subject_id: id, level: 'front' as const, is_primary: i === 0 }));
          doSwitch(entries, `Switched to ${chosen.map((id) => people.find((m) => m.id === id)?.name).filter(Boolean).join(', ')}`);
        }
        ondone();
      }
    } catch (e) {
      error = e instanceof Error ? e.message : 'Could not save that.';
    }
  }

  function useCustom() {
    if (custom === 0) {
      const w = customSystem(typed);
      if (w) words = { ...shown, system: w[0], systems: w[1] };
    } else {
      const w = customFront(typed);
      if (w) words = { ...shown, front: w[1], fronting: w[0] };
    }
    custom = null;
    typed = '';
  }

  function addMember() {
    const n = newName.trim();
    if (!n) return;
    try {
      sync.create('member.create', sync.accountScope, sync.newId(), { name: n, color: swatches[people.length % swatches.length] });
      adding = false;
      newName = '';
    } catch (e) {
      error = e instanceof Error ? e.message : 'Could not add them.';
      adding = false;
    }
  }

  const pick = (id: string) => { chosen = chosen.includes(id) ? chosen.filter((x) => x !== id) : [...chosen, id]; };
  const extraSystem = $derived(SYSTEM_CHOICES.some(([w]) => w === shown.system) ? null : shown.system);
  const extraFront = $derived(FRONT_CHOICES.some(([w]) => w === shown.fronting) ? null : shown.fronting);
</script>

<WelcomeBlobs />
<main aria-label="Setup">
  <h1 class="art">
    <Wordmark />
    {#if step === 0}<span class="line">VOICE FOR THE MANY</span>
    {:else}<span class="line question">Who’s {#if step === 1}in the <b>{shown.system}</b>{:else}<b>{shown.fronting}</b>{/if}?</span>{/if}
  </h1>

  {#if step === 0}
    <div class="words">
      <p class="caps">We call ourselves</p>
      <div class="chips" role="radiogroup" aria-label="We call ourselves">
        {#each SYSTEM_CHOICES as [one, many] (one)}
          <button class="chip" class:on={shown.system === one} role="radio" aria-checked={shown.system === one}
            onclick={() => (words = { ...shown, system: one, systems: many })}>{#if shown.system === one}<Icon name="check" size={16} />{/if}{cap(one)}</button>
        {/each}
        {#if extraSystem}<button class="chip on mine" role="radio" aria-checked="true"><Icon name="check" size={16} />{cap(extraSystem)}</button>{/if}
        <button class="chip add" onclick={() => (custom = 0)}><Icon name="plus" size={15} />Custom…</button>
      </div>
      <p class="caps second">When someone is in control, they are</p>
      <div class="chips" role="radiogroup" aria-label="When someone is in control, they are">
        {#each FRONT_CHOICES as [ing, noun] (ing)}
          <button class="chip" class:on={shown.fronting === ing} role="radio" aria-checked={shown.fronting === ing}
            onclick={() => (words = { ...shown, front: noun, fronting: ing })}>{#if shown.fronting === ing}<Icon name="check" size={16} />{/if}{cap(ing)}</button>
        {/each}
        {#if extraFront}<button class="chip on mine" role="radio" aria-checked="true"><Icon name="check" size={16} />{cap(extraFront)}</button>{/if}
        <button class="chip add" onclick={() => (custom = 1)}><Icon name="plus" size={15} />Custom…</button>
      </div>
    </div>
  {:else}
    <div class="grid" role={step === 2 ? 'group' : undefined} aria-label={step === 2 ? 'Who is steering' : undefined}>
      {#each people as m (m.id)}
        {@const picked = chosen.includes(m.id)}
        <button class="card" class:picked disabled={step !== 2} aria-pressed={step === 2 ? picked : undefined} onclick={() => pick(m.id)}>
          <Face who={m} size={48} dark={appearance.dark} />
          <strong>{m.name}</strong>
          {#if m.pronouns}<span class="sub">{m.pronouns}</span>{/if}
        </button>
      {/each}
      {#if step === 1}
        <button class="card dashed" onclick={() => (adding = true)}><span class="disc"><Icon name="plus" size={22} /></span><em>new</em></button>
        <button class="card dashed" onclick={() => (importing = true)}><span class="disc"><Icon name="forward" size={22} /></span><em>PluralKit</em></button>
      {/if}
    </div>
  {/if}

  {#if error}<p class="error" role="alert">{error}</p>{/if}
  <div class="buttons">
    {#if step > 0}<button class="back" onclick={() => (step -= 1)}><Icon name="back" size={16} />Back</button>{/if}
    <button class="go" onclick={next}>Continue<Icon name="chevron" size={16} /></button>
  </div>
</main>

{#if custom !== null}
  <Sheet title={custom === 0 ? 'We call ourselves' : 'When someone is in control, they are'} onclose={() => (custom = null)}>
    <!-- svelte-ignore a11y_autofocus -->
    <input class="word" bind:value={typed} maxlength="24" autofocus aria-label="Word" onkeydown={(e) => e.key === 'Enter' && typed.trim() && useCustom()} />
    {#snippet footer()}<div class="sheet-buttons"><button class="use" disabled={!typed.trim()} onclick={useCustom}>Use this word</button></div>{/snippet}
  </Sheet>
{/if}
{#if adding}
  <Sheet title="New member" onclose={() => (adding = false)}>
    <!-- svelte-ignore a11y_autofocus -->
    <input class="word" bind:value={newName} maxlength="100" autofocus aria-label="Name" placeholder="Name" onkeydown={(e) => e.key === 'Enter' && addMember()} />
    {#snippet footer()}<div class="sheet-buttons"><button class="use" disabled={!newName.trim()} onclick={addMember}>Add</button></div>{/snippet}
  </Sheet>
{/if}
{#if importing}<PkImport onclose={() => (importing = false)} />{/if}

<style>
  main {
    --cream: #fbf3e5; --ink: #1f5e57; --sun: #f0bd1b;
    position: relative; z-index: 1; min-height: 100dvh; box-sizing: border-box; max-width: 460px; margin: 0 auto;
    display: flex; flex-direction: column; align-items: center; justify-content: space-between; gap: 20px;
    padding: 40px 38px 28px; color: var(--cream);
  }
  .art { display: grid; justify-items: center; gap: 4px; margin: 0; font: inherit; width: min(272px, 100%); }
  .line { font-weight: 700; font-size: 15px; letter-spacing: 1px; }
  .line.question { letter-spacing: 0; }
  .line b { color: var(--sun); font-weight: 700; }
  .words { width: 100%; display: grid; gap: 8px; }
  .caps { margin: 0; font-size: 10px; font-weight: 700; letter-spacing: 0.4px; text-transform: uppercase; }
  .second { margin-top: 10px; }
  .chips { display: flex; flex-wrap: wrap; gap: 6px; }
  .chip { display: inline-flex; align-items: center; gap: 5px; min-height: 30px; padding: 4px 10px; border-radius: 15px; border: 2px solid var(--cream); background: none; color: var(--cream); font: inherit; font-size: 15px; font-weight: 500; cursor: pointer; }
  .chip.on, .chip.mine { background: var(--cream); color: var(--ink); }
  .chip.on :global(.icon) { color: #3da599; }
  .chip.add { font-style: italic; }
  .grid { width: 100%; flex: 1; overflow-y: auto; display: grid; grid-template-columns: repeat(3, 1fr); grid-auto-rows: 118px; gap: 8px; align-content: start; padding: 4px 0 24px; }
  .card { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 4px; padding: 8px; border: 3px solid transparent; border-radius: 16px; background: var(--cream); color: #1c1b19; font: inherit; cursor: pointer; min-width: 0; }
  .card:disabled { cursor: default; opacity: 1; }
  .card.picked { border-color: var(--ink); }
  .card strong { font-size: 17px; max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .sub { font-size: 9px; font-weight: 700; letter-spacing: 0.3px; text-transform: uppercase; color: #8a847a; max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .card.dashed { background: none; border: 2px dashed var(--ink); color: var(--cream); gap: 8px; }
  .disc { display: grid; place-items: center; width: 48px; height: 48px; border-radius: 50%; background: var(--cream); color: var(--ink); }
  .card.dashed em { font-size: 16px; font-weight: 500; }
  .error { margin: 0; padding: 8px 12px; border-radius: 12px; background: var(--cream); color: #9e2a1e; font-size: 13px; }
  .buttons { width: 100%; display: flex; align-items: center; justify-content: flex-end; gap: 12px; }
  .back { margin-right: auto; display: flex; align-items: center; gap: 8px; padding: 9px 16px; border: 0; background: none; color: var(--cream); font: inherit; font-size: 14px; font-weight: 700; letter-spacing: 0.3px; text-transform: uppercase; cursor: pointer; }
  .go { display: flex; align-items: center; gap: 8px; min-height: 38px; padding: 9px 16px; border: 0; border-radius: 19px; background: var(--cream); color: var(--ink); font: inherit; font-size: 14px; font-weight: 700; letter-spacing: 0.3px; text-transform: uppercase; cursor: pointer; }
  .word { font: inherit; font-size: 16px; padding: 10px 12px; border-radius: 10px; border: 1px solid var(--line); background: var(--surface-2); color: var(--ink-1, inherit); }
  .sheet-buttons { padding: 0 16px 16px; display: grid; }
  .use { min-height: 44px; border: 0; border-radius: 22px; background: var(--accent); color: var(--on-accent); font: inherit; font-weight: 600; cursor: pointer; }
  .use:disabled { opacity: 0.6; }
</style>
