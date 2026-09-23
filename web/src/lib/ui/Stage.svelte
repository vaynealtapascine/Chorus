<script lang="ts">
  // Stage mode (SPEC §7): pick messages, fold or hide the rest, restyle, redact or fake names and
  // times, then Capture (the app's chrome disappears) and take a normal screenshot. The plan —
  // what shows, what folds, which names and times — comes from core (`stagePlan`), so a saved
  // stage looks the same on Android. Nothing here changes real data (D-050).
  import { onDestroy } from 'svelte';
  import { stagePlan } from '../core/pkg/chorus_wasm.js';
  import { core } from '../core';
  import { channels, members, messages, type MemberRow, type MessageRow } from '../data';
  import { segmentRich } from '../segments';
  import { sync, type Projection } from '../sync/client';
  import RichText from './RichText.svelte';
  import AttachmentView from './AttachmentView.svelte';

  let { projection, channelId, dark }: { projection: Projection; channelId: string; dark: boolean } = $props();

  type Style = 'chorus' | 'discord' | 'bubbles' | 'card' | 'transcript' | 'minimal';
  interface Render {
    style: Style;
    theme: 'auto' | 'light' | 'dark';
    width: 'phone' | 'square' | 'wide';
    blur_avatars: boolean;
    blur_attachments: boolean;
    hide_header: boolean;
    hide_reply_bars: boolean;
  }
  interface Def {
    selected: string[];
    unselected: 'hidden' | 'context' | 'visible';
    only_members: string[] | null;
    redact_names: boolean;
    fake_names: Record<string, { label: string; color?: string | null }>;
    time: { mode: 'real' } | { mode: 'hide' } | { mode: 'shift'; offset_ms: number } | { mode: 'start'; start: number };
    render: Render;
    channel_id?: string;
  }
  type Row =
    | { kind: 'item'; id: string; selected: boolean; at: number | null; reply_shown: boolean }
    | { kind: 'context'; ids: string[]; count: number };

  const fresh = (): Def => ({
    selected: [],
    unselected: 'context',
    only_members: null,
    redact_names: false,
    fake_names: {},
    time: { mode: 'real' },
    render: { style: 'chorus', theme: 'auto', width: 'phone', blur_avatars: false, blur_attachments: false, hide_header: false, hide_reply_bars: false },
  });

  let def = $state<Def>(fresh());
  let capturing = $state(false);
  let saveName = $state('');
  let pillVisible = $state(true);

  const channel = $derived(channels(projection).find((c) => c.id === channelId));
  const list = $derived(messages(projection, channelId).filter((m) => !m.deleted));
  const byId = $derived(new Map(list.map((m) => [m.id, m])));
  const people = $derived(new Map(members(projection).map((m) => [m.id, m])));
  const authorsHere = $derived([...new Set(list.flatMap((m) => m.authors))].map((id) => people.get(id)).filter(Boolean) as MemberRow[]);
  const saved = $derived(
    Object.entries(projection.rows.stage ?? {})
      .filter(([, r]) => r.exists && r.fields.deleted_at == null && (r.fields.definition as Def | undefined)?.channel_id === channelId)
      .map(([id, r]) => ({ id, name: String(r.fields.name ?? 'Stage'), def: r.fields.definition as Def })),
  );

  const items = $derived(list.map((m) => ({ id: m.id, authors: m.authors, at: m.occurred_at, reply_to: m.reply_to ?? null })));
  // while editing, unselected messages stay visible (dimmed) so they can be picked
  const plan = $derived.by(() => {
    const d = capturing || def.selected.length === 0 ? def : { ...def, unselected: 'visible' as const };
    return JSON.parse(stagePlan(JSON.stringify(items), JSON.stringify(d))) as {
      rows: Row[];
      names: Record<string, { label: string; color?: string | null }>;
    };
  });

  const stageDark = $derived(def.render.theme === 'dark' || (def.render.theme === 'auto' && dark));
  $effect(() => {
    const root = document.documentElement;
    if (def.render.theme === 'auto') delete root.dataset.theme;
    else root.dataset.theme = def.render.theme;
  });
  $effect(() => {
    document.body.classList.toggle('capture', capturing);
  });
  onDestroy(() => {
    document.body.classList.remove('capture');
    delete document.documentElement.dataset.theme;
  });

  const nameOf = (id: string) => plan.names[id]?.label ?? people.get(id)?.display_name ?? people.get(id)?.name ?? 'Someone';
  const colorOf = (id: string) => core.adaptColor(plan.names[id] ? (plan.names[id].color ?? '#A09184') : (people.get(id)?.color ?? '#A09184'), stageDark);
  const glyphOf = (id: string) => (plan.names[id] ? plan.names[id].label.replace('Member ', '')[0] : (people.get(id)?.sigils[0] ?? nameOf(id)[0]));
  const clock = (t: number) => new Date(t).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  const day = (t: number) => new Date(t).toLocaleDateString([], { month: 'short', day: 'numeric' });

  function toggle(id: string) {
    if (capturing) return;
    def.selected = def.selected.includes(id) ? def.selected.filter((x) => x !== id) : [...def.selected, id];
  }

  function toggleOnly(id: string) {
    const cur = def.only_members ?? [];
    const next = cur.includes(id) ? cur.filter((x) => x !== id) : [...cur, id];
    def.only_members = next.length ? next : null;
  }

  function setFake(id: string, label: string) {
    const next = { ...def.fake_names };
    if (label.trim()) next[id] = { label: label.trim(), color: next[id]?.color ?? null };
    else delete next[id];
    def.fake_names = next;
  }

  function setTime(mode: string, value?: string) {
    if (mode === 'start') {
      const first = list.find((m) => def.selected.length === 0 || def.selected.includes(m.id));
      const start = value ? new Date(value).getTime() : (first?.occurred_at ?? Date.now());
      def.time = { mode: 'start', start };
    } else if (mode === 'shift') def.time = { mode: 'shift', offset_ms: Number(value ?? 0) * 60_000 };
    else def.time = { mode } as Def['time'];
  }
  const localInput = (t: number) => new Date(t - new Date(t).getTimezoneOffset() * 60_000).toISOString().slice(0, 16);

  function capture() {
    capturing = true;
    pillVisible = true;
    setTimeout(() => (pillVisible = false), 2000);
  }

  function onkey(e: KeyboardEvent) {
    if (e.key === 'Escape' && capturing) capturing = false;
  }

  function save() {
    if (!saveName.trim()) return;
    sync.create('stage.save', sync.accountScope, sync.newId(), {
      name: saveName.trim(),
      definition: { ...$state.snapshot(def), channel_id: channelId },
    });
    saveName = '';
  }

  function load(d: Def) {
    // projection objects can be reactive proxies, which structuredClone rejects
    const copy = JSON.parse(JSON.stringify(d)) as Def;
    def = { ...fresh(), ...copy, render: { ...fresh().render, ...(copy.render ?? {}) } };
  }

  const WIDTH = { phone: 390, square: 600, wide: 820 };
  const STYLES: [Style, string][] = [
    ['chorus', 'Chorus'], ['discord', 'Discord-ish'], ['bubbles', 'Bubbles'], ['card', 'Card'], ['transcript', 'Transcript'], ['minimal', 'Minimal'],
  ];
  const mine = (m: MessageRow) => m.account_id === sync.accountId;
