<script module lang="ts">
  export interface SavedRow { id: string; name: string; summary: string; icon: string }
</script>

<script lang="ts">
  // F8.9: name this stage and save it; the saved ones below reopen with a tap. Saving under a name
  // already used here replaces that stage.
  import TextField from '../kit/TextField.svelte';
  import Button from '../kit/Button.svelte';
  import Icon from '../Icon.svelte';
  import Sheet from '../Sheet.svelte';
  import FormCard from '../kit/FormCard.svelte';
  import MenuRow from '../kit/MenuRow.svelte';
  import MenuSheet from '../kit/MenuSheet.svelte';

  let { saved, onsave, onopen, ondelete, onclose }: {
    saved: SavedRow[]; onsave: (name: string) => void; onopen: (id: string) => void; ondelete: (id: string) => void; onclose: () => void;
  } = $props();
  let name = $state('');
  let menu = $state<SavedRow | null>(null);
</script>

<Sheet title="Save this stage" subtitle="Saves the setup, not a copy of the messages. Deleted messages drop out." {onclose} layer={3}>
  <label class="name"><span>Name</span><TextField outlined tall bind:value={name} onkeydown={(e) => e.key === 'Enter' && name.trim() && onsave(name.trim())} /></label>
  {#if saved.length}
    <h3>Saved stages</h3>
    <FormCard>
      {#each saved as row (row.id)}
        <div class="saved">
          <button class="open" onclick={() => onopen(row.id)}>
            <Icon name={row.icon} size={20} />
            <span class="text"><strong>{row.name}</strong><span>{row.summary}</span></span>
          </button>
          <button class="more" aria-label="Options for {row.name}" onclick={() => (menu = row)}><Icon name="more-vert" size={20} /></button>
        </div>
      {/each}
    </FormCard>
  {/if}
  {#snippet footer()}
    <Button size="lg" round grow class="neutral" onclick={onclose}>Cancel</Button>
    <Button variant="primary" size="lg" round grow class="primary" disabled={!name.trim()} onclick={() => onsave(name.trim())}>Save stage</Button>
  {/snippet}
</Sheet>
{#if menu}
  <MenuSheet title={menu.name} layer={4} onclose={() => (menu = null)}>
    <MenuRow label="Open" icon="chevron-right" onclick={() => { const id = menu!.id; menu = null; onopen(id); }} />
    <MenuRow label="Delete" icon="delete" danger onclick={() => { const id = menu!.id; menu = null; ondelete(id); }} />
  </MenuSheet>
{/if}

<style>
  .name { display: grid; gap: var(--s-1); color: var(--ink-3); font-size: var(--fs-sm); }
  h3 { margin: var(--s-1) var(--s-1) 0; color: var(--ink-3); font-size: var(--fs-sm); font-weight: 500; }
  .saved { display: flex; align-items: center; min-height: 56px; }
  .open { flex: 1; min-width: 0; display: flex; align-items: center; gap: var(--s-3); padding: var(--s-2) 0 var(--s-2) 14px; border: 0; background: none; color: var(--ink); font: inherit; text-align: left; cursor: pointer; }
  .open > :global(.icon) { color: var(--ink-2); }
  .text { flex: 1; min-width: 0; display: grid; }
  .text strong { font-size: var(--fs-md); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .text span { color: var(--ink-3); font-size: var(--fs-xs); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .more { width: 44px; height: 44px; display: grid; place-items: center; border: 0; background: none; color: var(--ink-3); cursor: pointer; }
</style>
