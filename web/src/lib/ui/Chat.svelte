<script lang="ts">
  import { onDestroy, tick } from 'svelte';
  import { core, type Composed, type Entity } from '../core';
  import RichText from './RichText.svelte';
  import { channels, contentWarningsAutoExpand, customEmojis, lastRead, memberMarks, members, messageById, messagePage, pinnedMessages, reactions, readPerMember, segmentParsing, spaces, threadSummaries, unread, type MessageRow, type QuoteValue, type SnapshotItem, type TextRange } from '../data';
  import { activeViewers, memberVisible } from '../hidden';
  import { router } from '../router.svelte';
  import { snapshot } from '../selection';
  import { editedSegments, segmentMarkup } from '../segments';
  import { sync, type Projection } from '../sync/client';
  import { flushUploads, imageThumbnail, stageBlob } from '../sync/uploads';
  import Message from './Message.svelte';
  import AvatarImage from './AvatarImage.svelte';
  import EmojiImage from './EmojiImage.svelte';
  import SpaceRail from './SpaceRail.svelte';
  import { authorCards, listSpaces, openDm, spaceTitle, type SpaceInfo } from '../spaces';
  import { carryReply, takeReply } from '../replyElsewhere';
  import ChannelPermissions from './ChannelPermissions.svelte';
  import SpaceRoles from './SpaceRoles.svelte';
  import { selfMember, type MemberRow } from '../data';
  import { apiBase } from '../sync/device';
  import { apiFetch } from '../http';

  let { projection, dark, channelId, focusId }: { projection: Projection; dark: boolean; channelId?: string; focusId?: string } = $props();

  const ss = $derived(spaces(projection));
  const allChannels = $derived(channels(projection));
  // `#/chat/space:<id>` opens a space by id (a DM just created may not have its channel synced yet)
  const current = $derived(
    allChannels.find((c) => c.id === channelId) ??
      (channelId?.startsWith('space:')
        ? allChannels.find((c) => c.space_id === channelId.slice(6) && !c.archived && c.kind !== 'thread')
        : undefined) ??
      allChannels.find((c) => !c.archived && c.kind !== 'thread'),
  );
  const space = $derived(ss.find((s) => s.id === current?.space_id) ?? ss[0]);
  const spaceChannels = $derived(allChannels.filter((c) => c.space_id === space?.id && !c.archived && c.kind !== 'thread'));
  // only the newest pages are built and in the DOM; scrolling up adds older ones (SPEC §9: pages
  // of 100, and a 50k-message channel's first screen within 150 ms)
  const PAGE = 100;
  let shown = $state(PAGE);
  const page = $derived(current ? messagePage(projection, current.id, shown) : { rows: [], ids: [], total: 0 });
  const msgs = $derived(page.rows);
  const threads = $derived(threadSummaries(projection));
  const threadParent = $derived.by(() => {
    if (current?.kind !== 'thread' || !current.parent_message_id) return undefined;
    const parent = messageById(projection, current.parent_message_id);
    return allChannels.some((c) => c.id === parent?.channel_id && c.space_id === current.space_id) ? parent : undefined;
  });
  const parentChannel = $derived(allChannels.find((c) => c.id === threadParent?.channel_id));
  // other accounts' members in shared spaces and DMs come as author cards (spaces.ts)
  let cards = $state<MemberRow[]>([]);
  let history = $state<(Pick<MessageRow, 'id' | 'channel_id' | 'authors' | 'text' | 'cw' | 'visibility' | 'occurred_at'> & { space_id: string }) | null>(null);
  let historyError = $state('');
  let historyRevealed = $state(false);
  let directory = $state(new Map<string, SpaceInfo>());
  const people = $derived(new Map([...cards, ...members(projection)].map((m) => [m.id, m])));
  const emojiRows = $derived(customEmojis(projection));
  const emojiById = $derived(new Map(emojiRows.map((e) => [e.id, e])));
  const activeEmoji = $derived(emojiRows.filter((e) => !e.deleted));
  const scopeOf = (spaceId: string) => `space:${spaceId}`;
  const scope = $derived(space ? scopeOf(space.id) : '');
  const parseSegments = $derived(segmentParsing(projection, sync.accountId));
  const cwAutoExpand = $derived(contentWarningsAutoExpand(projection, sync.accountId));

  // who speaks by default: the primary fronter, else the first one fronting (SPEC §5.2)
  const fronting = $derived(projection.fronts[sync.accountId]?.current ?? []);
  const frontingMembers = $derived(fronting.filter((e) => e.subject_type === 'member')
    .map((e) => ({ member_id: e.subject_id, is_primary: !!e.is_primary, level: e.level })));
  // a person account always speaks as its self member (D-003)
  const self = $derived(selfMember(projection));
  let viewingAs = $state<string | null>(null);
  const activeMembers = $derived(activeViewers(fronting));
  const visibleMsgs = $derived(msgs.filter((m) => memberVisible(m, space?.kind, activeMembers, viewingAs)));
  // this account's autoproxy for the channel (D-074): pref `autoproxy:<channel id>`
  type Autoproxy = 'off' | 'front' | 'latch' | 'member';
  const autoproxyKey = $derived(current ? `autoproxy:${current.id}` : '');
  const autoproxy = $derived.by((): { mode: Autoproxy; member?: string } => {
    const rows = projection.rows.pref ?? {};
    const v = (rows[`${sync.accountId}||${autoproxyKey}`] ?? rows[`||${autoproxyKey}`])?.fields.value as { mode?: string; member?: string } | undefined;
    const mode = (['off', 'latch', 'member'].includes(v?.mode ?? '') ? v?.mode : 'front') as Autoproxy;
    return { mode, member: typeof v?.member === 'string' ? v.member : undefined };
  });
  function setAutoproxy(mode: Autoproxy, member?: string) {
    sync.create('pref.set', sync.accountScope, null, { device: '', key: autoproxyKey, value: member ? { mode, member } : { mode } });
  }
  // the speaker chip before anyone picks: one rule in core (SPEC §5.2)
  const defaults = $derived(core.defaultSpeaker({
    mode: autoproxy.mode, member: autoproxy.member ?? null, self_member: self?.id ?? null,
    fronting: frontingMembers,
    last_authors: msgs.findLast((m) => m.account_id === sync.accountId && !m.deleted)?.authors ?? [],
    members: members(projection).filter((m) => !m.deleted).map((m) => m.id),
  }));
  let chosen = $state<string | null>(null);
  const speaker = $derived(chosen ?? defaults[0] ?? null);
  const speakers = $derived(
    [...people.values()].filter((m) => !m.deleted).map((m) => ({ member_id: m.id, sigils: m.sigils, proxy_tags: m.proxy_tags })),
  );
  const names = $derived({
    mentions: Object.fromEntries(
      [...people.values()].filter((m) => !m.deleted).map((m) => [m.name.toLowerCase(), { target_type: 'member', target_id: m.id }]),
    ),
    emoji: Object.fromEntries([
      ...activeEmoji.flatMap((e) => e.aliases.map((alias) => [alias, e.id])),
      ...activeEmoji.map((e) => [e.name, e.id]), // a primary name wins over an alias collision
    ]),
  });

  let draft = $state('');
  let cw = $state('');
  let visibilityMode = $state<'all' | 'members' | 'system_only'>('all');
  let visibleTo = $state<string[]>([]);
  let pending = $state<{ file: File; alt: string; spoiler: boolean }[]>([]);
  let uploadError = $state('');
  let fileInput: HTMLInputElement | undefined = $state();
  let replyTo = $state<MessageRow | null>(null);
  let quoting = $state<QuoteValue | null>(null);
  let editing = $state<MessageRow | null>(null);
  let editingParts = $state<string[] | null>(null);
  let forwarding = $state<SnapshotItem[] | null>(null);
  let forwardingSensitive = false;
  let quoteSourceSpaceId = '';
  let quoteSensitive = false;
  let keepQuoteOnNavigation = false;
  let selecting = $state(false);
  let selectedIds = $state(new Set<string>());
  let showPins = $state(false);
  // messages waiting out slow mode (D-076): not in the projection, so follow the sync client
  let held = $state(sync.heldUntil);
  // the sync client isn't reactive state: what the template shows of it is copied here on change
  let syncLive = $state(sync.status === 'live');
  let windowed = $state(sync.windowed);
  const unheld = sync.subscribe(() => {
    held = sync.heldUntil;
    syncLive = sync.status === 'live';
    windowed = sync.windowed;
  });
  onDestroy(unheld);
  let picking = $state(false);
  let box: HTMLTextAreaElement | undefined = $state();
  let emojiPicker = $state(false);
  let emojiFile: File | null = $state(null);
  let emojiName = $state('');
  let emojiAliases = $state('');
  let emojiCategory = $state('Custom');
  let emojiError = $state('');
  let emojiBitmap: ImageBitmap | null = null;
  let emojiCropReady = $state(false);
  let emojiCanvas: HTMLCanvasElement | undefined = $state();
  let emojiZoom = $state(1);
  let emojiX = $state(0);
  let emojiY = $state(0);
  onDestroy(() => emojiBitmap?.close());
  async function chooseEmoji(file: File | null) {
    emojiBitmap?.close(); emojiBitmap = null; emojiCropReady = false;
    emojiFile = file;
    if (!file || file.type === 'image/gif') return;
    try {
      emojiBitmap = await createImageBitmap(file);
      emojiZoom = 1; emojiX = emojiY = 0; emojiCropReady = true;
    } catch { emojiError = 'Could not read this image.'; }
  }
  $effect(() => {
    void emojiCropReady;
    const image = emojiBitmap;
    const canvas = emojiCanvas;
    if (!image || !canvas) return;
    const scale = Math.max(128 / image.width, 128 / image.height) * emojiZoom;
    const width = image.width * scale;
    const height = image.height * scale;
    const x = (128 - width) / 2 + emojiX * (width - 128) / 2;
    const y = (128 - height) / 2 + emojiY * (height - 128) / 2;
    const context = canvas.getContext('2d');
    context?.clearRect(0, 0, 128, 128);
    context?.drawImage(image, x, y, width, height);
  });
  const completion = $derived.by(() => {
    const before = draft.slice(0, box?.selectionStart ?? draft.length);
    const match = before.match(/(?:^|\s):([a-z0-9_]{1,32})$/);
    return match ? activeEmoji.filter((e) => [e.name, ...e.aliases].some((name) => name.startsWith(match[1]))).slice(0, 8) : [];
  });

  function insertEmoji(name: string) {
    const at = box?.selectionStart ?? draft.length;
    const before = draft.slice(0, at);
    const match = before.match(/(?:^|\s):([a-z0-9_]{1,32})$/);
    const from = match ? at - match[1].length - 1 : at;
    draft = `${draft.slice(0, from)}:${name}: ${draft.slice(at)}`;
    emojiPicker = false;
    queueMicrotask(() => { box?.focus(); box?.setSelectionRange(from + name.length + 3, from + name.length + 3); });
  }

  async function addEmoji() {
    emojiError = '';
    const name = emojiName.trim();
    if (!emojiFile || !/^[a-z0-9_]{2,32}$/.test(name)) { emojiError = 'Choose an image and a 2–32 character lowercase name.'; return; }
    if (activeEmoji.some((e) => e.name === name)) { emojiError = 'That emoji name is already in use.'; return; }
    if (!['image/png', 'image/webp', 'image/gif'].includes(emojiFile.type)) { emojiError = 'Use PNG, WebP, or GIF.'; return; }
    try {
      let blob: Blob;
      if (emojiFile.type === 'image/gif') {
        const image = await createImageBitmap(emojiFile);
        if (image.width !== image.height || image.width > 128) throw new Error('Animated GIFs must already be square and at most 128 px.');
        image.close();
        blob = emojiFile;
      } else {
        if (!emojiCropReady || !emojiCanvas) throw new Error('Image crop is not ready yet.');
        blob = await new Promise<Blob>((resolve, reject) => emojiCanvas!.toBlob((value) => value ? resolve(value) : reject(new Error('Image crop failed')), 'image/webp', 0.85));
      }
      if (blob.size > 256 * 1024) throw new Error('Cropped emoji exceeds 256 KB.');
      const staged = await stageBlob(blob, blob.type, sync.accountId);
      sync.create('emoji.create', 'server', sync.newId(), {
        name,
        aliases: emojiAliases.split(',').map((a) => a.trim()).filter((a) => /^[a-z0-9_]{2,32}$/.test(a)),
        category: emojiCategory.trim() || 'Custom',
        blob_hash: staged.hash,
        is_animated: emojiFile.type === 'image/gif',
      });
      if (sync.status === 'live') void flushUploads(sync.device);
      emojiName = emojiAliases = '';
      emojiFile = null;
      emojiBitmap?.close(); emojiBitmap = null; emojiCropReady = false;
    } catch (error) { emojiError = String(error); }
  }
  let activeChannelId: string | undefined;
  $effect(() => {
    if (current?.id === activeChannelId) return;
    activeChannelId = current?.id;
    replyTo = editing = forwarding = null;
    if (!keepQuoteOnNavigation) quoting = null;
    keepQuoteOnNavigation = false;
    editingParts = null;
    selecting = false;
    selectedIds = new Set();
    chosen = null;
    viewingAs = null;
    cw = '';
    visibilityMode = 'all';
    visibleTo = [];
    replyIn = null;
    older = [];
    olderDone = false;
    olderError = '';
    // a reply started elsewhere ("Reply in…", "Reply privately") lands here
    if (current) replyTo = takeReply(current.id, current.space_id);
  });

  // a browser tab keeps a window of messages (SYNC §6.5): older history comes over REST, on
  // request, shown read-only above what this device holds
  interface OlderMessage { id: string; authors: string[]; text: string; entities: Entity[]; cw: string | null; occurred_at: number }
  let older = $state<OlderMessage[]>([]);
  let olderDone = $state(false);
  let olderBusy = $state(false);
  let olderError = $state('');
  async function loadOlder() {
    if (!current) return;
    const channel = current.id;
    const before = older[0]?.occurred_at ?? msgs[0]?.occurred_at ?? Date.now();
    olderBusy = true;
    olderError = '';
    try {
      const r = await apiFetch(`${apiBase()}/channels/${encodeURIComponent(channel)}/messages?before=${before}&limit=50`, {
        headers: { authorization: `Bearer ${sync.device?.session ?? ''}` },
      });
      if (current?.id !== channel) return;
      if (!r.ok) {
        olderError = `The server couldn't give older messages (${r.status}); try again.`;
        return;
      }
      const items = ((await r.json()) as { items: OlderMessage[] }).items;
      const have = new Set(msgs.map((m) => m.id));
      older = [...items.filter((m) => !have.has(m.id)), ...older];
      olderDone = items.length < 50;
    } catch {
      // the server went away mid-request (R29): say so, the button stays for another try
      if (current?.id === channel) olderError = "Couldn't reach the server for older messages; try again when it's back.";
    } finally {
      olderBusy = false;
    }
  }

  // Reply elsewhere (SPEC §5.3): the reply goes to another channel, thread or DM and links back
  // (a reference card, for readers who can see the original)
  let replyIn = $state<MessageRow | null>(null);
  const replyTargets = $derived(
    allChannels
      .filter((c) => c.id !== current?.id && !c.archived)
      .map((c) => ({ id: c.id, label: `${ss.find((x) => x.id === c.space_id)?.name ?? 'DM'} › ${c.kind === 'thread' ? 'thread' : '#'}${c.name}` }))
      .sort((a, b) => a.label.localeCompare(b.label)),
  );
  function replyElsewhere(target: string) {
    if (!replyIn || !target) return;
    carryReply(replyIn, target);
    replyIn = null;
    location.hash = `#/chat/${target}`;
  }
  // Reply privately: the member DM with the author (internal space), else the DM with the
  // author's account; made if there isn't one yet
  async function replyPrivately(m: MessageRow) {
    if (!current || !space) return;
    if (space.kind === 'internal') {
      const author = m.authors[0];
      if (!speaker || !author || author === speaker) return;
      const pair = [speaker, author];
      let dm = allChannels.find((c) => c.space_id === space.id && c.kind === 'member_dm' && !c.archived
        && c.member_ids?.length === 2 && pair.every((id) => c.member_ids?.includes(id)));
      const target = dm?.id ?? sync.newId();
      if (!dm) {
        const name = pair.map((id) => people.get(id)?.name ?? '?').join(' & ');
        sync.create('channel.create', scope, target, { space_id: space.id, kind: 'member_dm', name, member_ids: pair });
      }
      carryReply(m, target);
      location.hash = `#/chat/${target}`;
    } else if (m.account_id && m.account_id !== sync.accountId) {
      const dm = await openDm(m.account_id);
      carryReply(m, `space:${dm}`);
      location.hash = `#/chat/space:${dm}`;
    }
  }

  const preview: Composed | null = $derived(draft.trim() && !editing ? core.compose(draft, speakers, { segments: parseSegments }, chosen ? [chosen] : defaults, names) : null);
  const previewNames = $derived(
    preview ? preview.segments.map((s) => s.authors.map((a) => people.get(a)?.name ?? '?').join(' & ')).join(' → ') : '',
  );

  function addFiles(files: FileList | File[]) {
    pending = [...pending, ...[...files].map((file) => ({ file, alt: '', spoiler: false }))];
  }

  function dropFiles(e: DragEvent) {
    if (!e.dataTransfer?.files.length) return;
    e.preventDefault();
    addFiles(e.dataTransfer.files);
  }

  function pasteFiles(e: ClipboardEvent) {
    const files = [...(e.clipboardData?.files ?? [])];
    if (!files.length) return;
    e.preventDefault();
    addFiles(files);
  }

  async function send() {
    if (!current) return;
    if (editing) {
      const result = editingParts
        ? editedSegments(editingParts, editing.segments, names)
        : { rich: core.parseMarkup(draft, names), segments: [{ offset: 0, length: 0, authors: [...editing.authors] }] };
      if (!result.rich.text.trim()) return;
      if (!editingParts) result.segments[0].length = result.rich.text.length;
      sync.create('message.edit', scope, editing.id, {
        message_id: editing.id, text: result.rich.text, entities: result.rich.entities, segments: result.segments,
      });
      editing = null;
      editingParts = null;
      draft = '';
      return;
    }
    if (!preview && !pending.length) return;
    if (visibilityMode === 'members' && !visibleTo.length) return;
    const authors = preview?.authors ?? (chosen ? [chosen] : defaults);
    if (!authors.length) return;
    const attachmentIds: string[] = [];
    try {
      for (const item of pending) {
        const original = await stageBlob(item.file, item.file.type, sync.accountId);
        const thumb = await imageThumbnail(item.file);
        const thumbnail = thumb ? await stageBlob(thumb, thumb.type, sync.accountId) : null;
        const id = sync.newId();
        sync.create('attachment.create', scope, id, {
          blob_hash: original.hash, filename: item.file.name, mime: original.mime, size: item.file.size,
          ...(thumbnail ? { thumb_blob_hash: thumbnail.hash } : {}),
          alt_text: item.alt, is_spoiler: item.spoiler,
        });
        attachmentIds.push(id);
      }
      uploadError = '';
    } catch (error) {
      uploadError = `Could not queue the attachment: ${String(error)}`;
      return;
    }
    sync.create('message.send', scope, sync.newId(), {
      channel_id: current.id,
      authors,
      text: preview?.rich.text ?? '',
      entities: preview?.rich.entities ?? [],
      ...(preview && preview.segments.length > 1 ? { segments: preview.segments } : {}),
      ...(attachmentIds.length ? { attachments: attachmentIds } : {}),
      ...(replyTo ? { reply_to: replyTo.id } : {}),
      ...(quoting ? { quote: quoting } : {}),
      ...(cw.trim() ? { cw: cw.trim() } : {}),
      ...(visibilityMode === 'members' ? { visibility: { mode: 'members', member_ids: visibleTo } } :
        visibilityMode === 'system_only' ? { visibility: { mode: 'system_only' } } : {}),
      sent_offline: sync.status !== 'live',
    });
    draft = '';
    cw = '';
    visibilityMode = 'all';
    visibleTo = [];
    pending = [];
    if (sync.status === 'live') void flushUploads(sync.device);
    chosen = null;
    replyTo = null;
    quoting = null;
    quoteSourceSpaceId = '';
    quoteSensitive = false;
  }

  function onkey(e: KeyboardEvent) {
    if (e.key === 'Escape' && (emojiPicker || completion.length)) { emojiPicker = false; e.preventDefault(); return; }
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      void send();
    }
    if (e.key === 'Escape') cancel();
  }

  function cancel() {
    if (editing) draft = ''; // leaving an edit drops the edited text
    replyTo = quoting = editing = null;
    editingParts = null;
  }

  function startEdit(m: MessageRow) {
    editing = m;
    editingParts = m.segments.length > 1 ? segmentMarkup(m) : null;
    replyTo = quoting = null;
    // Segment editing keeps each part's authors; a single segment uses the plain composer.
    draft = editingParts ? '' : core.toMarkup({ text: m.text, entities: m.entities });
    if (!editingParts) box?.focus();
  }

  function toggleSelect(m: MessageRow) {
    selecting = true;
    const next = new Set(selectedIds);
    if (next.has(m.id)) next.delete(m.id);
    else next.add(m.id);
    selectedIds = next;
  }

  function selectedMessages(): MessageRow[] {
    return visibleMsgs.filter((m) => selectedIds.has(m.id) && !m.deleted);
  }

  function quoteSelected() {
    if (!current) return;
    const picked = selectedMessages();
    if (!picked.length) return;
    quoting = { items: picked.map((m) => snapshot(m, current.name)) };
    forwarding = null;
    quoteSourceSpaceId = current.space_id;
    quoteSensitive = picked.some((m) => !!m.visibility && m.visibility.mode !== 'all');
    replyTo = editing = null;
    editingParts = null;
    selecting = false;
    selectedIds = new Set();
    box?.focus();
  }

  function startForward(items: MessageRow[], range?: TextRange) {
    if (!current || !items.length) return;
    forwarding = items.map((m) => snapshot(m, current.name, m.id === range?.message_id ? range : undefined));
    quoting = null;
    replyTo = editing = null;
    editingParts = null;
    forwardingSensitive = items.some((m) => !!m.visibility && m.visibility.mode !== 'all');
    selecting = false;
    selectedIds = new Set();
  }

  function sharingOutward(targetId: string, targetSpaceId: string, sourceSpaceId: string, sensitive: boolean): boolean {
    if (targetId === current?.id) return false;
    const sourceSpace = ss.find((s) => s.id === sourceSpaceId);
    return (sourceSpace?.kind === 'internal' && targetSpaceId !== sourceSpaceId) || sensitive;
  }

  function moveQuote(targetId: string) {
    const target = allChannels.find((c) => c.id === targetId && !c.archived);
    if (!target || !quoting || target.id === current?.id) return;
    if (sharingOutward(target.id, target.space_id, quoteSourceSpaceId, quoteSensitive) &&
        !confirm('This quote shares a snapshot of the selected content with another channel. Continue?')) return;
    keepQuoteOnNavigation = true;
    router.go(`/chat/${target.id}`);
  }

  function forwardTo(targetId: string) {
    const target = allChannels.find((c) => c.id === targetId);
    const items = forwarding;
    if (!target || target.archived || !items || !speaker || !current) return;
    if (sharingOutward(target.id, target.space_id, current.space_id, forwardingSensitive) &&
        !confirm('This forward shares a snapshot of the selected content with another channel. Continue?')) return;
    const targetScope = scopeOf(target.space_id);
    const attachments = items.flatMap((item) => item.attachments ?? []).map((a) => {
      const id = sync.newId();
      sync.create('attachment.create', targetScope, id, {
        blob_hash: a.blob_hash, thumb_blob_hash: a.thumb_blob_hash, filename: a.filename,
        mime: a.mime, size: a.size, alt_text: a.alt_text, is_spoiler: a.is_spoiler,
      });
      return id;
    });
    sync.create('message.forward', targetScope, sync.newId(), {
      channel_id: target.id,
      authors: [speaker],
      text: '',
      entities: [],
      forward_of_id: items[0].message_id,
      forward_snapshot: items,
      ...(attachments.length ? { attachments } : {}),
    });
    forwarding = null;
    router.go(`/chat/${target.id}`);
  }

  let newChannel = $state('');
  // may this account add channels and change permissions here? (perms.rs: the owner, admins; in a DM, both)
  // slow mode (OPEN_QUESTIONS Q17 default): the channel's settings.slow_mode_s, enforced by the
  // server when a send arrives; managers are exempt
  const slowMode = $derived.by(() => {
    const v = (projection.rows.channel?.[current?.id ?? '']?.fields.settings as { slow_mode_s?: unknown } | undefined)?.slow_mode_s;
    return typeof v === 'number' && v > 0 ? v : 0;
  });
  function setSlowMode(seconds: number) {
    if (!current) return;
    const old = (projection.rows.channel?.[current.id]?.fields.settings ?? {}) as Record<string, unknown>;
    sync.create('channel.set', scope, current.id, { settings: { ...old, slow_mode_s: seconds } });
  }
  // messages the server refused here (SYNC §7 "Sync issues"): the reason, the text to copy
  const notSent = $derived.by(() => {
    void projection;
    return sync.syncIssues().filter((i) => ['message.send', 'message.forward'].includes(i.kind) && i.payload.channel_id === current?.id);
  });
  const slowLabel = (s: number) => (s < 60 ? `${s} s` : s < 3600 ? `${s / 60} min` : `${s / 3600} h`);
  const canManage = $derived.by(() => {
    if (!space) return false;
    const info = directory.get(space.id);
    if (info?.guest) return false;
    if (space.kind === 'dm') return true;
    const owner = info?.owner_account_id ?? (space.kind === 'internal' ? sync.accountId : undefined);
    return owner === sync.accountId || projection.rows.space_member?.[`${space.id}|${sync.accountId}`]?.fields.role === 'admin';
  });

  function addChannel(e: SubmitEvent) {
    e.preventDefault();
    const name = newChannel.trim().toLowerCase().replace(/\s+/g, '-');
    if (!name || !space) return;
    const id = sync.newId();
    sync.create('channel.create', scope, id, { space_id: space.id, kind: 'text', name });
    newChannel = '';
    router.go(`/chat/${id}`);
  }

  function openThread(m: MessageRow) {
    if (!current) return;
    const existing = allChannels.find((c) => c.kind === 'thread' && c.space_id === current.space_id && c.parent_message_id === m.id);
    const id = existing?.id ?? m.id; // one stable channel id per parent, even across devices
    if (existing?.archived) sync.create('channel.unarchive', scope, id, {});
    else if (!existing) sync.create('channel.create', scope, id, {
      space_id: current.space_id,
      kind: 'thread',
      name: 'Thread',
      parent_message_id: m.id,
    });
    router.go(`/chat/${id}`);
  }

  let shownFor = '';
  $effect.pre(() => {
    if (current?.id !== shownFor) {
      shownFor = current?.id ?? '';
      shown = PAGE;
    }
  });
  // older messages this device has that aren't built yet
  const first = $derived(Math.max(0, page.total - msgs.length));
  // a channel's first screen is on the page (web/perf measures opening a big channel, R24)
  $effect(() => {
    if (current && grouped.length) performance.mark(`chorus:channel-shown:${current.id}`);
  });
  const grouped = $derived(
    visibleMsgs.map((m, j) => {
      const prev = visibleMsgs[j - 1];
      const cont =
        !!prev && !prev.deleted && prev.authors.join() === m.authors.join() && m.occurred_at - prev.occurred_at < 300_000 && m.segments.length === 1;
      return { m, cont };
    }),
  );
  function showOlder() {
    if (!list || !first) return;
    const fromBottom = list.scrollHeight - list.scrollTop;
    shown += PAGE;
    // keep what the reader was looking at in place once the older page renders
    void tick().then(() => list && (list.scrollTop = list.scrollHeight - fromBottom));
  }
  // a search result deep in history: widen the window so the message is rendered
  $effect.pre(() => {
    if (!focusId) return;
    const at = page.ids.indexOf(focusId);
    if (at >= 0 && at < first) shown = page.total - at + PAGE / 2;
  });
  const pinned = $derived(
    current ? pinnedMessages(projection, current.id).filter((m) => memberVisible(m, space?.kind, activeMembers, viewingAs)) : [],
  );
  const reacts = $derived(reactions(projection));

  function react(m: MessageRow, emoji: string, on: boolean) {
    if (!speaker) return;
    // add and remove must carry the identical element key (LWW element set)
    sync.create(on ? 'reaction.add' : 'reaction.remove', scope, m.id, {
      target_type: 'message',
      target_id: m.id,
      emoji,
      member_id: speaker,
    });
  }

  // reading the channel moves the read mark forward (never backward, SYNC.md §5.5); only while
  // the page is actually visible, and again when it becomes visible
  let visible = $state(!document.hidden);
  $effect(() => {
    const on = () => (visible = !document.hidden);
    document.addEventListener('visibilitychange', on);
    return () => document.removeEventListener('visibilitychange', on);
  });
  $effect(() => {
    if (!current || !visible) return;
    const latest = visibleMsgs.findLast((m) => !m.deleted);
    if (!latest) return;
    // the account, and with "track reading per member" whoever is fronting (core decides)
    const marks = new Map(marksHere.map((m) => [m.member, m.at]));
    for (const reader of core.readReaders(perMember, frontingMembers)) {
      const at = reader === '' ? lastRead(projection, current.id, sync.accountId) : marks.get(reader) ?? 0;
      if (latest.occurred_at <= at) continue;
      sync.create('read.mark', scope, null, {
        channel_id: current.id,
        message_id: latest.id,
        message_at: latest.occurred_at,
        reader_member_id: reader,
      });
    }
  });
  const perMember = $derived(readPerMember(projection, sync.accountId));
  const marksHere = $derived(current ? memberMarks(projection, current.id, sync.accountId) : []);
  const unseenBy = (m: MessageRow): string[] => perMember && marksHere.length ? core.readUnseenBy(m.occurred_at, m.id, marksHere) : [];
  const color = (id: string) => core.adaptColor(people.get(id)?.color ?? '#A09184', dark);

  // notifications for this channel (NOTIFICATIONS §7): all · mentions · none, kept as an account
  // pref the server reads; DMs default to "all", other shared channels to "mentions"
  const notifyKey = $derived(current ? `notify_channel:${current.id}` : '');
  const notifyLevel = $derived.by(() => {
    const rows = projection.rows.pref ?? {};
    const v = (rows[`${sync.accountId}||${notifyKey}`] ?? rows[`||${notifyKey}`])?.fields.value;
    return typeof v === 'string' ? v : space?.kind === 'dm' ? 'all' : 'mentions';
  });
  function setNotifyLevel(level: string) {
    sync.create('pref.set', sync.accountScope, null, { device: '', key: notifyKey, value: level });
  }

  // who is in which space (names for DMs), refreshed when spaces come and go
  $effect(() => {
    void ss.length;
    listSpaces()
      .then((items) => (directory = new Map(items.map((i) => [i.id, i]))))
      .catch(() => {});
  });
  // author cards: fetched when entering a shared space or DM, and again when someone new speaks
  const strangers = $derived(
    space && space.kind !== 'internal'
      ? [...new Set(visibleMsgs.flatMap((m) => m.authors))].filter((a) => !people.has(a)).sort().join(',')
      : '',
  );
  let cardsFor = '';
  $effect(() => {
    const key = `${space?.id}|${strangers}`;
    if (!space || space.kind === 'internal' || key === cardsFor) return;
    cardsFor = key;
    authorCards(space.id)
      .then((c) => (cards = c.members))
      .catch(() => {});
  });

  let list: HTMLElement | undefined = $state();
  $effect(() => {
    if (focusId) return;
    void msgs.length;
    queueMicrotask(() => list?.scrollTo({ top: list.scrollHeight }));
  });
  $effect(() => {
    const id = focusId;
    if (!id || visibleMsgs.some((m) => m.id === id)) { history = null; historyError = ''; return; }
    const abort = new AbortController();
    history = null;
    historyRevealed = false;
    apiFetch(`${apiBase()}/messages/${encodeURIComponent(id)}`, {
      headers: { authorization: `Bearer ${sync.device?.session ?? ''}` }, signal: abort.signal,
    }).then(async (response) => {
      if (!response.ok) throw new Error(`History HTTP ${response.status}`);
      return response.json() as Promise<NonNullable<typeof history>>;
    }).then((found) => {
      if (found.channel_id === channelId) history = found;
      else historyError = 'This message belongs to another channel.';
    }).catch((error) => { if (!abort.signal.aborted) historyError = error instanceof Error ? error.message : String(error); });
    return () => abort.abort();
  });
  $effect(() => {
    if (!focusId || (!visibleMsgs.some((m) => m.id === focusId) && history?.id !== focusId)) return;
    queueMicrotask(() => list?.querySelector(`[data-message-id="${CSS.escape(focusId)}"]`)?.scrollIntoView({ block: 'center' }));
  });
