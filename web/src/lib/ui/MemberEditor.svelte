<script lang="ts">
  import { core } from '../core';
  import { fieldDefs, fieldValues, groupPath, groups, members, membership, type FieldDef, type ProxyTag } from '../data';
  import { router } from '../router.svelte';
  import { sync, type Projection } from '../sync/client';

  let { projection, id, dark }: { projection: Projection; id: string; dark: boolean } = $props();

  const all = $derived(members(projection));
  const m = $derived(all.find((x) => x.id === id));
  const raw = $derived((projection.rows.member?.[id]?.fields ?? {}) as Record<string, unknown>);
  const gs = $derived(groups(projection));
  const inGroup = $derived(membership(projection));
  const defs = $derived(fieldDefs(projection));
  const values = $derived(fieldValues(projection).get(id) ?? new Map());
  const colors = $derived(m ? core.adaptColor(m.color, dark) : null);

  /** Send only fields that changed (field-level LWW merges them with other devices' edits). */
  function set(fields: Record<string, unknown>) {
    const changed = Object.fromEntries(
      Object.entries(fields).filter(([k, v]) => JSON.stringify(raw[k] ?? null) !== JSON.stringify(v ?? null)),
    );
    if (Object.keys(changed).length) sync.create('member.set', sync.accountScope, id, changed);
  }

  const text = (e: Event) => (e.currentTarget as HTMLInputElement).value;
  const opt = (s: string) => (s.trim() === '' ? null : s.trim());

  // description: edited as markup, stored as plain text + entities (D-041)
  const descMarkup = $derived(
    m?.description ? core.toMarkup({ text: m.description, entities: (raw.description_entities as never) ?? [] }) : '',
  );
  function saveDescription(e: Event) {
    const src = text(e);
    const rich = core.parseMarkup(src);
    set({ description: rich.text || null, description_entities: rich.entities.length ? rich.entities : null });
  }

  const sigilText = $derived(m?.sigils.join(' ') ?? '');
  const sigilClash = $derived(
    (m?.sigils ?? []).filter((s) => all.some((o) => o.id !== id && !o.deleted && o.sigils.includes(s))),
  );

  let tags = $state<ProxyTag[]>([]);
  $effect(() => {
    tags = m?.proxy_tags.map((t) => ({ ...t })) ?? [];
  });
  function saveTags() {
    set({ proxy_tags: tags.filter((t) => t.prefix || t.suffix) });
  }

  function toggleGroup(gid: string, on: boolean) {
    sync.create(on ? 'group.add_member' : 'group.remove_member', sync.accountScope, gid, { member_id: id });
  }

  function setValue(def: FieldDef, value: unknown) {
    sync.create('field.set_value', sync.accountScope, null, { member_id: id, field_id: def.id, value });
  }

  let newField = $state('');
  let newFieldType = $state<FieldDef['type']>('text');
  function addField(e: SubmitEvent) {
    e.preventDefault();
    if (!newField.trim()) return;
    sync.create('field.define', sync.accountScope, sync.newId(), { name: newField.trim(), type: newFieldType });
    newField = '';
  }

  function switchIn() {
    sync.create('front.switch', sync.accountScope, sync.newId(), {
      entries: [{ subject_type: 'member', subject_id: id, level: 'front', is_primary: true }],
    });
    router.go('/');
  }
</script>

