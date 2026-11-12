<script lang="ts">
  // Stage mode (SPEC §7, F8): pick messages, fold or hide the rest, restyle, redact or fake names and
  // times, then Capture (the app's chrome disappears) and take a normal screenshot. The plan —
  // what shows, what folds, which names and times — comes from core (`stagePlan`), so a saved
  // stage looks the same on Android. Nothing here changes real data (D-050).
  import { onDestroy } from 'svelte';
  import { stagePlan } from '../core/pkg/chorus_wasm.js';
  import { core } from '../core';
  import { channels, customEmojis, members, messages, reactions, spaces, threadSummaries, type MessageRow } from '../data';
  import { activeViewers, memberVisible } from '../hidden';
  import { chatShown } from '../chat-visible';
  import { segmentRich } from '../segments';
  import { count, fresh, loadDef, maskText, redacted, styleLabel, withBlackout, WIDTH_PX, type Def } from '../stage-def';
  import { paletteStyle } from '../stage-text';
  import { sync, type Projection } from '../sync/client';
  import { bodyPresence } from '../front-view';
  import RichText from './RichText.svelte';
  import AttachmentView from './AttachmentView.svelte';
  import AvatarImage from './AvatarImage.svelte';
  import EmojiImage from './EmojiImage.svelte';
  import Icon from './Icon.svelte';
  import Segmented from './kit/Segmented.svelte';
  import FormCard from './kit/FormCard.svelte';
  import Switch from './kit/Switch.svelte';
  import Check from './stage/Check.svelte';
  import Chip from './kit/Chip.svelte';
  import Label from './stage/Label.svelte';
  import MemberChips from './stage/MemberChips.svelte';
  import NamesTab from './stage/NamesTab.svelte';
  import RedactTab from './stage/RedactTab.svelte';
  import Row from './stage/Row.svelte';
  import SaveSheet, { type SavedRow } from './stage/SaveSheet.svelte';
  import StageShell, { type StageTab } from './stage/StageShell.svelte';
  import Stepper from './stage/Stepper.svelte';
  import StageText from './stage/StageText.svelte';
  import StyleTab from './stage/StyleTab.svelte';
  import TimeSheet from './stage/TimeSheet.svelte';
  import { stageAuthor } from './stage/authors';

  let { projection, channelId, dark }: { projection: Projection; channelId: string; dark: boolean } = $props();

  type PlanRow =
    | { kind: 'item'; id: string; selected: boolean; at: number | null; reply_shown: boolean; blackout?: [number, number][] }
    | { kind: 'context'; ids: string[]; count: number };

  let def = $state<Def>(fresh());
  let tab = $state<StageTab>('select');
  let capturing = $state(false);
  let saving = $state(false);
  let viewingAs = $state<string | null>(null);
  let shownCw = $state(new Set<string>());
  let timing = $state<{ id: string; at: number } | null>(null);

  const channel = $derived(channels(projection).find((c) => c.id === channelId));
  const space = $derived(spaces(projection).find((s) => s.id === channel?.space_id));
  const activeMembers = $derived(activeViewers(bodyPresence(projection, sync.accountId)));
  // a private chat that isn't ours to see now stages nothing (D-097, D-098)
  const open = $derived(chatShown(projection, sync.accountId)(channel));
  const list = $derived(open ? messages(projection, channelId).filter((m) => !m.deleted && memberVisible(m, space?.kind, activeMembers, viewingAs)) : []);
  const byId = $derived(new Map(list.map((m) => [m.id, m])));
  const people = $derived(new Map(members(projection).map((m) => [m.id, m])));
  const authors = $derived([...new Set(list.flatMap((m) => m.authors))].map((id) => stageAuthor(id, people.get(id))));
  const reacts = $derived(reactions(projection));
  const threads = $derived(threadSummaries(projection));
  const emojiById = $derived(new Map(customEmojis(projection).map((e) => [e.id, e])));
  const saved = $derived(
    Object.entries(projection.rows.stage ?? {})
      .filter(([, r]) => r.exists && r.fields.deleted_at == null && (r.fields.definition as Def | undefined)?.channel_id === channelId)
      .map(([id, r]) => ({ id, name: String(r.fields.name ?? 'Stage'), def: loadDef(r.fields.definition) })),
  );

  const items = $derived(list.map((m) => ({ id: m.id, authors: m.authors, at: m.occurred_at, reply_to: m.reply_to ?? null })));
  const picking = $derived(!capturing && tab === 'select');
  const editingText = $derived(!capturing && tab === 'redact');
  const byHand = $derived(!capturing && def.time.mode === 'manual');
  // picking shows every message, the unpicked dimmed; the other tools show what Capture will
  const plan = $derived.by(() => {
    const d = picking && def.selected.length > 0 ? { ...def, unselected: 'visible' as const } : def;
    return JSON.parse(stagePlan(JSON.stringify(items), JSON.stringify(d))) as {
      rows: PlanRow[];
      names: Record<string, { label: string; color?: string | null }>;
      palette?: Record<string, string | boolean>;
    };
  });
  const shown = $derived(plan.rows.filter((r) => r.kind === 'item').length);
  const firstRow = $derived(plan.rows.find((r): r is Extract<PlanRow, { kind: 'item' }> => r.kind === 'item'));

  const stageDark = $derived(def.render.theme === 'custom' ? plan.palette?.dark === true : def.render.theme === 'dark' || (def.render.theme === 'auto' && dark));
  $effect(() => {
    const root = document.documentElement;
    if (def.render.theme === 'auto' || def.render.theme === 'custom') delete root.dataset.theme;
    else root.dataset.theme = def.render.theme;
  });
  onDestroy(() => { delete document.documentElement.dataset.theme; });

  const nameOf = (id: string) => plan.names[id]?.label ?? people.get(id)?.display_name ?? people.get(id)?.name ?? 'Someone';
  const colorOf = (id: string) => core.adaptColor(plan.names[id] ? (plan.names[id].color ?? '#A09184') : (people.get(id)?.color ?? '#A09184'), stageDark);
  const glyphOf = (id: string) => (plan.names[id] ? plan.names[id].label.replace('Member ', '')[0] : (people.get(id)?.sigils[0] ?? nameOf(id)[0]));
  const clock = (t: number) => new Date(t).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  const day = (t: number) => new Date(t).toLocaleDateString([], { month: 'short', day: 'numeric' });

  const picked = $derived(def.selected.filter((id) => byId.has(id)).length);
  const subtitle = $derived(`#${channel?.name ?? 'channel'} · ` + (
    tab === 'select' || tab === 'filters' ? (def.selected.length ? `${picked} of ${list.length} picked` : count(list.length, 'message'))
    : tab === 'style' ? `${styleLabel(def.render.style)} · ${def.render.width} width`
    : tab === 'redact' ? (redacted(def) ? 'redacted' : count(shown, 'message'))
    : Object.keys(def.fake_names).length ? `${count(Object.keys(def.fake_names).length, 'name')} changed` : count(shown, 'message')));

  function toggle(id: string) {
    if (!picking) return;
    def.selected = def.selected.includes(id) ? def.selected.filter((x) => x !== id) : [...def.selected, id];
  }
  const onlyAuthor = $derived(authors.find((a) => activeMembers.includes(a.id)) ?? authors[0]);

  function save(name: string) {
    const id = saved.find((s) => s.name.toLowerCase() === name.toLowerCase())?.id ?? sync.newId();
    sync.create('stage.save', sync.accountScope, id, { name, definition: { ...$state.snapshot(def), channel_id: channelId } });
    saving = false;
  }
  const savedRows = $derived<SavedRow[]>(saved.map((s) => ({
    id: s.id, name: s.name, icon: 'message',
    summary: [`#${channel?.name ?? 'channel'}`, count(s.def.selected.length || list.length, 'message'), styleLabel(s.def.render.style),
      Object.keys(s.def.fake_names).length ? 'names changed' : '', redacted(s.def) ? 'redacted' : ''].filter(Boolean).join(' · '),
  })));

  const mine = (m: MessageRow) => m.account_id === sync.accountId;
  const spaceInternal = $derived(space?.kind === 'internal');