</script>

<div class="chat">
  <aside class="channels" aria-label="Channels">
    <SpaceRail spaces={ss} channels={allChannels} currentId={space?.id} {directory} me={sync.accountId} />
    {#each spaceChannels as c (c.id)}
      {@const n = c.id === current?.id ? 0 : unread(projection, c.id, sync.accountId)}
      <a href="#/chat/{c.id}" class:on={c.id === current?.id} class:unread={n > 0}
        ># {c.name}{#if n}<span class="badge">{n}</span>{/if}</a
      >
    {/each}
    {#if space && canManage}
      <form onsubmit={addChannel}>
        <input bind:value={newChannel} placeholder="new channel" aria-label="New channel name" />
      </form>
    {/if}
  </aside>

  <section class="room" aria-label={current ? `#${current.name}` : 'Chat'} ondragover={(e) => { if (e.dataTransfer?.types.includes('Files')) e.preventDefault(); }} ondrop={dropFiles}>
    <header>
      {#if current?.kind === 'thread'}
        <a class="thread-back" href="#/chat/{parentChannel?.id ?? ''}">‹ #{parentChannel?.name ?? 'channel'}</a>
        <h1>Thread</h1>
      {:else}
        <h1>{space?.kind === 'dm' ? spaceTitle(space, directory.get(space.id), sync.accountId) : `# ${current?.name ?? '…'}`}</h1>
      {/if}
      {#if current?.topic}<span class="topic">{current.topic}</span>{/if}
      <a class="search-link" href="#/search">Search</a>
      {#if space?.kind === 'internal'}
        <label class="view-as">Viewing as
          <select aria-label="Viewing as member" value={viewingAs ?? ''} onchange={(e) => (viewingAs = e.currentTarget.value || null)}>
            <option value="">Current front</option>
            {#each members(projection).filter((m) => !m.deleted && !m.archived) as person (person.id)}
              <option value={person.id}>{person.display_name ?? person.name}</option>
            {/each}
          </select>
        </label>
      {/if}
      <button class="pins" class:on={showPins} onclick={() => (showPins = !showPins)}>📌 {pinned.length}</button>
      {#if current}
        <details class="room-menu">
          <summary aria-label="Channel menu">⋯</summary>
          <div class="menu-items">
            <a href="#/stage/{current.id}">Stage… (screenshot)</a>
            <a href="#/trash/{current.id}">Show deleted</a>
            {#if space?.kind === 'shared' && canManage}
              <SpaceRoles {projection} spaceId={space.id} info={directory.get(space.id)} />
            {/if}
            {#if space && canManage && space.kind !== 'dm'}
              <label class="notify">Slow mode
                <select value={String(slowMode)} onchange={(e) => setSlowMode(Number(e.currentTarget.value))} aria-label="Slow mode for this channel">
                  {#each [0, 5, 30, 60, 300, 900, 3600] as s (s)}<option value={String(s)}>{s ? `one message every ${slowLabel(s)}` : 'off'}</option>{/each}
                </select>
              </label>
            {/if}
            {#if space && canManage && space.kind !== 'dm' && current.kind !== 'thread'}
              <ChannelPermissions {projection} channelId={current.id} spaceId={space.id} info={directory.get(space.id)} />
            {/if}
            {#if space && space.kind !== 'internal'}
              <label class="notify">Notify me
                <select value={notifyLevel} onchange={(e) => setNotifyLevel(e.currentTarget.value)} aria-label="Notifications for this channel">
                  <option value="all">for every message</option>
                  <option value="mentions">for mentions and replies</option>
                  <option value="none">never</option>
                </select>
              </label>
            {/if}
          </div>
        </details>
      {/if}
    </header>

    {#if showPins}
      <div class="pinned">
        {#each pinned as p (p.id)}
          <div class="pin"><strong>{p.authors.map((a) => people.get(a)?.name ?? '?').join(' & ')}</strong> {p.cw ? `Content warning: ${p.cw}` : p.text.slice(0, 140)}</div>
        {:else}
          <p class="muted">Nothing pinned yet.</p>
        {/each}
      </div>
    {/if}

    {#if current?.kind === 'thread'}
      <div class="thread-origin">
        <span>Original message</span>
        {#if threadParent && memberVisible(threadParent, space?.kind, activeMembers, viewingAs)}
          <strong>{threadParent.authors.map((a) => people.get(a)?.name ?? 'Someone').join(' & ')}</strong>
          <p>{threadParent.deleted ? 'Message deleted' : threadParent.cw ? `Content warning: ${threadParent.cw}` : threadParent.text.slice(0, 240)}</p>
        {:else}
          <p>Original message unavailable</p>
        {/if}
      </div>
    {/if}

    <div class="list" bind:this={list} onscroll={() => { if (list && list.scrollTop < 200) showOlder(); }}>
      {#if first}
        <button class="ghost older" onclick={showOlder}>Show older messages ({first})</button>
      {/if}
      {#if focusId && !visibleMsgs.some((m) => m.id === focusId)}
        {#if history && memberVisible(history, ss.find((s) => s.id === history?.space_id)?.kind, activeMembers, viewingAs)}
          <article class="history-message" data-message-id={history.id}>
            <span class="hint">From server history · {new Date(history.occurred_at).toLocaleString()}</span>
            <strong>{history.authors.map((id) => people.get(id)?.name ?? 'Someone').join(' & ')}</strong>
            {#if history.cw && !historyRevealed}
              <button onclick={() => (historyRevealed = true)}>Content warning: {history.cw} · Show content</button>
            {:else}<p>{history.text}</p>{/if}
          </article>
        {:else if historyError}<p class="hint" role="status">{historyError}</p>{/if}
      {/if}
      {#if windowed && current && !olderDone && !first}
        <button class="older-load" disabled={olderBusy || !syncLive} onclick={() => void loadOlder()}>
          {olderBusy ? 'Loading…' : 'Older messages (from the server)'}
        </button>
        {#if olderError}<p class="hint" role="status">{olderError}</p>{/if}
      {/if}
      {#each older as o (o.id)}
        <article class="older">
          <span class="who">{o.authors.map((id) => people.get(id)?.name ?? 'Someone').join(' & ')}</span>
          <time>{new Date(o.occurred_at).toLocaleString([], { dateStyle: 'short', timeStyle: 'short' })}</time>
          {#if o.cw}<div class="hint">Content warning: {o.cw}</div>{:else}<p><RichText text={o.text} entities={o.entities} emoji={emojiById} /></p>{/if}
        </article>
      {/each}
      {#each grouped as { m, cont } (m.id)}
        <Message
          {m}
          {cont}
          {people}
          {dark}
          lookup={(id) => { const found = messageById(projection, id); return found && memberVisible(found, space?.kind, activeMembers, viewingAs) ? found : undefined; }}
          mine={m.account_id === sync.accountId}
          onreply={() => { replyTo = m; quoting = null; editing = null; editingParts = null; box?.focus(); }}
          onreplyin={() => (replyIn = m)}
          unseen={unseenBy(m).map((id) => people.get(id)?.name ?? '?')}
          onreplyprivately={(space?.kind === 'internal' ? m.authors[0] !== speaker : m.account_id !== sync.accountId) ? () => void replyPrivately(m) : undefined}
          onquote={(q) => { quoting = q; forwarding = null; quoteSourceSpaceId = current?.space_id ?? ''; quoteSensitive = !!m.visibility && m.visibility.mode !== 'all'; replyTo = m; editing = null; editingParts = null; box?.focus(); }}
          onedit={() => startEdit(m)}
          ondelete={() => sync.create('message.delete', scope, m.id, {})}
          onrestore={() => sync.create('message.restore', scope, m.id, {})}
          onpin={() => sync.create(m.pinned ? 'message.unpin' : 'message.pin', scope, m.id, {}, { memberId: speaker ?? undefined })}
          onforward={(range) => startForward([m], range)}
          onselect={() => toggleSelect(m)}
          {selecting}
          selected={selectedIds.has(m.id)}
          thread={threads.get(m.id)}
          onthread={() => openThread(m)}
          reacts={reacts.get(m.id)}
          {speaker}
          {emojiById}
          {cwAutoExpand}
          heldUntil={held[m.id]?.until}
          oncancelheld={() => sync.cancelHeld(m.id)}
          onreact={(emoji, on) => react(m, emoji, on)}
        />
      {:else}
        <p class="empty">Say hello — messages here are only for your system.</p>
      {/each}
    </div>

    {#if selecting}
      <div class="bar">
        {selectedIds.size} selected
        <button onclick={quoteSelected} disabled={!selectedIds.size}>Quote bundle</button>
        <button onclick={() => startForward(selectedMessages())} disabled={!selectedIds.size}>Forward bundle</button>
        <button class="x" onclick={() => { selecting = false; selectedIds = new Set(); }} aria-label="Cancel selection">✕</button>
      </div>
    {/if}
    {#if forwarding}
      <div class="bar">
        Forward {forwarding.length} {forwarding.length === 1 ? 'item' : 'items'} to
        <select onchange={(e) => { forwardTo((e.currentTarget as HTMLSelectElement).value); e.currentTarget.value = ''; }} aria-label="Forward to channel">
          <option value="">choose…</option>
          {#each allChannels.filter((c) => !c.archived) as c (c.id)}<option value={c.id}># {c.name}</option>{/each}
        </select>
        {#if !speaker}<span>Pick a speaker first.</span>{/if}
        <button class="x" onclick={() => (forwarding = null)} aria-label="Cancel forward">✕</button>
      </div>
    {/if}
    {#if notSent.length}
      <ul class="not-sent" aria-label="Messages not sent">
        {#each notSent as issue (issue.id)}
          <li>
            <span class="why">Not sent: {issue.message}</span>
            <span class="text">{typeof issue.payload.text === 'string' ? issue.payload.text : ''}</span>
            <button onclick={() => void navigator.clipboard?.writeText(String(issue.payload.text ?? ''))}>Copy text</button>
            <button onclick={() => sync.dismissIssue(issue.id)}>Dismiss</button>
          </li>
        {/each}
      </ul>
    {/if}
    {#if slowMode && !canManage}<p class="hint slow">Slow mode: one message every {slowLabel(slowMode)} here.</p>{/if}
    {#if replyIn}
      <div class="reply-in" role="group" aria-label="Reply in another channel">
        Reply to {replyIn.authors.map((a) => people.get(a)?.name ?? '?').join(' & ')} in
        <select aria-label="Channel to reply in" onchange={(e) => replyElsewhere(e.currentTarget.value)}>
          <option value="">choose…</option>
          {#each replyTargets as t (t.id)}<option value={t.id}>{t.label}</option>{/each}
        </select>
        <button onclick={() => (replyIn = null)}>Cancel</button>
      </div>
    {/if}
    {#if replyTo || quoting || editing}
      <div class="bar">
        {#if editing}Editing message{:else if quoting}Quoting {#if 'items' in quoting}{quoting.items.length} {quoting.items.length === 1 ? 'message' : 'messages'}{:else}“{quoting.text.slice(0, 60)}”{/if}{:else if replyTo}Replying to {replyTo.authors.map((a) => people.get(a)?.name ?? '?').join(' & ')}{/if}
        {#if quoting}
          <select onchange={(e) => { moveQuote((e.currentTarget as HTMLSelectElement).value); e.currentTarget.value = ''; }} aria-label="Quote in another channel">
            <option value="">Quote in…</option>
            {#each allChannels.filter((c) => !c.archived && c.id !== current?.id) as c (c.id)}<option value={c.id}># {c.name}</option>{/each}
          </select>
        {/if}
        <button class="x" onclick={cancel} aria-label="Cancel">✕</button>
      </div>
    {/if}

    {#if !editing}
      <details class="chat-advanced">
        <summary>Advanced chat settings</summary>
        <p>Speaker parsing and content warning preferences are in <a href="#/settings">Settings</a>.</p>
        {#if !self}
          <label>Speaker in this channel
            <select aria-label="Autoproxy for this channel" value={autoproxy.mode}
              onchange={(e) => setAutoproxy(e.currentTarget.value as Autoproxy, e.currentTarget.value === 'member' ? (autoproxy.member ?? speaker ?? undefined) : undefined)}>
              <option value="front">Whoever is fronting</option>
              <option value="latch">Whoever spoke last here</option>
              <option value="member">Always one member</option>
              <option value="off">Nobody (pick each time)</option>
            </select>
          </label>
          {#if autoproxy.mode === 'member'}
            <label>Always
              <select aria-label="Sticky speaker" value={autoproxy.member ?? ''} onchange={(e) => setAutoproxy('member', e.currentTarget.value)}>
                {#each members(projection).filter((m) => !m.deleted && !m.archived) as person (person.id)}
                  <option value={person.id}>{person.display_name ?? person.name}</option>
                {/each}
              </select>
            </label>
          {/if}
        {/if}
        <label>Content warning <input aria-label="Content warning" bind:value={cw} placeholder="Optional label" /></label>
        <label>Visibility
          <select aria-label="Message visibility" bind:value={visibilityMode}>
            <option value="all">Everyone in this space</option>
            {#if space?.kind === 'internal'}<option value="members">Chosen members</option>{/if}
            {#if space?.kind !== 'internal'}<option value="system_only">Only my system</option>{/if}
          </select>
        </label>
        {#if visibilityMode === 'members'}
          <p class="hint">This is a view filter within your system, not a security boundary. Members fronting or co-conscious can see it.</p>
          <div class="visibility-members" aria-label="Members who can view this message">
            {#each members(projection).filter((m) => !m.deleted && !m.archived) as person (person.id)}
              <label><input type="checkbox" checked={visibleTo.includes(person.id)} onchange={(e) => {
                visibleTo = e.currentTarget.checked ? [...visibleTo, person.id] : visibleTo.filter((id) => id !== person.id);
              }} /> {person.display_name ?? person.name}</label>
            {/each}
          </div>
          {#if !visibleTo.length}<p class="hint" role="alert">Choose at least one member.</p>{/if}
        {/if}
      </details>
    {/if}
    {#if sync.device?.is_admin}
      <details class="chat-advanced emoji-manager">
        <summary>Manage custom emoji</summary>
        <div class="emoji-form">
          <input type="file" accept="image/png,image/webp,image/gif" aria-label="Emoji image" onchange={(e) => void chooseEmoji(e.currentTarget.files?.[0] ?? null)} />
          <input bind:value={emojiName} placeholder="name" aria-label="Emoji name" />
          <input bind:value={emojiAliases} placeholder="aliases, comma separated" aria-label="Emoji aliases" />
          <input bind:value={emojiCategory} placeholder="category" aria-label="Emoji category" />
          <button onclick={() => void addEmoji()}>Add emoji</button>
        </div>
        {#if emojiCropReady}
          <div class="emoji-crop">
            <canvas width="128" height="128" bind:this={emojiCanvas} aria-label="Emoji crop preview"></canvas>
            <label>Zoom <input type="range" min="1" max="3" step="0.05" bind:value={emojiZoom} /></label>
            <label>Left/right <input type="range" min="-1" max="1" step="0.05" bind:value={emojiX} /></label>
            <label>Up/down <input type="range" min="-1" max="1" step="0.05" bind:value={emojiY} /></label>
          </div>
        {/if}
        <p class="hint">PNG and WebP use the 128 px crop shown above. Animated GIFs must already be square and at most 128 px. Limit: 256 KB.</p>
        {#if emojiError}<p role="alert">{emojiError}</p>{/if}
        <div class="emoji-existing">
          {#each activeEmoji as e (e.id)}
            <span><EmojiImage hash={e.blob_hash} name={e.name} /> :{e.name}: <button onclick={() => sync.create('emoji.delete', 'server', e.id, {})} aria-label={`Retire ${e.name}`}>Retire</button></span>
          {/each}
        </div>
      </details>
    {/if}

    {#if pending.length}
      <div class="pending-files" aria-label="Attachments ready to send">
        {#each pending as item, i (item.file.name + i)}
          <div class="pending-file">
            <strong>{item.file.name}</strong>
            <input placeholder="Alt text" aria-label={`Alt text for ${item.file.name}`} bind:value={item.alt} />
            <label><input type="checkbox" bind:checked={item.spoiler} /> Spoiler</label>
            <button onclick={() => (pending = pending.filter((_, n) => n !== i))} aria-label={`Remove ${item.file.name}`}>✕</button>
          </div>
        {/each}
      </div>
    {/if}
    {#if uploadError}<p class="upload-error" role="alert">{uploadError}</p>{/if}
    <div class="composer">
      {#if !editing}
        <input class="file-input" type="file" multiple bind:this={fileInput} onchange={(e) => { addFiles(e.currentTarget.files ?? []); e.currentTarget.value = ''; }} aria-label="Choose attachments" />
        <button class="attach" onclick={() => fileInput?.click()} title="Attach files">＋</button>
      {/if}
      {#if !editing}<div class="chip-wrap">
        <button class="chip" onclick={() => (picking = !picking)} title="Speaking as">
          {#if speaker}
            <span class="avatar" style="--ring: {color(speaker).ring}"><AvatarImage hash={people.get(speaker)?.avatar_blob} glyph={people.get(speaker)?.sigils[0] ?? people.get(speaker)?.name[0] ?? '?'} name={people.get(speaker)?.name} /></span>
          {:else}
            <span class="avatar">?</span>
          {/if}
        </button>
        {#if picking}
          <div class="picker" role="listbox">
            {#each [...people.values()].filter((p) => !p.deleted && !p.archived) as p (p.id)}
              <button onclick={() => { chosen = p.id; picking = false; }} style="color: {color(p.id).name}">{p.sigils[0] ?? ''} {p.name}</button>
            {/each}
          </div>
        {/if}
      </div>{/if}
      <div class="input">
        {#if preview && !editing}<span class="as">as {previewNames || 'no one — pick a speaker'}</span>{/if}
        {#if editing && editingParts}
          {#each editingParts as part, i (i)}
            <label class="segment-edit">
              <span>{editing.segments[i].authors.map((a) => people.get(a)?.name ?? 'Someone').join(' & ')}</span>
              <textarea value={part} oninput={(e) => (editingParts![i] = e.currentTarget.value)} onkeydown={onkey} rows="2" aria-label={`Edit segment ${i + 1}`}></textarea>
            </label>
          {/each}
        {:else}
          <textarea
            bind:this={box}
            bind:value={draft}
            onkeydown={onkey}
            onpaste={pasteFiles}
            rows="1"
            placeholder={!current ? '' : space?.kind === 'dm' ? `Message ${spaceTitle(space, directory.get(space.id), sync.accountId)}` : `Message #${current.name}`}
            aria-label="Message"
          ></textarea>
        {/if}
        {#if completion.length && !editing}
          <div class="emoji-suggestions" role="listbox" aria-label="Emoji autocomplete">
            {#each completion as e (e.id)}
              <button onclick={() => insertEmoji(e.name)}><EmojiImage hash={e.blob_hash} name={e.name} /> :{e.name}:</button>
            {/each}
          </div>
        {/if}
      </div>
      {#if !editing}<div class="chip-wrap">
        <button class="chip" onclick={() => (emojiPicker = !emojiPicker)} title="Custom emoji" aria-label="Custom emoji">☺</button>
        {#if emojiPicker}
          <div class="picker emoji-pick" role="listbox" aria-label="Custom emoji">
            {#each activeEmoji as e (e.id)}
              <button onclick={() => insertEmoji(e.name)}><EmojiImage hash={e.blob_hash} name={e.name} /> :{e.name}: <small>{e.category}</small></button>
            {/each}
          </div>
        {/if}
      </div>{/if}
      <button class="send" onclick={() => void send()} disabled={editing ? (editingParts ? !editingParts.some((part) => part.trim()) : !draft.trim()) : (!preview && !pending.length) || (!preview?.authors.length && !speaker) || (visibilityMode === 'members' && !visibleTo.length)}>{editing ? 'Save' : 'Send'}</button>
    </div>
  </section>
</div>

<style>
  .search-link { color: var(--accent); font-size: var(--fs-sm); text-decoration: none; }
  .history-message { display: grid; gap: var(--s-2); padding: var(--s-4); border: 1px solid var(--line); border-radius: var(--r-sm); background: var(--surface-2); }
  .history-message button { width: fit-content; font: inherit; border: 1px solid var(--line); border-radius: var(--r-sm); background: var(--surface); color: var(--ink); padding: var(--s-2); cursor: pointer; }
  .history-message p { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; }
  .view-as { display: flex; gap: var(--s-1); align-items: center; color: var(--ink-3); font-size: var(--fs-xs); }
  .view-as select, .chat-advanced select, .chat-advanced input:not([type='checkbox']) { font: inherit; color: var(--ink); background: var(--surface-2); border: 1px solid var(--line); border-radius: var(--r-sm); padding: var(--s-1); }
  .visibility-members { display: flex; flex-wrap: wrap; gap: var(--s-2); max-height: 8rem; overflow: auto; }
  .emoji-form, .emoji-existing { display: flex; flex-wrap: wrap; gap: var(--s-2); padding: var(--s-2) 0; }
  .emoji-crop { display: flex; align-items: center; flex-wrap: wrap; gap: var(--s-2); }
  .emoji-crop canvas { width: 128px; height: 128px; border: 1px solid var(--line); border-radius: var(--r-sm); }
  .emoji-crop label { display: inline-flex; align-items: center; gap: var(--s-1); }
  .emoji-form input { min-width: 10ch; max-width: 22ch; }
  .emoji-existing span { display: inline-flex; align-items: center; gap: var(--s-1); }
  .hint { color: var(--ink-3); font-size: var(--fs-xs); }
  .emoji-suggestions { display: flex; flex-wrap: wrap; gap: var(--s-1); padding: var(--s-1); }
  .emoji-suggestions button, .emoji-pick button { display: inline-flex; align-items: center; gap: var(--s-1); }
  .file-input { display: none; }
  .pending-files { display: grid; gap: var(--s-1); padding: var(--s-2); background: var(--surface-2); border-radius: var(--r-md); }
  .pending-file { display: flex; align-items: center; flex-wrap: wrap; gap: var(--s-2); font-size: var(--fs-sm); }
  .pending-file strong { max-width: 20ch; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .pending-file input:not([type='checkbox']) { min-width: 10ch; flex: 1; }
  .pending-file button, .attach { border: 0; background: none; color: var(--accent); cursor: pointer; }
  .upload-error { color: var(--danger, #b33); font-size: var(--fs-sm); }
  .chat {
    display: grid;
    grid-template-columns: 180px 1fr;
    gap: var(--s-4);
    height: calc(100dvh - 140px);
  }
  @media (max-width: 640px) {
    .chat {
      grid-template-columns: 1fr;
      grid-template-rows: auto 1fr;
      height: calc(100dvh - 190px);
    }
    /* `.chat .channels` so this beats the base rule below (same specificity would lose to it) */
    .chat .channels {
      display: flex;
      align-items: center;
      gap: var(--s-2);
      overflow-x: auto;
    }
    .channels form {
      display: none;
    }
  }
  .channels {
    display: grid;
    align-content: start;
    gap: 2px;
  }
  .channels a {
    color: var(--ink-2);
    text-decoration: none;
    padding: var(--s-1) var(--s-3);
    border-radius: var(--r-sm);
    white-space: nowrap;
  }
  .channels a.on {
    background: var(--surface-3);
    color: var(--ink);
  }
  .channels a.unread {
    color: var(--ink);
    font-weight: 600;
  }
  .badge {
    margin-left: var(--s-2);
    font-size: var(--fs-xs);
    font-weight: 600;
    color: var(--accent);
  }
  .channels input {
    margin-top: var(--s-2);
    width: 100%;
    font: inherit;
    font-size: var(--fs-sm);
    color: var(--ink);
    background: none;
    border: 1px dashed var(--line);
    border-radius: var(--r-sm);
    padding: var(--s-1) var(--s-3);
  }
  .room {
    display: flex;
    flex-direction: column;
    min-height: 0;
    background: var(--surface);
    border: 1px solid var(--line);
    border-radius: var(--r-lg);
  }
  header {
    display: flex;
    gap: var(--s-3);
    align-items: baseline;
    padding: var(--s-3) var(--s-4);
    border-bottom: 1px solid var(--line);
  }
  h1 {
    font-size: var(--fs-lg);
  }
  .topic {
    color: var(--ink-3);
    font-size: var(--fs-sm);
  }
  .thread-back { color: var(--accent); text-decoration: none; font-size: var(--fs-sm); }
  .room-menu { position: relative; color: var(--ink-3); }
  .room-menu summary { cursor: pointer; list-style: none; }
  .menu-items { position: absolute; right: 0; top: 100%; z-index: 2; width: max-content; max-width: min(24rem, 90vw); max-height: 70dvh; overflow-y: auto; display: grid; background: var(--surface-2); border: 1px solid var(--line); border-radius: var(--r-sm); }
  .room-menu a { padding: var(--s-2) var(--s-3); color: var(--accent); text-decoration: none; font-size: var(--fs-sm); }
  .room-menu .notify { display: grid; gap: 2px; padding: var(--s-2) var(--s-3); font-size: var(--fs-sm); border-top: 1px solid var(--line); }
  .room-menu select { font: inherit; color: var(--ink); background: var(--surface); border: 1px solid var(--line); border-radius: var(--r-sm); }
  .thread-origin {
    display: grid;
    gap: 2px;
    padding: var(--s-3) var(--s-4);
    border-bottom: 1px solid var(--line);
    color: var(--ink-2);
    font-size: var(--fs-sm);
  }
  .thread-origin span { color: var(--ink-3); font-size: var(--fs-xs); }
  .thread-origin p { margin: 0; white-space: pre-wrap; }
  .pins {
    margin-left: auto;
    background: none;
    border: 1px solid transparent;
    border-radius: var(--r-full);
    cursor: pointer;
    color: var(--ink-3);
  }
  .pins.on {
    border-color: var(--line);
    color: var(--ink);
  }
  .pinned {
    border-bottom: 1px solid var(--line);
    padding: var(--s-2) var(--s-4);
    max-height: 30vh;
    overflow: auto;
    display: grid;
    gap: var(--s-1);
    font-size: var(--fs-sm);
  }
  .muted {
    color: var(--ink-3);
    margin: 0;
  }
  .older { display: block; margin: 0 auto var(--s-2); font-size: var(--fs-sm); }
  .list {
    flex: 1;
    overflow-y: auto;
    padding: var(--s-2) var(--s-2);
    display: flex;
    flex-direction: column;
    min-height: 0;
  }
  .empty {
    color: var(--ink-3);
    margin: auto;
  }
  .bar {
    display: flex;
    align-items: center;
    gap: var(--s-2);
    font-size: var(--fs-sm);
    color: var(--ink-2);
    padding: var(--s-2) var(--s-4);
    border-top: 1px solid var(--line);
    background: var(--surface-2);
  }
  .bar select {
    font: inherit;
    color: var(--ink);
    background: var(--surface);
    border: 1px solid var(--line);
    border-radius: var(--r-sm);
  }
  .x {
    margin-left: auto;
    background: none;
    border: 0;
    cursor: pointer;
    color: var(--ink-3);
  }
  .composer {
    display: flex;
    gap: var(--s-2);
    align-items: end;
    padding: var(--s-3);
    border-top: 1px solid var(--line);
  }
  .older-load { justify-self: center; margin: var(--s-2) auto; }
  .older { padding: var(--s-1) var(--s-3); color: var(--ink-2); }
  .older .who { font-weight: 600; margin-right: var(--s-2); }
  .older time { font-size: var(--fs-xs); color: var(--ink-3); }
  .not-sent { list-style: none; margin: 0; padding: var(--s-2); display: grid; gap: var(--s-1); border-left: 2px solid var(--line); }
  .not-sent li { display: flex; flex-wrap: wrap; align-items: center; gap: var(--s-2); }
  .not-sent .why { color: var(--ink-2); font-size: var(--fs-sm); }
  .not-sent .text { color: var(--ink-3); overflow-wrap: anywhere; }
  .reply-in { display: flex; flex-wrap: wrap; align-items: center; gap: var(--s-2); padding: var(--s-2); color: var(--ink-2); }
  .reply-in select { font: inherit; color: var(--ink); background: var(--surface-2); border: 1px solid var(--line); border-radius: var(--r-sm); padding: var(--s-1); max-width: 100%; }
  .chat-advanced {
    padding: var(--s-1) var(--s-3);
    border-top: 1px solid var(--line);
    color: var(--ink-3);
    font-size: var(--fs-xs);
  }
  .chat-advanced summary { cursor: pointer; width: fit-content; }
  .chat-advanced label { display: flex; align-items: center; gap: var(--s-2); padding: var(--s-2) 0; color: var(--ink-2); }
  .segment-edit { display: grid; gap: 2px; font-size: var(--fs-xs); color: var(--ink-2); }
  .segment-edit + .segment-edit { margin-top: var(--s-2); }
  .chip-wrap {
    position: relative;
  }
  .chip {
    background: none;
    border: 0;
    padding: var(--s-1);
    cursor: pointer;
  }
  .avatar {
    width: 28px;
    height: 28px;
    display: grid;
    place-items: center;
    font-size: 14px;
    border-radius: 50%;
    background: var(--surface-2);
    box-shadow: 0 0 0 2px var(--ring, var(--line));
  }
  .picker {
    position: absolute;
    bottom: 110%;
    left: 0;
    z-index: 5;
    display: grid;
    min-width: 160px;
    max-height: 260px;
    overflow: auto;
    background: var(--surface);
    border: 1px solid var(--line);
    border-radius: var(--r-md);
    box-shadow: var(--shadow-pop);
    padding: var(--s-1);
  }
  .picker button {
    all: unset;
    cursor: pointer;
    padding: var(--s-2) var(--s-3);
    border-radius: var(--r-sm);
  }
  .picker button:hover {
    background: var(--surface-2);
  }
  .input {
    flex: 1;
    display: grid;
  }
  .as {
    font-size: var(--fs-xs);
    color: var(--ink-3);
    padding: 0 var(--s-2);
  }
  textarea {
    font: inherit;
    color: var(--ink);
    background: var(--surface-2);
    border: 1px solid var(--line);
    border-radius: var(--r-md);
    padding: var(--s-2) var(--s-3);
    resize: none;
    field-sizing: content;
    max-height: 30vh;
  }
  .send {
    background: var(--accent);
    color: var(--surface);
    border: 0;
    border-radius: var(--r-full);
    padding: var(--s-2) var(--s-4);
    font-weight: 600;
    cursor: pointer;
  }
  .send:disabled {
    opacity: 0.5;
  }
</style>