{#if !m}
  <p class="muted">This member doesn't exist (yet). <a href="#/members">Back to members</a></p>
{:else}
  <article class="editor" style="--ring: {colors?.ring}; --tint: {colors?.tint}">
    <header>
      <a class="back" href="#/members" aria-label="Back to members">‹ Members</a>
      <div class="identity">
        <span class="avatar" aria-hidden="true">{m.sigils[0] ?? m.name[0]}</span>
        <div>
          <h1 class="display" style="color: {colors?.name}">{m.display_name ?? m.name}</h1>
          <p class="muted">{m.pronouns ?? ''}{m.archived ? ' · archived' : ''}{m.deleted ? ' · in Trash' : ''}</p>
        </div>
      </div>
      <button class="primary" onclick={switchIn}>Switch in</button>
    </header>

    <section>
      <h2>About</h2>
      <div class="fields">
        <label>Name <input value={m.name} onchange={(e) => set({ name: text(e).trim() || m!.name })} /></label>
        <label>Display name <input value={m.display_name ?? ''} onchange={(e) => set({ display_name: opt(text(e)) })} /></label>
        <label>Pronouns <input value={m.pronouns ?? ''} onchange={(e) => set({ pronouns: opt(text(e)) })} /></label>
        <label class="color">Colour <input type="color" value={m.color} onchange={(e) => set({ color: text(e) })} /></label>
        <label>Birthday <input type="date" value={m.birthday ?? ''} onchange={(e) => set({ birthday: opt(text(e)) })} /></label>
        <label class="wide">
          Description <span class="hint">**bold**, *italic*, ||spoiler||, [link](https://…)</span>
          <textarea rows="4" value={descMarkup} onchange={saveDescription}></textarea>
        </label>
      </div>
    </section>

    <section>
      <h2>Speaking</h2>
      <div class="fields">
        <label class="wide">
          Sigils <span class="hint">emoji that start a message as {m.name}; space-separated</span>
          <input
            value={sigilText}
            onchange={(e) => set({ sigils: text(e).split(/\s+/).filter(Boolean) })}
            placeholder="🌌"
          />
          {#if sigilClash.length}<span class="warn">Also used by someone else: {sigilClash.join(' ')}</span>{/if}
        </label>
        <div class="wide">
          <span class="label">Proxy tags <span class="hint">like PluralKit: <code>k:</code> or <code>[</code>…<code>]</code></span></span>
          {#each tags as t, i (i)}
            <div class="tag">
              <input bind:value={t.prefix} placeholder="prefix" onchange={saveTags} aria-label="Prefix" />
              <span class="muted">text</span>
              <input bind:value={t.suffix} placeholder="suffix" onchange={saveTags} aria-label="Suffix" />
              <button class="ghost" onclick={() => { tags = tags.filter((_, j) => j !== i); saveTags(); }}>Remove</button>
            </div>
          {/each}
          <button class="ghost" onclick={() => (tags = [...tags, { prefix: '', suffix: '' }])}>Add proxy tag</button>
        </div>
      </div>
    </section>

    {#if gs.length}
      <section>
        <h2>Subsystems &amp; groups</h2>
        <div class="checks">
          {#each gs as g (g.id)}
            <label class="check">
              <input type="checkbox" checked={inGroup.get(g.id)?.has(id) ?? false} onchange={(e) => toggleGroup(g.id, (e.currentTarget as HTMLInputElement).checked)} />
              {groupPath(g, gs)}
            </label>
          {/each}
        </div>
      </section>
    {/if}

    <section>
      <h2>Custom fields</h2>
      <div class="fields">
        {#each defs as d (d.id)}
          {@const v = values.get(d.id)}
          <label class:wide={d.type === 'long_text'}>
            {d.name}
            {#if d.type === 'boolean'}
              <input type="checkbox" checked={v === true} onchange={(e) => setValue(d, (e.currentTarget as HTMLInputElement).checked)} />
            {:else if d.type === 'long_text'}
              <textarea rows="3" value={(v as string) ?? ''} onchange={(e) => setValue(d, text(e))}></textarea>
            {:else if d.type === 'select'}
              <select value={(v as string) ?? ''} onchange={(e) => setValue(d, text(e))}>
                <option value=""></option>
                {#each d.options.choices ?? [] as c (c)}<option>{c}</option>{/each}
              </select>
            {:else}
              <input
                type={d.type === 'number' ? 'number' : d.type === 'date' ? 'date' : d.type === 'url' ? 'url' : d.type === 'color' ? 'color' : 'text'}
                value={(v as string | number) ?? ''}
                onchange={(e) => setValue(d, d.type === 'number' ? Number(text(e)) : text(e))}
              />
            {/if}
          </label>
        {/each}
      </div>
      <form class="addfield" onsubmit={addField}>
        <input bind:value={newField} placeholder="New field, e.g. Role" aria-label="Field name" />
        <select bind:value={newFieldType} aria-label="Field type">
          <option value="text">Text</option>
          <option value="long_text">Long text</option>
          <option value="number">Number</option>
          <option value="date">Date</option>
          <option value="boolean">Yes / no</option>
          <option value="url">Link</option>
          <option value="color">Colour</option>
        </select>
        <button class="ghost">Add field</button>
      </form>
    </section>

    <section class="danger">
      {#if m.archived}
        <button class="ghost" onclick={() => sync.create('member.unarchive', sync.accountScope, id, {})}>Unarchive</button>
      {:else}
        <button class="ghost" onclick={() => sync.create('member.archive', sync.accountScope, id, {})}>Archive</button>
      {/if}
      {#if m.deleted}
        <button class="ghost" onclick={() => sync.create('member.restore', sync.accountScope, id, {})}>Restore from Trash</button>
      {:else}
        <button class="ghost del" onclick={() => sync.create('member.delete', sync.accountScope, id, {})}>Move to Trash</button>
      {/if}
      <span class="hint">Nothing is ever erased; Trash can always restore (D-053).</span>
    </section>
  </article>
{/if}

<style>
  .editor {
    display: grid;
    gap: var(--s-6);
  }
  header {
    display: grid;
    gap: var(--s-4);
  }
  .back {
    color: var(--ink-2);
    text-decoration: none;
    font-size: var(--fs-sm);
  }
  .identity {
    display: flex;
    gap: var(--s-4);
    align-items: center;
  }
  .avatar {
    width: 72px;
    height: 72px;
    display: grid;
    place-items: center;
    font-size: 34px;
    border-radius: 50%;
    background: var(--surface-2);
    box-shadow: 0 0 0 3px var(--ring), 0 0 0 6px var(--bg);
  }
  h1 {
    font-size: var(--fs-2xl);
  }
  h2 {
    font-size: var(--fs-sm);
    font-weight: 600;
    color: var(--ink-2);
    margin-bottom: var(--s-3);
  }
  section {
    background: var(--surface);
    border: 1px solid var(--line);
    border-radius: var(--r-lg);
    padding: var(--s-5);
  }
  .fields {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
    gap: var(--s-4);
  }
  label,
  .label {
    display: grid;
    gap: var(--s-1);
    font-size: var(--fs-sm);
    color: var(--ink-2);
  }
  .wide {
    grid-column: 1 / -1;
  }
  input,
  textarea,
  select {
    font: inherit;
    font-size: var(--fs-base);
    color: var(--ink);
    background: var(--surface-2);
    border: 1px solid var(--line);
    border-radius: var(--r-sm);
    padding: var(--s-2) var(--s-3);
  }
  input[type='color'] {
    padding: 2px;
    height: 40px;
    width: 64px;
  }
  input[type='checkbox'] {
    width: 18px;
    height: 18px;
    accent-color: var(--accent);
  }
  .hint {
    color: var(--ink-3);
    font-size: var(--fs-xs);
  }
  .warn {
    color: var(--warn);
    font-size: var(--fs-xs);
  }
  .tag {
    display: flex;
    gap: var(--s-2);
    align-items: center;
  }
  .tag input {
    width: 7em;
  }
  .checks {
    display: flex;
    flex-wrap: wrap;
    gap: var(--s-2) var(--s-5);
  }
  .check {
    display: flex;
    align-items: center;
    gap: var(--s-2);
    color: var(--ink);
  }
  .addfield {
    display: flex;
    gap: var(--s-2);
    margin-top: var(--s-4);
  }
  .addfield input {
    flex: 1;
  }
  .primary {
    justify-self: start;
    background: var(--accent);
    color: var(--surface);
    border: 0;
    border-radius: var(--r-full);
    padding: var(--s-2) var(--s-5);
    cursor: pointer;
    font-weight: 600;
  }
  .ghost {
    background: none;
    border: 0;
    color: var(--accent);
    cursor: pointer;
    padding: var(--s-1) 0;
    justify-self: start;
  }
  .danger {
    display: flex;
    gap: var(--s-5);
    align-items: center;
    flex-wrap: wrap;
    background: none;
    border-style: dashed;
  }
  .del {
    color: var(--danger);
  }
  .muted {
    color: var(--ink-3);
    margin: 0;
  }
</style>