</script>

<StageShell bind:tab bind:capturing {subtitle} closeHref="#/chat/{channelId}" canCapture={list.length > 0} onsave={() => (saving = true)}>
  {#snippet panel()}
    {#if tab === 'select'}
      <Label>Messages you didn’t pick</Label>
      <Segmented label="Messages you didn’t pick" value={def.unselected} options={[{ id: 'hidden', label: 'Hidden' }, { id: 'context', label: 'Context only' }, { id: 'visible', label: 'Shown' }]}
        onchange={(v) => (def.unselected = v)} />
      <div class="chips">
        <Chip label="Pick all" onclick={() => (def.selected = list.map((m) => m.id))} />
        <Chip label="Pick none" onclick={() => (def.selected = [])} />
        {#if onlyAuthor && authors.length > 1}
          <Chip label="Only {onlyAuthor.name}" onclick={() => (def.selected = list.filter((m) => m.authors.includes(onlyAuthor.id)).map((m) => m.id))} />
        {/if}
      </div>
    {:else if tab === 'filters'}
      {#if authors.length > 1}
        <Label>Only these members</Label>
        <MemberChips {authors} only={def.only_members} onchange={(next) => (def.only_members = next)} />
      {/if}
      <FormCard>
        <Switch label="Hide reactions" bind:checked={def.render.hide_reactions} />
        <Switch label="Hide reply bars" bind:checked={def.render.hide_reply_bars} />
        <Switch label="Hide thread previews" bind:checked={def.render.hide_thread_previews} />
      </FormCard>
      <Row label="Crop reply chains">
        <Stepper value={def.reply_depth == null ? 'All' : String(def.reply_depth)} label="Reply levels"
          minus={def.reply_depth === 0 ? null : () => (def.reply_depth = def.reply_depth == null ? 2 : Math.max(0, def.reply_depth - 1))}
          plus={def.reply_depth == null ? null : () => (def.reply_depth = def.reply_depth! >= 9 ? null : def.reply_depth! + 1)} />
      </Row>
      {#if spaceInternal}
        <Row label="Viewing as">
          <select aria-label="Stage viewing as member" value={viewingAs ?? ''} onchange={(e) => (viewingAs = e.currentTarget.value || null)}>
            <option value="">Current front</option>
            {#each members(projection).filter((m) => !m.deleted && !m.archived) as person (person.id)}
              <option value={person.id}>{person.display_name ?? person.name}</option>
            {/each}
          </select>
        </Row>
      {/if}
    {:else if tab === 'style'}
      <StyleTab bind:def {dark} />
    {:else if tab === 'redact'}
      <RedactTab bind:def />
    {:else}
      <NamesTab bind:def {authors} firstAt={firstRow?.at ?? null} />
    {/if}
  {/snippet}

  {#snippet preview()}
    <div
      class="canvas style-{def.render.style}"
      class:capturing
      class:blur={def.render.blur_avatars}
      style="--w: {WIDTH_PX[def.render.width]}px; {paletteStyle(plan.palette)}"
    >
      {#if !def.render.hide_header}
        <header class="chan"><Icon name="hash" size={20} />{channel?.name ?? 'channel'}{#if firstRow?.at != null && def.time.mode !== 'hide' && !def.render.hide_dates}<span>{day(firstRow.at)}</span>{/if}</header>
      {/if}
      {#each plan.rows as row, i (row.kind === 'item' ? row.id : `c${i}`)}
        {#if row.kind === 'context'}
          {@const who = [...new Set(row.ids.flatMap((id) => byId.get(id)?.authors ?? []).map(nameOf))]}
          <div class="context">{[who.length > 2 ? `${who.slice(0, 2).join(', ')}…` : who.join(', '), count(row.count, 'message')].filter(Boolean).join(' · ')}</div>
        {:else}
          {@const m = byId.get(row.id)!}
          {@const lead = m.authors[0] ?? ''}
          {@const reply = row.reply_shown && !def.render.hide_reply_bars && m.reply_to ? byId.get(m.reply_to) : undefined}
          {@const on = def.selected.includes(m.id)}
          <!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
          <div
            class="item"
            class:dim={!capturing && !row.selected}
            class:mine={mine(m)}
            class:picking
            style="--ring: {colorOf(lead).ring}; --tint: {colorOf(lead).tint}; --name: {colorOf(lead).name}"
            onclick={() => toggle(m.id)}
          >
            {#if picking}<Check {on} />{/if}
            {#if def.render.style !== 'transcript' && def.render.style !== 'minimal'}
              <span class="avatar" aria-hidden="true"><AvatarImage hash={plan.names[lead] ? undefined : people.get(lead)?.avatar_blob} glyph={glyphOf(lead)} name={nameOf(lead)} /></span>
            {/if}
            <div class="body">
              {#if reply}
                <div class="reply">↳ {reply.authors.map(nameOf).join(' & ')}: {reply.cw ? `Content warning: ${reply.cw}` : maskText(reply.text, def.blackout?.[reply.id] ?? []).slice(0, 60)}</div>
              {/if}
              <div class="head">
                <span class="who">{m.authors.map(nameOf).join(' & ')}{def.render.style === 'transcript' ? ':' : ''}</span>
                {#if row.at != null && def.render.style !== 'transcript'}{#if byHand}<button class="time-edit" title="Change this time" onclick={() => (timing = { id: m.id, at: row.at! })}>{clock(row.at)}</button>{:else}<time>{clock(row.at)}</time>{/if}{/if}
              </div>
              {#if m.cw}
                <button class="cw-toggle" aria-expanded={shownCw.has(m.id)} onclick={(e) => {
                  e.stopPropagation();
                  const next = new Set(shownCw);
                  if (next.has(m.id)) next.delete(m.id); else next.add(m.id);
                  shownCw = next;
                }}>Content warning: {m.cw} · {shownCw.has(m.id) ? 'Hide' : 'Show'}</button>
              {/if}
              {#if !m.cw || shownCw.has(m.id)}
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
                  <div class="forward">{f.authors.length ? `Forwarded from ${f.authors.map(nameOf).join(' & ')}` : 'Forwarded'}: <RichText text={f.text} entities={f.entities} />
                    {#each f.attachments ?? [] as a (a.id)}<AttachmentView attachment={a} blur={def.render.blur_attachments} revealable={false} />{/each}
                  </div>
                {/each}
                {#if m.segments.length > 1 && !row.blackout?.length && !editingText}
                  {#each m.segments as sg, j (j)}
                    {@const seg = segmentRich(m, sg)}
                    <div class="segment"><span class="seg-who" style="color: {colorOf(sg.authors[0] ?? '').name}">{sg.authors.map(nameOf).join(' & ')}</span> <RichText text={seg.text} entities={seg.entities} /></div>
                  {/each}
                {:else}
                  <StageText text={m.text} entities={m.entities} shown={row.blackout} own={def.blackout?.[m.id] ?? []} editing={editingText}
                    onchange={(ranges) => (def.blackout = withBlackout(def, m.id, ranges))} />
                {/if}
                {#if m.attachments.length && !m.forward_snapshot?.some((f) => f.attachments?.length)}
                  <div class="attachments">{#each m.attachments as a (a.id)}<AttachmentView attachment={a} blur={def.render.blur_attachments} revealable={false} />{/each}</div>
                {/if}
                {#if threads.get(m.id) && !def.render.hide_thread_previews}
                  {@const n = threads.get(m.id)!.replyCount}
                  <span class="thread">{n === 1 ? '1 reply' : `${n} replies`}</span>
                {/if}
                {#if reacts.get(m.id)?.size && !def.render.hide_reactions}
                  <div class="reacts">
                    {#each [...reacts.get(m.id)!] as [emoji, who] (emoji)}
                      <span class="react">{#if emoji.startsWith('custom:') && emojiById.has(emoji.slice(7))}{@const custom = emojiById.get(emoji.slice(7))!}<EmojiImage hash={custom.blob_hash} name={custom.name} />{:else}{emoji}{/if} {who.length}</span>
                    {/each}
                  </div>
                {/if}
              {/if}
            </div>
          </div>
        {/if}
      {:else}
        <p class="empty">No messages</p>
      {/each}
    </div>
  {/snippet}
</StageShell>

{#if timing}
  <TimeSheet at={timing.at} onclose={() => (timing = null)}
    onpick={(at) => { if (def.time.mode === 'manual') def.time = { mode: 'manual', at: { ...def.time.at, [timing!.id]: at } }; timing = null; }} />
{/if}

{#if saving}
  <SaveSheet saved={savedRows} onsave={save} onclose={() => (saving = false)}
    onopen={(id) => { const s = saved.find((x) => x.id === id); if (s) { def = loadDef(s.def); saving = false; } }}
    ondelete={(id) => sync.create('stage.delete', sync.accountScope, id, {})} />
{/if}

<style>
  .chips { display: flex; flex-wrap: wrap; gap: var(--s-2); }
  select { font: inherit; color: var(--ink); background: var(--surface-2); border: 1px solid var(--line); border-radius: var(--r-sm); padding: var(--s-1) var(--s-2); }
  .cw-toggle { display: block; padding: var(--s-2); border: 1px solid var(--line); border-radius: var(--r-sm); background: var(--surface-2); color: var(--ink); cursor: pointer; text-align: left; font: inherit; }

  /* ─── the canvas: what ends up in the screenshot ─── */
  .canvas {
    width: min(100%, var(--w)); margin: 0 auto; display: grid; gap: var(--s-1); box-sizing: border-box; min-height: 100%; align-content: start;
    padding: var(--s-4); background: var(--bg); color: var(--ink);
  }
  .capturing { padding-top: var(--s-6); }
  .time-edit { padding: 0; border: 0; background: none; color: var(--accent); font: inherit; font-size: var(--fs-xs); cursor: pointer; }
  .thread { color: var(--accent); font-size: var(--fs-sm); font-weight: 600; }
  .reacts { display: flex; flex-wrap: wrap; gap: var(--s-1); }
  .react { display: inline-flex; align-items: center; gap: var(--s-1); padding: 2px var(--s-2); border-radius: var(--r-sm); background: var(--surface-3); color: var(--ink-2); font-size: var(--fs-xs); }
  .chan { display: flex; align-items: center; gap: var(--s-2); font-size: 17px; font-weight: 600; color: var(--ink); padding: var(--s-3) 0 var(--s-2); }
  .chan :global(.icon) { color: var(--ink-3); }
  .chan span { margin-left: auto; font-size: var(--fs-xs); font-weight: 400; color: var(--ink-3); }
  .item { display: flex; gap: var(--s-3); padding: var(--s-2); border-radius: var(--r-md); align-items: flex-start; }
  .item.picking { cursor: pointer; align-items: center; }
  .item.dim { opacity: 0.45; }
  .avatar {
    flex: none; width: 36px; height: 36px; display: grid; place-items: center; border-radius: 50%;
    background: var(--surface-2); box-shadow: 0 0 0 2px var(--ring); font-size: var(--fs-base); color: var(--name); overflow: hidden;
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
    justify-self: start; color: var(--ink-3); font-size: var(--fs-xs); font-style: italic; border-left: 3px solid var(--line); padding: 2px var(--s-2); margin: var(--s-1) 0;
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
</style>
