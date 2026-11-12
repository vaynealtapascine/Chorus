<script lang="ts">
  // Everyone else (the account default `follow_ceiling`, NOTIFICATIONS §3): how much followers in no
  // bucket hear, what every follower may also see, and every detail buckets inherit (F7.8).
  import FormNote from './kit/FormNote.svelte';
  import SectionLabel from './kit/SectionLabel.svelte';
  import Switch from './kit/Switch.svelte';
  import '../design/friends.css';
  import { accountDefault, presetCeiling } from '../follow-presets';
  import { everyoneElse, PRESET_IDS, presetAbout, presetName } from '../friends-screens';
  import { presetOf } from '../follow-presets';
  import { sync, type Projection } from '../sync/client';
  import CeilingDetails from './CeilingDetails.svelte';
  import Icon from './Icon.svelte';

  let { projection }: { projection: Projection } = $props();
  const ceiling = $derived(accountDefault(projection));
  const choice = $derived(everyoneElse(ceiling, presetOf));
  const save = (value: Record<string, unknown>) => sync.create('pref.set', sync.accountScope, null, { device: '', key: 'follow_ceiling', value });
  const on = (key: string, fallback: boolean) => (Object.hasOwn(ceiling, key) ? ceiling[key] === true : fallback);
</script>

<section class="fr-page">
  <header class="fr-bar">
    <a class="fr-icon ink" href="#/people" aria-label="Back"><Icon name="back" /></a>
    <div class="fr-titles"><strong>Advanced sharing</strong><span>Everyone else: friends in no bucket</span></div>
  </header>
  <SectionLabel inset label="How much everyone else hears" />
  <div class="fr-seg" role="group" aria-label="How much everyone else hears">
    {#each PRESET_IDS as p (p)}<button aria-pressed={choice === p} onclick={() => save(presetCeiling(p, ceiling))}>{presetName(p)}</button>{/each}
  </div>
  <p class="fr-about">{presetAbout(choice)}</p>
  <SectionLabel inset label="Also share with every follower" />
  {#each [['share_current_front', 'Who’s here now', 'On their Friends tab and our profile', true], ['share_history', 'Front history', 'Last 30 days, only what they were told, times as shared', false], ['share_stats', 'Fronting stats', 'Each member’s share, whole days only', false]] as [key, title, sub, fallback] (key)}
    {@const v = on(key as string, fallback as boolean)}
    <Switch checked={v} label={title as string} sub={sub as string} onchange={(next) => save({ ...ceiling, [key as string]: next })} />
  {/each}
  <SectionLabel inset label="Details" aside="buckets inherit these" />
  <CeilingDetails {projection} {ceiling} inherited="Chorus’s default" onchange={save} />
  <FormNote quiet inset icon="shield" text="Buckets and each friend’s own settings can share more or less than this. A friend in several buckets gets the most open one." />
</section>
