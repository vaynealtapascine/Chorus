<script lang="ts">
  // Notifications (D-127, Figma F11.5): chips, then Needs you / Today / Earlier; unread rows are
  // tinted with a dot. A click marks a row read and opens it; right-click (or hold on touch)
  // offers more. Read marks belong to the member reading ("Reading as"); the side panel chooses
  // what shows here. Everything listed already reached this account elsewhere (`inbox.ts`).
  import { core } from '../core';
  import { members, selfMember, type MemberRow } from '../data';
  import { sync, type Projection } from '../sync/client';
  import { apiFetch } from '../http';
  import { apiBase } from '../sync/device';
  import { router } from '../router.svelte';
  import { bodyAuthors } from '../front-view';
  import { canWriteAs } from '../posts';
  import { longPress } from '../long-press';
  import { inbox, refreshInbox } from '../inbox-state.svelte';
  import { chips, isRead, mark, readMarks, readPayload, section, SHOWS, shows, showsPayload, showsPref, unmark, unread, visible, type InboxItem, type InboxRead } from '../inbox';
  import { shortAgo } from '../journal-presentation';
  import { notifyPreset } from '../core/pkg/chorus_wasm.js';
  import AvatarImage from './AvatarImage.svelte';
  import Icon from './Icon.svelte';

  let { projection, dark }: { projection: Projection; dark: boolean } = $props();
  const person = $derived(!!selfMember(projection));
  const own = $derived(members(projection).filter((m) => canWriteAs(projection, m.id, sync.accountId)));
  let chosen = $state<string | null>(null);
  // whoever is fronting reads, unless another member was picked; a person reads as themself
  const reader = $derived<MemberRow | undefined>(own.find((m) => m.id === chosen) ?? selfMember(projection)
    ?? own.find((m) => m.id === bodyAuthors(projection, sync.accountId)[0]) ?? own[0]);
  const marks = $derived(readMarks(projection.rows.pref, sync.accountId));
  const read = $derived<InboxRead>((reader && marks.get(reader.id)) || { until: 0, ids: [] });
  const pref = $derived(showsPref(projection.rows.pref, sync.accountId));
  const items = $derived(inbox.account === sync.accountId ? inbox.items : []);
  let chip = $state('all');
  const chipList = $derived(chips(person));
  const shown = $derived(visible(items, chip, pref));
  const startOfToday = $derived.by(() => { void items; const d = new Date(); d.setHours(0, 0, 0, 0); return d.getTime(); });
  let loading = $state(false);
  let error = $state('');
  let notice = $state('');
  let menu = $state<{ item: InboxItem; x: number; y: number } | null>(null);
  let readAs = $state(false);
  let panelOpen = $state(false);
  let accepting = $state<InboxItem | null>(null);

  async function load() {
    loading = true;
    try { await refreshInbox(projection, true); error = ''; } catch { error = 'Couldn’t check for new notifications. Showing what this device has.'; }
    finally { loading = false; }
  }
  $effect(() => { void sync.device?.session; void load(); });

  function save(next: InboxRead) {
    if (!reader) return;
    sync.create('pref.set', sync.accountScope, null, readPayload(reader.id, next));
  }
  function setShows(key: string, on: boolean) {
    sync.create('pref.set', sync.accountScope, null, showsPayload(pref, key, on));
  }
  function markAll() {
    const newest = Math.max(read.until, ...items.filter((i) => !i.needsYou).map((i) => i.at));
    save({ until: newest, ids: [] });
  }
  function open(item: InboxItem) {
    if (!item.needsYou) save(mark(read, item));
    if (item.kind === 'review' || item.kind === 'own_switch') router.go('/history');
    else if (item.postId) router.go(`/journal/post/${encodeURIComponent(item.postId)}`);
    else if (item.channelId) router.go(`/chat/${encodeURIComponent(item.channelId)}${item.messageId ? `/${encodeURIComponent(item.messageId)}` : ''}`);
    else if (item.kind === 'export_ready') router.go('/data');
    else if (item.accountId) router.go(`/people/${encodeURIComponent(item.accountId)}`);
  }
  async function call(method: string, path: string, body?: unknown) {
    const r = await apiFetch(`${apiBase()}${path}`, { method, headers: { authorization: `Bearer ${sync.device?.session ?? ''}`,
      ...(body ? { 'content-type': 'application/json' } : {}) }, body: body ? JSON.stringify(body) : undefined });
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
    return r.status === 204 ? null : r.json().catch(() => null);
  }
  async function act(failure: string, run: () => unknown) {
    try { await run(); await load(); } catch { notice = failure; setTimeout(() => (notice = ''), 4000); }
  }
  function accept(item: InboxItem) {
    if (item.kind === 'alert_ask') return act('Couldn’t allow. Try again.', () => call('PUT', `/alerts/${item.memberId}/${item.accountId}`));
    if (person) return act('Couldn’t accept. Try again.', () => sync.create('follow.accept', sync.accountScope, item.followId!, {}));
    accepting = item;
  }
  /** Accept with one of the follow presets (People's), or the account default. */
  function acceptWith(preset: string) {
    const item = accepting;
    accepting = null;
    if (!item?.followId) return;
    void act('Couldn’t accept. Try again.', () => {
      sync.create('follow.accept', sync.accountScope, item.followId!, {});
      if (preset !== 'inherit') sync.create('follow.set_ceiling', sync.accountScope, item.followId!, { ceiling: JSON.parse(notifyPreset(preset)) });
    });
  }
  function decline(item: InboxItem) {
    if (item.kind === 'alert_ask') return act('Couldn’t decline. Try again.', () => call('DELETE', `/alerts/${item.memberId}/${item.accountId}`));
    return act('Couldn’t decline. Try again.', () => sync.create('follow.end', sync.accountScope, item.followId!, {}));
  }
  async function quietHour(item: InboxItem) {
    menu = null;
    await act('Couldn’t quiet them. Try again.', async () => {
      const follows = (await call('GET', '/follows')) as { following: { id: string; status: string; prefs?: Record<string, unknown>; account: { id: string } }[] };
      const f = follows.following.find((x) => x.account.id === item.accountId && x.status === 'active');
      if (!f) throw new Error('not following');
      await call('PUT', `/follows/${f.id}/prefs`, { ...(f.prefs ?? {}), mute_until: Date.now() + 60 * 60 * 1000 });
    });
  }
  function openMenu(item: InboxItem, x: number, y: number) {
    menu = { item, x: Math.min(x, innerWidth - 300), y: Math.min(y, innerHeight - 320) };
  }
  const BADGE: Record<string, [string, string]> = {
    mention: ['@', 'accent'], reply: ['reply', 'accent'], post_reply: ['reply', 'accent'], dm: ['chat', 'accent'], message: ['chat', 'accent'],
    member_dm: ['chat', 'accent'], switch: ['swap', 'accent'], own_switch: ['swap', 'accent'], reaction: ['heart', 'danger'],
    repost: ['repost', 'ok'], quote: ['message', 'ok'], post: ['book', 'ink-2'], follow_request: ['users', 'warn'],
    alert_ask: ['bell', 'warn'], alert_request: ['bell', 'warn'], alert_accepted: ['bell', 'warn'], review: ['!', 'warn'], export_ready: ['doc', 'ink-2'],
  };
  const look = (item: InboxItem) => core.adaptColor(item.color ?? '#A09184', dark);
  const lead = (item: InboxItem) => item.actor && item.title.startsWith(item.actor) ? item.actor : null;
  const shownShows = $derived(SHOWS.filter(([k]) => !person || k !== 'own_switches'));
