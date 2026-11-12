<script lang="ts">
  // Who can see one thing, as a menu sheet (Android AudienceMenu): follow the default, Only us, each
  // bucket, Friends, Followers, Following, Everyone here. Choosing closes it.
  import type { BucketRow } from '../data';
  import type { Audience } from '../profile-audience';
  import BucketGlyph from './BucketGlyph.svelte';
  import MenuRow from './kit/MenuRow.svelte';
  import MenuSheet from './kit/MenuSheet.svelte';

  let { title, value, own, inherit, buckets, onpick, onclose, layer = 0 }: {
    title: string; value: Audience; own: boolean; inherit?: string; buckets: BucketRow[];
    onpick: (a: Audience | null) => void; onclose: () => void; layer?: number;
  } = $props();
  const chosen = $derived(own || !inherit);
  const pick = (a: Audience | null) => { onpick(a); onclose(); };
</script>

<MenuSheet {title} {layer} {onclose}>
  {#if inherit}<MenuRow label={inherit} selected={!own} onclick={() => pick(null)} />{/if}
  <MenuRow label="Only us" icon="lock" selected={chosen && value.mode === 'private'} onclick={() => pick({ mode: 'private' })} />
  {#each buckets as b (b.id)}
    <MenuRow label={b.name} selected={chosen && value.mode === 'buckets' && value.bucket_ids.length === 1 && value.bucket_ids[0] === b.id}
      onclick={() => pick({ mode: 'buckets', bucket_ids: [b.id] })}>
      {#snippet leading()}<BucketGlyph icon={b.icon} color={b.color} size={22} />{/snippet}
    </MenuRow>
  {/each}
  <MenuRow label="Friends" icon="users" selected={chosen && value.mode === 'friends'} onclick={() => pick({ mode: 'friends' })} />
  <MenuRow label="Followers" icon="users" selected={chosen && value.mode === 'followers'} onclick={() => pick({ mode: 'followers' })} />
  <MenuRow label="Following" icon="users" selected={chosen && value.mode === 'following'} onclick={() => pick({ mode: 'following' })} />
  <MenuRow label="Everyone here" icon="globe" selected={chosen && value.mode === 'server'} onclick={() => pick({ mode: 'server' })} />
</MenuSheet>