</script>

<svelte:window onkeydown={onkey} />

<div class="stage-page" class:capturing>
  {#if !capturing}
    <div class="top">
      <a class="back" href="#/chat/{channelId}">‹ #{channel?.name ?? 'channel'}</a>
      <h1 class="display">Stage</h1>
      <button class="primary" onclick={capture}>Capture</button>
    </div>
    <p class="hint">
      {def.selected.length ? `${def.selected.length} picked — tap to add or remove.` : 'Tap messages to pick them. With nothing picked, everything shows.'}
      Nothing here changes your real messages.
    </p>

    <div class="panel">
      <div class="group">
        <span class="label">Others</span>
        <div class="seg" role="radiogroup" aria-label="Unpicked messages">
          {#each [['context', 'Fold'], ['hidden', 'Hide'], ['visible', 'Show']] as [v, l] (v)}
            <button class:on={def.unselected === v} role="radio" aria-checked={def.unselected === v} onclick={() => (def.unselected = v as Def['unselected'])}>{l}</button>
          {/each}
        </div>
      </div>
      <div class="group">
        <span class="label">Style</span>
        <select bind:value={def.render.style} aria-label="Style">
          {#each STYLES as [v, l] (v)}<option value={v}>{l}</option>{/each}
        </select>
        <select bind:value={def.render.theme} aria-label="Theme">
          <option value="auto">Auto</option><option value="light">Light</option><option value="dark">Dark</option>
        </select>
        <select bind:value={def.render.width} aria-label="Width">
          <option value="phone">Phone</option><option value="square">Square</option><option value="wide">Wide</option>
        </select>
      </div>
      <div class="group">
        <span class="label">Hide</span>
        <label class="check"><input type="checkbox" bind:checked={def.redact_names} /> real names</label>
        <label class="check"><input type="checkbox" bind:checked={def.render.blur_avatars} /> avatars</label>
        <label class="check"><input type="checkbox" bind:checked={def.render.blur_attachments} /> attachments</label>
        <label class="check"><input type="checkbox" bind:checked={def.render.hide_header} /> channel name</label>
        <label class="check"><input type="checkbox" bind:checked={def.render.hide_reply_bars} /> reply bars</label>
      </div>
      <div class="group">
        <span class="label">Times</span>
        <select value={def.time.mode} onchange={(e) => setTime(e.currentTarget.value)} aria-label="Times">
          <option value="real">Real</option>
          <option value="hide">Hidden</option>
          <option value="start">Start at…</option>
          <option value="shift">Shift by…</option>
        </select>
        {#if def.time.mode === 'start'}
          <input type="datetime-local" value={localInput(def.time.start)} onchange={(e) => setTime('start', e.currentTarget.value)} aria-label="Start time" />
        {:else if def.time.mode === 'shift'}
          <input type="number" value={def.time.offset_ms / 60_000} onchange={(e) => setTime('shift', e.currentTarget.value)} aria-label="Minutes" /> min
        {/if}
      </div>
      {#if authorsHere.length}
        <details class="group wide">
          <summary><span class="label">Members</span> only some, or shown as someone else</summary>
          {#each authorsHere as a (a.id)}
            <div class="member-row">
              <label class="check">
                <input type="checkbox" checked={!def.only_members || def.only_members.includes(a.id)} onchange={() => toggleOnly(a.id)} />
                {a.display_name ?? a.name}
              </label>
              <input placeholder="Show as…" value={def.fake_names[a.id]?.label ?? ''} onchange={(e) => setFake(a.id, e.currentTarget.value)} aria-label="Show {a.name} as" />
            </div>
          {/each}
        </details>
      {/if}
      <div class="group wide">
        <span class="label">Saved</span>
        {#each saved as s (s.id)}
          <button class="chip" onclick={() => load(s.def)}>{s.name}</button>
        {/each}
        <input bind:value={saveName} placeholder="Name this stage" aria-label="Stage name" />
        <button class="ghost" onclick={save} disabled={!saveName.trim()}>Save</button>
        <button class="ghost" onclick={() => (def = fresh())}>Reset</button>
      </div>
    </div>
  {/if}

  <div
    class="canvas style-{def.render.style}"
    class:blur={def.render.blur_avatars}
    style="--w: {WIDTH[def.render.width]}px"
  >
    {#if !def.render.hide_header}
      <header class="chan">#{channel?.name ?? 'channel'}{#if def.time.mode !== 'hide' && plan.rows[0]?.kind === 'item' && plan.rows[0].at}<span> · {day(plan.rows[0].at)}</span>{/if}</header>
    {/if}
    {#each plan.rows as row, i (row.kind === 'item' ? row.id : `c${i}`)}
      {#if row.kind === 'context'}
        <div class="context">{row.count} message{row.count === 1 ? '' : 's'}</div>
      {:else}
        {@const m = byId.get(row.id)!}
        {@const lead = m.authors[0] ?? ''}
        {@const reply = row.reply_shown && !def.render.hide_reply_bars && m.reply_to ? byId.get(m.reply_to) : undefined}
        <!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
        <div
          class="item"
          class:picked={!capturing && def.selected.includes(m.id)}
          class:dim={!capturing && !row.selected}
          class:mine={mine(m)}
          style="--ring: {colorOf(lead).ring}; --tint: {colorOf(lead).tint}; --name: {colorOf(lead).name}"
          onclick={() => toggle(m.id)}
        >
          {#if def.render.style !== 'transcript' && def.render.style !== 'minimal'}
            <span class="avatar" aria-hidden="true">{glyphOf(lead)}</span>
          {/if}
          <div class="body">
            {#if reply}
              <div class="reply">↳ {reply.authors.map(nameOf).join(' & ')}: {reply.text.slice(0, 60)}</div>
            {/if}
            <div class="head">
              <span class="who">{m.authors.map(nameOf).join(' & ')}{def.render.style === 'transcript' ? ':' : ''}</span>
              {#if row.at != null && def.render.style !== 'transcript'}<time>{clock(row.at)}</time>{/if}
            </div>
            {#if m.quote}
              <blockquote>
                {#if 'items' in m.quote}
                  {#each m.quote.items as q, j (`${q.message_id}:${j}`)}
                    <div>{q.authors.map(nameOf).join(' & ')}: <RichText text={q.text} entities={q.entities} />
                      {#each q.attachments ?? [] as a (a.id)}<AttachmentView attachment={a} blur={def.render.blur_attachments} revealable={false} />{/each}
                    </div>
                  {/each}
                {:else}{m.quote.text}{/if}
              </blockquote>
            {/if}
            {#each m.forward_snapshot ?? [] as f (f.message_id)}
              <div class="forward">Forwarded from {f.authors.map(nameOf).join(' & ')}: <RichText text={f.text} entities={f.entities} />
                {#each f.attachments ?? [] as a (a.id)}<AttachmentView attachment={a} blur={def.render.blur_attachments} revealable={false} />{/each}
              </div>
            {/each}
            {#if m.segments.length > 1}
              {#each m.segments as s, j (j)}
                {@const seg = segmentRich(m, s)}
                <div class="segment"><span class="seg-who" style="color: {colorOf(s.authors[0] ?? '').name}">{s.authors.map(nameOf).join(' & ')}</span> <RichText text={seg.text} entities={seg.entities} /></div>
              {/each}
            {:else}
              <div class="text"><RichText text={m.text} entities={m.entities} /></div>
            {/if}
            {#if m.attachments.length && !m.forward_snapshot?.some((f) => f.attachments?.length)}
              <div class="attachments">{#each m.attachments as a (a.id)}<AttachmentView attachment={a} blur={def.render.blur_attachments} revealable={false} />{/each}</div>
            {/if}
          </div>
        </div>
      {/if}
    {:else}
      <p class="empty">Nothing to show with these settings.</p>
    {/each}
  </div>

  {#if capturing}
    <button class="done" class:faded={!pillVisible} onclick={() => (capturing = false)} onmouseenter={() => (pillVisible = true)}>
      Done · Esc
    </button>
  {/if}
</div>

<style>
  .stage-page { display: grid; gap: var(--s-3); }
  .top { display: flex; align-items: center; gap: var(--s-3); }
  .top h1 { font-size: var(--fs-2xl); margin-right: auto; }
  .back { color: var(--accent); text-decoration: none; font-size: var(--fs-sm); }
  .hint { margin: 0; color: var(--ink-3); font-size: var(--fs-sm); }
  .panel {
    display: flex; flex-wrap: wrap; gap: var(--s-3) var(--s-5); padding: var(--s-4);
    background: var(--surface); border: 1px solid var(--line); border-radius: var(--r-lg); font-size: var(--fs-sm);
  }
  .group { display: flex; flex-wrap: wrap; align-items: center; gap: var(--s-2); }
  .group.wide { flex-basis: 100%; }
  .label { color: var(--ink-3); font-weight: 600; min-width: 4.5em; }
  .seg { display: inline-flex; background: var(--surface-2); border-radius: var(--r-full); padding: 2px; }
  .seg button { border: 0; background: none; color: var(--ink-2); padding: var(--s-1) var(--s-3); border-radius: var(--r-full); cursor: pointer; font: inherit; }
  .seg button.on { background: var(--surface); color: var(--ink); box-shadow: 0 1px 2px rgb(0 0 0 / 0.12); }
  select, .panel input:not([type='checkbox']) {
    font: inherit; color: var(--ink); background: var(--surface-2); border: 1px solid var(--line);
    border-radius: var(--r-sm); padding: var(--s-1) var(--s-2);
  }
  .panel input[type='number'] { width: 5em; }
  .check { display: inline-flex; align-items: center; gap: var(--s-1); color: var(--ink-2); }
  details summary { cursor: pointer; color: var(--ink-2); }
  .member-row { display: flex; gap: var(--s-3); align-items: center; margin-top: var(--s-2); }
  .chip { border: 1px solid var(--line); background: var(--surface-2); border-radius: var(--r-full); padding: var(--s-1) var(--s-3); cursor: pointer; color: var(--ink); font: inherit; }
  .primary { background: var(--accent); color: var(--surface); border: 0; border-radius: var(--r-full); padding: var(--s-2) var(--s-5); font-weight: 600; cursor: pointer; }
  .ghost { background: none; border: 0; color: var(--accent); cursor: pointer; font: inherit; }

  /* ─── the canvas: what ends up in the screenshot ─── */
  .canvas {
    width: min(100%, var(--w)); margin: 0 auto; display: grid; gap: var(--s-1);
    padding: var(--s-4); background: var(--bg); border: 1px dashed var(--line); border-radius: var(--r-lg);
  }
  .capturing .canvas { border-color: transparent; margin-top: var(--s-6); }
  .chan { font-weight: 600; color: var(--ink-2); padding-bottom: var(--s-2); border-bottom: 1px solid var(--line); margin-bottom: var(--s-2); }
  .chan span { font-weight: 400; color: var(--ink-3); }
  .item { display: flex; gap: var(--s-3); padding: var(--s-2); border-radius: var(--r-md); cursor: pointer; }
  .capturing .item { cursor: default; }
  .item.picked { box-shadow: inset 0 0 0 2px var(--accent); }
  .item.dim { opacity: 0.4; }
  .avatar {
    flex: none; width: 36px; height: 36px; display: grid; place-items: center; border-radius: 50%;
    background: var(--surface-2); box-shadow: 0 0 0 2px var(--ring); font-size: 16px; color: var(--name);
  }
  .blur .avatar { filter: blur(5px); }
  .body { min-width: 0; display: grid; gap: 2px; }
  .head { display: flex; gap: var(--s-2); align-items: baseline; }
  .who { font-weight: 600; color: var(--name); }
  time { color: var(--ink-3); font-size: var(--fs-xs); }
  .text, .segment { color: var(--ink); white-space: pre-wrap; overflow-wrap: anywhere; }
  .seg-who { font-weight: 600; font-size: var(--fs-sm); }
  .reply { color: var(--ink-3); font-size: var(--fs-xs); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  blockquote, .forward { margin: var(--s-1) 0; padding: var(--s-2); border-left: 2px solid var(--line); color: var(--ink-2); font-size: var(--fs-sm); }
  .context {
    justify-self: center; color: var(--ink-3); font-size: var(--fs-xs); background: var(--surface-2);
    border-radius: var(--r-full); padding: 2px var(--s-3); margin: var(--s-1) 0;
  }
  .empty { color: var(--ink-3); text-align: center; }

  /* styles (DESIGN.md §5) */
  .style-discord { gap: 0; }
  .style-discord .item { padding: var(--s-1) var(--s-2); }
  .style-discord .avatar { width: 32px; height: 32px; box-shadow: none; }
  .style-bubbles .item .body { background: var(--surface-2); padding: var(--s-2) var(--s-3); border-radius: 18px; }
  .style-bubbles .item.mine { flex-direction: row-reverse; }
  .style-bubbles .item.mine .body { background: var(--tint); }
  .style-bubbles .avatar { width: 28px; height: 28px; align-self: flex-end; }
  .style-card { gap: var(--s-3); }
  .style-card .item { background: var(--surface); border: 1px solid var(--line); border-radius: var(--r-lg); padding: var(--s-4); }
  .style-card .avatar { width: 44px; height: 44px; }
  .style-transcript .item { padding: 1px var(--s-2); }
  .style-transcript .body { display: block; }
  .style-transcript .head { display: inline; }
  .style-transcript .text { display: inline; }
  .style-minimal .item { border-left: 3px solid var(--ring); border-radius: 0; padding: var(--s-1) var(--s-3); }
  .style-minimal .head { display: none; }

  .done {
    position: fixed; right: var(--s-4); bottom: var(--s-4); border: 0; border-radius: var(--r-full);
    padding: var(--s-2) var(--s-4); background: var(--ink); color: var(--bg); cursor: pointer; transition: opacity 400ms;
  }
  .done.faded { opacity: 0; }
  .done:hover { opacity: 1; }
</style>