</script>

<div class="notifications-layout">
  <section class="inbox" aria-busy={loading}>
    <header>
      <div class="titles">
        <h1>Notifications</h1>
        {#if person}<span class="reading">{reader ? `${reader.display_name ?? reader.name} · just me` : 'Just me'}</span>
        {:else}<button class="reading" onclick={() => (readAs = !readAs)} aria-expanded={readAs}>Reading as {reader ? reader.display_name ?? reader.name : 'nobody'} <Icon name="chevron" size={14} /></button>{/if}
      </div>
      <button class="mark-all" onclick={markAll} disabled={unread(items, read, pref) === 0}><Icon name="read" size={18} />Mark all read</button>
      <button class="panel-toggle icon-only" aria-label="What shows here" aria-expanded={panelOpen} onclick={() => (panelOpen = !panelOpen)}><Icon name="sliders" size={20} /></button>
    </header>
    {#if readAs}
      <div class="read-as" role="dialog" aria-label="Read as">
        <p>Each member keeps their own unread marks. Marks never leave your system.</p>
        {#each own as m (m.id)}
          {@const n = unread(items, marks.get(m.id) ?? { until: 0, ids: [] }, pref)}
          {@const c = core.adaptColor(m.color, dark)}
          <button class:on={m.id === reader?.id} onclick={() => { chosen = m.id; readAs = false; }}>
            <span class="mini" style="--bg: {c.ring}; --ink: {c.avatar_ink}"><AvatarImage hash={m.avatar_blob} glyph={m.sigils[0] ?? m.name[0]} name={m.name} /></span>
            <span class="who">{m.display_name ?? m.name}</span><span class="count" class:none={!n}>{n ? `${n} unread` : 'caught up'}</span>
          </button>
        {/each}
      </div>
    {/if}
    <div class="chips" role="tablist" aria-label="Show">
      {#each chipList as [id, label] (id)}
        <button role="tab" aria-selected={chip === id} class:on={chip === id} onclick={() => (chip = id)}>{label} <span>{unread(items, read, pref, id)}</span></button>
      {/each}
    </div>
    {#if error}<p class="quiet">{error}</p>{/if}
    {#if notice}<p class="quiet danger" role="status">{notice}</p>{/if}
    {#if loading && !inbox.loaded}<p class="quiet">Checking…</p>{/if}
    <ol class="rows">
      {#each shown as item, i (item.id)}
        {@const s = section(item, startOfToday)}
        {#if i === 0 || section(shown[i - 1], startOfToday) !== s}<li class="section">{s}</li>{/if}
        {@const unreadRow = !isRead(read, item)}
        {@const [badge, tone] = BADGE[item.kind] ?? ['bell', 'ink-2']}
        {@const c = look(item)}
        <!-- the row is a button for keyboards; right-click or a long press opens its menu -->
        <li class="row" class:unread={unreadRow} data-kind={item.kind}
          oncontextmenu={(e) => { e.preventDefault(); openMenu(item, e.clientX, e.clientY); }}
          use:longPress={{ enabled: true, skip: 'button, a', onpress: ({ x, y }) => openMenu(item, x, y) }}>
          <span class="badge tone-{tone}" aria-hidden="true">{#if badge.length === 1}{badge}{:else}<Icon name={badge} size={20} />{/if}</span>
          <div class="body">
            {#if item.actor}<span class="face" style="--bg: {c.ring}; --ink: {c.avatar_ink}"><AvatarImage hash={item.avatarBlob} glyph={item.actor[0]?.toUpperCase() ?? '?'} name={item.actor} /></span>{/if}
            <button class="open" onclick={() => open(item)}>
              {#if lead(item)}<strong>{lead(item)}</strong>{item.title.slice(lead(item)!.length)}{:else}<strong>{item.title}</strong>{/if}
            </button>
            {#if item.detail}<p class="detail">{item.detail}</p>{/if}
            {#if item.quote}<p class="bubble">{item.quote}</p>{/if}
            {#if item.kind === 'follow_request' || item.kind === 'alert_ask'}
              <div class="asks"><button class="primary" onclick={() => accept(item)}>{item.kind === 'alert_ask' ? 'Allow' : person ? 'Accept' : 'Accept…'}</button><button onclick={() => decline(item)}>Decline</button></div>
            {:else if item.kind === 'review'}
              <div class="asks"><button class="primary" onclick={() => router.go('/history')}>Review</button></div>
            {/if}
          </div>
          <div class="side">
            {#if !item.needsYou}<time datetime={new Date(item.at).toISOString()}>{shortAgo(item.at)}</time>{/if}
            {#if unreadRow}<span class="dot" aria-label="Unread"></span>{/if}
            <button class="more icon-only" aria-label="More options" onclick={(e) => openMenu(item, e.clientX, e.clientY)}><Icon name="more" size={16} /></button>
          </div>
        </li>
      {:else}
        {#if !loading}<li class="quiet">{chip === 'all' ? 'Nothing waiting. Mentions, replies and friends’ news land here.' : 'Nothing here yet.'}</li>{/if}
      {/each}
    </ol>
  </section>

  <aside class="side-panel" class:open={panelOpen} aria-label="Notification options">
    <section class="card">
      <header><h2>What shows here</h2><a href="#/settings">All settings</a></header>
      {#each shownShows as [key, label] (key)}
        <label class="toggle"><span>{label}</span><input type="checkbox" role="switch" checked={shows(pref, key)} onchange={(e) => setShows(key, e.currentTarget.checked)} /></label>
      {/each}
    </section>
    <section class="card">
      <header><h2>Quiet hours</h2><a href="#/settings">Change</a></header>
      <p class="quiet-line"><Icon name="clock" size={18} />Set per friend in Notification settings; pings wait until morning.</p>
    </section>
    <p class="footnote">Everything here also reaches your devices as set in Notification settings.</p>
  </aside>
</div>

{#if menu}
  {@const item = menu.item}
  {@const isFriend = item.category === 'friends' && !!item.accountId && item.kind !== 'alert_ask' && item.kind !== 'follow_request'}
  <button class="scrim" aria-label="Close menu" onclick={() => (menu = null)}></button>
  <div class="menu" role="menu" style="left: {menu.x}px; top: {menu.y}px">
    <p class="menu-title">{item.title}</p>
    {#if !item.needsYou}
      {@const was = isRead(read, item)}
      <button role="menuitem" onclick={() => { save(was ? unmark(read, item, items) : mark(read, item)); menu = null; }}><Icon name="check" size={18} />{was ? 'Mark as unread' : 'Mark as read'}</button>
    {/if}
    <button role="menuitem" onclick={() => { menu = null; open(item); }}><Icon name="chevron-right" size={18} />{item.postId ? 'Open post' : item.channelId ? 'Open chat' : item.actor && item.accountId ? `Open ${item.actor}` : 'Open'}</button>
    {#if isFriend}
      <button role="menuitem" onclick={() => quietHour(item)}><Icon name="muted" size={18} /><span>Quiet {item.actor} for an hour<small>Their switches wait an hour</small></span></button>
      <button role="menuitem" onclick={() => { menu = null; router.go(`/people/${encodeURIComponent(item.accountId!)}`); }}><Icon name="bell" size={18} /><span>Notifications from {item.actor}<small>Who, which layers, how often</small></span></button>
    {/if}
    {#if item.shows}
      {@const label = SHOWS.find(([k]) => k === item.shows)?.[1] ?? ''}
      <button role="menuitem" onclick={() => { setShows(item.shows!, false); menu = null; }}><Icon name="eye-off" size={18} /><span>Hide {label.toLowerCase()} here<small>They still reach your devices as set in settings</small></span></button>
    {/if}
  </div>
{/if}

{#if accepting}
  <button class="scrim" aria-label="Close" onclick={() => (accepting = null)}></button>
  <div class="menu center" role="dialog" aria-label="Accept {accepting.actor}">
    <p class="menu-title">Accept {accepting.actor}: how soon and how exactly they hear about switches</p>
    {#each [['inherit', 'Account default', ''], ['close', 'Close', 'right away, exact times'], ['gentle', 'Gentle', '5–20 min later, rounded'], ['private', 'Private', '30–90 min later, part of day'], ['digest', 'Digest', 'one summary a day'], ['off', 'Off', 'no switch notifications']] as [id, label, about] (id)}
      <button role="menuitem" onclick={() => acceptWith(id)}><span>{label}{#if about}<small>{about}</small>{/if}</span></button>
    {/each}
  </div>
{/if}

<style>
  .notifications-layout { display: grid; grid-template-columns: minmax(0, 1fr) 372px; gap: 0; margin: calc(-1 * var(--s-6)) calc(-1 * var(--s-6)) 0; min-height: 100%; }
  .inbox { border-right: 1px solid var(--line); min-width: 0; }
  .inbox > header { display: flex; align-items: center; gap: 12px; padding: 20px 20px 8px; }
  .titles { flex: 1; display: grid; gap: 2px; min-width: 0; }
  h1 { margin: 0; font-size: 20px; font-weight: 700; }
  .reading { justify-self: start; display: inline-flex; align-items: center; gap: 4px; border: 0; background: none; padding: 0; color: var(--ink-3); font: inherit; font-size: var(--fs-sm); cursor: pointer; }
  .reading :global(.icon) { transform: rotate(90deg); }
  .mark-all { display: inline-flex; align-items: center; gap: 8px; border: 0; border-radius: var(--r-full); background: var(--surface-2); color: var(--ink); padding: 8px 14px; font: inherit; font-size: var(--fs-sm); font-weight: 600; cursor: pointer; }
  .mark-all:disabled { opacity: .5; cursor: default; }
  .icon-only { display: grid; place-items: center; width: 40px; height: 40px; border: 0; border-radius: 50%; background: none; color: var(--ink-2); cursor: pointer; }
  .panel-toggle { display: none; }
  .chips { display: flex; gap: 8px; padding: 8px 20px 12px; overflow-x: auto; scrollbar-width: none; border-bottom: 1px solid var(--line); }
  .chips button { flex: none; border: 0; border-radius: var(--r-full); background: var(--surface-2); color: var(--ink); padding: 6px 12px; font: inherit; font-size: var(--fs-sm); font-weight: 600; cursor: pointer; }
  .chips button span { color: var(--ink-3); font-weight: 500; margin-left: 2px; }
  .chips button.on { background: var(--accent); color: var(--on-accent); }
  .chips button.on span { color: inherit; opacity: .8; }
  .read-as { margin: 0 20px 8px; padding: 8px; border-radius: var(--r-md); background: var(--surface); display: grid; gap: 2px; animation: chorus-fade-in var(--t-fast) var(--ease); }
  .read-as p { margin: 4px 8px 8px; color: var(--ink-3); font-size: var(--fs-sm); }
  .read-as button { display: flex; align-items: center; gap: 12px; border: 0; border-radius: 12px; background: none; color: var(--ink); padding: 8px; font: inherit; cursor: pointer; text-align: left; }
  .read-as button.on { background: var(--accent-soft); }
  .read-as .who { flex: 1; font-weight: 600; }
  .read-as .count { color: var(--accent); font-size: var(--fs-sm); }
  .read-as .count.none { color: var(--ink-3); }
  .mini, .face { display: inline-grid; place-items: center; border-radius: 50%; overflow: hidden; background: var(--bg); color: var(--ink); font-weight: 700; flex: none; }
  .mini { width: 32px; height: 32px; }
  .face { width: 24px; height: 24px; font-size: 12px; }
  .rows { list-style: none; margin: 0; padding: 0; }
  .section { padding: 16px 20px 8px; color: var(--accent); font-size: var(--fs-sm); font-weight: 600; }
  .row { display: grid; grid-template-columns: 36px minmax(0, 1fr) auto; gap: 12px; padding: 12px 20px; animation: chorus-fade-in var(--t-fast) var(--ease); }
  .row.unread { background: color-mix(in srgb, var(--accent-soft) 70%, var(--bg)); }
  .row:hover { background: color-mix(in srgb, var(--surface) 70%, var(--bg)); }
  .badge { width: 36px; height: 36px; border-radius: 50%; display: grid; place-items: center; font-weight: 700; font-size: 17px; }
  .tone-accent { color: var(--accent); background: color-mix(in srgb, var(--accent) 16%, transparent); }
  .tone-danger { color: var(--danger); background: color-mix(in srgb, var(--danger) 16%, transparent); }
  .tone-ok { color: var(--ok); background: color-mix(in srgb, var(--ok) 16%, transparent); }
  .tone-warn { color: var(--warn); background: color-mix(in srgb, var(--warn) 16%, transparent); }
  .tone-ink-2 { color: var(--ink-2); background: var(--surface-2); }
  .body { display: grid; gap: 6px; min-width: 0; justify-items: start; }
  .open { border: 0; background: none; padding: 0; color: var(--ink-2); font: inherit; font-size: var(--fs-md); line-height: 1.4; text-align: left; cursor: pointer; }
  .open strong { color: var(--ink); font-weight: 600; }
  .detail { margin: 0; color: var(--ink-3); font-size: var(--fs-sm); }
  .bubble { margin: 0; padding: 8px 12px; border-radius: 12px; background: var(--surface-2); color: var(--ink-2); font-size: var(--fs-sm); max-width: 100%; overflow-wrap: anywhere;
    display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 3; line-clamp: 3; overflow: hidden; }
  .asks { display: flex; gap: 8px; }
  .asks button { border: 0; border-radius: var(--r-full); background: var(--surface-2); color: var(--ink); padding: 7px 14px; font: inherit; font-size: var(--fs-sm); font-weight: 600; cursor: pointer; }
  .asks .primary { background: var(--accent); color: var(--on-accent); }
  .side { display: grid; justify-items: end; align-content: start; gap: 8px; }
  time { color: var(--ink-3); font-size: var(--fs-xs); }
  .dot { width: 8px; height: 8px; border-radius: 50%; background: var(--accent); }
  .more { width: 28px; height: 28px; opacity: 0; }
  .row:hover .more, .more:focus-visible { opacity: 1; }
  .quiet { padding: 20px; color: var(--ink-3); margin: 0; }
  .danger { color: var(--danger); }
  .side-panel { display: grid; align-content: start; gap: 16px; padding: 20px 24px; }
  .card { background: var(--surface); border-radius: var(--r-md); padding: 12px 16px; display: grid; gap: 4px; }
  .card header { display: flex; align-items: center; justify-content: space-between; padding: 4px 0 6px; }
  .card h2 { margin: 0; font-size: var(--fs-md); }
  .card a { color: var(--accent); font-size: var(--fs-sm); text-decoration: none; font-weight: 600; }
  .toggle { display: flex; align-items: center; gap: 12px; min-height: 40px; color: var(--ink-2); font-size: var(--fs-md); cursor: pointer; }
  .toggle span { flex: 1; }
  .quiet-line { display: flex; gap: 10px; align-items: center; margin: 0; color: var(--ink-3); font-size: var(--fs-sm); }
  .footnote { margin: 0; color: var(--ink-3); font-size: var(--fs-xs); line-height: 1.5; }
  .scrim { position: fixed; inset: 0; z-index: 40; border: 0; background: rgb(0 0 0 / .25); }
  .menu { position: fixed; z-index: 41; width: 300px; display: grid; gap: 2px; padding: 8px; border-radius: var(--r-md); background: var(--surface); box-shadow: var(--shadow-pop); animation: chorus-fade-in var(--t-fast) var(--ease); }
  .menu.center { left: 50%; top: 30%; transform: translateX(-50%); }
  .menu-title { margin: 4px 8px 8px; font-weight: 600; font-size: var(--fs-sm); color: var(--ink); }
  .menu button { display: flex; align-items: center; gap: 12px; border: 0; border-radius: 10px; background: none; color: var(--ink); padding: 10px 8px; font: inherit; font-size: var(--fs-md); text-align: left; cursor: pointer; }
  .menu button:hover { background: var(--surface-2); }
  .menu button span { display: grid; gap: 2px; }
  .menu small { color: var(--ink-3); font-size: var(--fs-xs); }
  @media (max-width: 1279px) {
    .notifications-layout { grid-template-columns: minmax(0, 1fr); }
    .inbox { border-right: 0; }
    .panel-toggle { display: grid; }
    .side-panel { display: none; }
    .side-panel.open { display: grid; position: fixed; inset: auto 0 0; z-index: 35; background: var(--bg); border-top: 1px solid var(--line); padding-bottom: calc(80px + env(safe-area-inset-bottom)); }
    .mark-all { padding: 8px 10px; }
  }
  @media (max-width: 599px) {
    .notifications-layout { margin: calc(-1 * var(--s-4)) calc(-1 * var(--s-4)) 0; }
    .mark-all { font-size: 0; gap: 0; width: 40px; height: 40px; padding: 0; justify-content: center; background: none; }
    .more { opacity: 1; }
  }
</style>
