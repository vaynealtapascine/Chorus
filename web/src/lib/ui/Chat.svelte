<script lang="ts">
  import Search from "./Search.svelte";
  let searchHere = $state(false);
  import { onDestroy, tick, untrack } from 'svelte';
  import { core, type Composed, type Entity } from '../core';
  import RichText from './RichText.svelte';
  import { channels, contentWarningsAutoExpand, customEmojis, lastRead, latestMessage, memberMarks, members, messageById, messagePage, pinnedMessages, reactions, readPerMember, segmentParsing, spaces, threadSummaries, unread, type MessageRow, type QuoteValue, type SnapshotItem, type TextRange } from '../data';
  import { activeViewers, memberVisible } from '../hidden';
  import { router } from '../router.svelte';
  import { quoteForTarget, snapshot } from '../selection';
  import { editedSegments, segmentRich } from '../segments';
  import { canWriteAs, posts } from '../posts';
  import { channelFrontChoices, channelFrontId, channelFrontKey, channelReaders, channelSpeaker } from '../channel-front';
  import { typingLabel, typingName } from '../sync/typing';
  import { sync, type Projection } from '../sync/client';
  import { bodyPresence, frontLayers } from '../front-view';
  import { flushUploads, imageThumbnail, stageBlob } from '../sync/uploads';
  import Message from './Message.svelte';
  import PollCard from './PollCard.svelte';
  import { closedAt, closeMessage, pollOf, pollPayload, tallyOf } from '../polls';
  import AvatarImage from './AvatarImage.svelte';
  import EmojiImage from './EmojiImage.svelte';
  import ChatFolderRail from './ChatFolderRail.svelte';
  import ChatList from './ChatList.svelte';
  import { chatPrefs, dayLabel, discColor, dmFace, folderOf, frontEvents, FOLDERS, jumpBack, pushJump } from '../chat-list';
  import { contains, folderPrefs, holds, label as folderLabel, mutedChat, PREF as FOLDERS_PREF, setChat, toJson, visible as visibleFolders, withCustom } from '../chat-folders';
  import AttachmentView from './AttachmentView.svelte';
  import AttachSheet from './AttachSheet.svelte';
  import { linkTo } from '../share-links';
  import { expandFront } from '../subsystems';
  import Icon from './Icon.svelte';
  import EmptyState from './kit/EmptyState.svelte';
  import UnderlineTabs from './kit/UnderlineTabs.svelte';
  import Sheet from './Sheet.svelte';
  import { accountName, authorCards, listSpaces, openDm, spaceTitle, type SpaceInfo } from '../spaces';
  import { accountsLabel, frontingLine } from '../friends';
  import { frontOf, refreshFront } from '../friend-fronts.svelte';
  import { carryReply, takeReply } from '../replyElsewhere';
  import ChannelPermissions from './ChannelPermissions.svelte';
  import SpaceRoles from './SpaceRoles.svelte';
  import ChannelEditor from './ChannelEditor.svelte';
  import NewChat from './NewChat.svelte';
  import ForwardSheet, { type Target } from './ForwardSheet.svelte';
  import { snapshotsFor, warning as forwardCard } from '../chat-forward';
  import { afterSend, everyone, offered as asideOffered, on as asideOn, placeholder as asidePlaceholder } from '../chat-aside';
  import { startFrom } from '../new-chat';
  import SpacePanel from './SpacePanel.svelte';
  import { profileHref } from '../profile-link';
  import { chatShown, privacyChannel } from '../chat-visible';
  import { alignsRight, readSetting, roomSpeakers, settingValue, type SpeakerMode } from '../room-speakers';
  import { editDraft, inputDraft, markupDraft, parseComposer, updateDraft, mentionAt, mentionChoices, replaceSelection, unicodeEmoji, type MentionChoice, type ComposerDraft } from '../chat-composer';
  import { groups } from '../data';
  import { handleMentions, memberHandles, shownHandle } from '../handles';
  import { canEditSpace, privateSides, roleIn } from '../chat-manage';
  import type { MemberChange } from '../many-members';
  import { selfMember, type MemberRow } from '../data';
  import { apiBase } from '../sync/device';
  import { apiFetch } from '../http';

  let { projection, dark, channelId, focusId, onswitch = () => {}, onmenu = () => {} }: {
    projection: Projection; dark: boolean; channelId?: string; focusId?: string; onswitch?: () => void; onmenu?: () => void;
  } = $props();
  // desktop chat folders (Figma F5.21); the choice lasts for this tab
  let folder = $state<string>(sessionStorage.getItem('chorus.chat.folder') ?? 'all');
  // chat folders (D-113): which show, in what order, and folders of our own; a hidden or deleted
  // folder falls back to the first shown, and with none shown every chat is listed
  const folderSettings = $derived(folderPrefs(projection.rows.pref ?? {}, sync.accountId));
  const openFolder = $derived(visibleFolders(folderSettings).includes(folder) ? folder : visibleFolders(folderSettings)[0] ?? 'all');
  const railFolders = $derived(visibleFolders(folderSettings).map((id) => ({ id, label: folderLabel(folderSettings, id),
    icon: FOLDERS.find((f) => f.id === id)?.icon ?? 'folder' })));
  let foldersAt = $state<string | null>(null);
  $effect(() => sessionStorage.setItem('chorus.chat.folder', folder));

  let showInfo = $state(window.matchMedia('(min-width: 1280px)').matches);
  // below 1024 px the list and a conversation take turns, as on Android: #/chat is the list
  // (owner, 2026-11-06), and nothing is read while only the list shows
  const wide = window.matchMedia('(min-width: 1024px)');
  let compact = $state(!wide.matches);
  $effect(() => {
    const on = () => (compact = !wide.matches);
    wide.addEventListener('change', on);
    return () => wide.removeEventListener('change', on);
  });
  const listing = $derived(compact && !channelId);
  // messages newer than the moment a chat opened slide in; the ones it opened with just show
  const openedAt = $derived.by(() => { void channelId; return Date.now(); });
  // a new channel in the shared space open here (its managers; our own space's come from New chat)
  let addingChannel = $state(false);
  // "Read as": whose view of hidden messages and members' chats this is (none: whoever is here)
  let viewingAs = $state<string | null>(null);
  const roomMembers = $derived(members(projection).filter((m) => !m.deleted && !m.archived));
  const ss = $derived(spaces(projection));
  const allChannels = $derived(channels(projection));
  // `#/chat/space:<id>` opens a space by id (a DM just created may not have its channel synced yet)
  const current = $derived(
    allChannels.find((c) => c.id === channelId) ??
      (channelId?.startsWith('space:')
        ? allChannels.find((c) => c.space_id === channelId.slice(6) && !c.archived && c.kind !== 'thread' && !c.member_ids?.length) ??
          allChannels.find((c) => c.space_id === channelId.slice(6) && !c.archived && c.kind !== 'thread')
        : undefined) ??
      // nothing asked for: the first chat that isn't someone else's private one (D-097, D-098)
      allChannels.find((c) => !c.archived && c.kind !== 'thread' && shownNow(c)) ??
      allChannels.find((c) => !c.archived && c.kind !== 'thread'),
  );
  const space = $derived(ss.find((s) => s.id === current?.space_id) ?? ss[0]);
  // members' private chats only while one of them is here (D-097, D-098), as in ChatList
  const shownNow = $derived(chatShown(projection, sync.accountId, viewingAs));
  // what messages link to (D-109): posts from the replica (others' come from the server), and a
  // message only where this reader may see it now: its chat is shown, and its audience allows us
  const postsById = $derived(new Map(posts(projection).map((p) => [p.id, p])));
  const channelsById = $derived(new Map(channels(projection, undefined, true).map((c) => [c.id, c])));
  function embedLookup(id: string) {
    const found = messageById(projection, id);
    const channel = found && channelsById.get(found.channel_id);
    if (!found || !channel || !shownNow(channel)) return undefined;
    const home = ss.find((s) => s.id === channel.space_id);
    const where = home?.kind === 'dm' ? home.name ?? 'Direct message' : channel.kind === 'member_dm' ? channel.name ?? undefined
      : home?.kind === 'shared' ? `${home.name} · #${channel.name}` : `#${channel.name}`;
    return memberVisible(found, home?.kind, activeMembers, viewingAs) ? { ...found, channel_name: where } : undefined;
  }
  const spaceChannels = $derived(allChannels.filter((c) => c.space_id === space?.id && !c.archived && c.kind !== 'thread' && shownNow(c)));
  // a private chat in a DM (D-098): whose it is on our side and theirs
  const ownIds = $derived(new Set(members(projection).map((m) => m.id)));
  const privacyRoot = $derived(privacyChannel(projection, current));
  const sides = $derived(privacyRoot && space ? privateSides(privacyRoot, space, (id) => ownIds.has(id)) : null);
  // someone's private chat that isn't ours to look at now: its words stay out, and nothing is marked read
  const lockedOut = $derived(!!current && !shownNow(current));
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
  const fronting = $derived(bodyPresence(projection, sync.accountId));
  const layerNames = $derived(new Map(frontLayers(projection, sync.accountId).map((l) => [l.id, l.name])));
  const frontingMembers = $derived(fronting.filter((e) => e.subject_type === 'member')
    .map((e) => ({ member_id: e.subject_id, is_primary: !!e.is_primary, level: e.level })));
  // a person account always speaks as its self member (D-003)
  const self = $derived(selfMember(projection));
  const activeMembers = $derived(activeViewers(fronting));
  // a chat cleared for us (D-097) keeps its earlier messages out of view
  const clearedAt = $derived(current ? chatPrefs(projection.rows.pref ?? {}, sync.accountId).hidden.get(current.id) : undefined);
  const visibleMsgs = $derived(lockedOut ? [] : msgs.filter((m) => memberVisible(m, space?.kind, activeMembers, viewingAs) &&
    (clearedAt === undefined || m.occurred_at > clearedAt)));
  // this account's speakers for the channel (D-074, D-103): pref `autoproxy:<channel id>`
  type Autoproxy = SpeakerMode;
  const autoproxyKey = $derived(current ? `autoproxy:${current.id}` : '');
  const autoproxy = $derived.by(() => {
    const rows = projection.rows.pref ?? {};
    return readSetting((rows[`${sync.accountId}||${autoproxyKey}`] ?? rows[`||${autoproxyKey}`])?.fields.value);
  });
  function setAutoproxy(mode: Autoproxy, member?: string, chosenMembers: string[] = autoproxy.members) {
    sync.create('pref.set', sync.accountScope, null, { device: '', key: autoproxyKey, value: settingValue(mode, member, chosenMembers) });
  }
  // the speaker chip before anyone picks: one rule in core (SPEC §5.2)
  // in a private chat only its members of ours speak (D-098; the server refuses anyone else)
  const ownSenders = $derived(members(projection).filter((m) => canWriteAs(projection, m.id, sync.accountId) &&
    (!sides?.mine.length || sides.mine.includes(m.id))));
  const lastOwnAuthors = $derived(msgs.findLast((m) => m.account_id === sync.accountId && !m.deleted)?.authors ?? []);
  // the room's speakers (D-103): the composer offers each with a tap; their messages sit on our side
  const roomSpeakerIds = $derived(roomSpeakers(autoproxy, { fronting: frontingMembers, lastAuthors: lastOwnAuthors,
    writable: (id) => ownSenders.some((m) => m.id === id) }));
  // several chosen speakers: the first is the default (core knows one sticky member)
  const defaults = $derived(channelSpeaker(projection, sync.accountId, current?.id ?? '', {
    mode: autoproxy.mode === 'members' ? (roomSpeakerIds.length ? 'member' : 'front') : autoproxy.mode,
    member: (autoproxy.mode === 'members' ? roomSpeakerIds[0] : autoproxy.member) ?? null, self_member: self?.id ?? null,
    fronting: frontingMembers,
    last_authors: lastOwnAuthors,
    members: ownSenders.map((m) => m.id),
  }));
  // the channel's front (D-080/D-085): authorship, @front, per-member reads and chat alerts
  const frontChoices = $derived(channelFrontChoices(projection, sync.accountId));
  const channelFront = $derived(current ? channelFrontId(projection, sync.accountId, current.id) : sync.accountId);
  function setChannelFront(front: string) {
    if (!current) return;
    sync.create('pref.set', sync.accountScope, null, { device: '', key: channelFrontKey(current.id), value: front });
  }
  let chosen = $state<string | null>(null);
  const speaker = $derived(chosen ?? (sides?.mine.length
    ? defaults.find((d) => sides.mine.includes(d)) ?? sides.mine.find((id) => fronting.some((e) => e.subject_id === id)) ?? sides.mine[0]
    : defaults[0]) ?? null);
  const defaultAuthors = $derived(chosen ? [chosen] : sides?.mine.length ? (speaker ? [speaker] : []) : defaults);
  const speakers = $derived(
    ownSenders.map((m) => ({ member_id: m.id, sigils: m.sigils, proxy_tags: m.proxy_tags })),
  );
  // who sits on our side: the room's speakers and whoever speaks now, with everyone inside any of
  // them that is a subsystem (D-102: the only place a subsystem's members count as fronting with it)
  const alignedIds = $derived(new Set(expandFront(projection, sync.accountId,
    [...new Set([...roomSpeakerIds, ...(speaker ? [speaker] : [])])].map((id) => ({ subject_type: 'member', subject_id: id, level: 'front', is_primary: false })))
    .entries.filter((e) => e.subject_type === 'member').map((e) => e.subject_id)));
  // members by the name everyone may see (never a private full name, D-126), with their handles
  const ownHandles = $derived(memberHandles(projection, sync.accountId));
  const mentionable = $derived([...people.values()].filter((m) => !m.deleted && !m.archived));
  const mentionOptions = $derived(mentionChoices(mentionable.map((m) => ({
    id: m.id, name: m.display_name ?? m.name, handle: shownHandle(m, ownHandles), foreign: !!m.account_id && m.account_id !== sync.accountId,
  })), groups(projection)));
  const names = $derived({
    mentions: Object.fromEntries([
      // a typed @handle names its member wherever it's written (a name only where it's unique, below)
      ...[...handleMentions(ownHandles, mentionable)].map(([handle, id]) => [handle, { target_type: 'member', target_id: id }]),
      ...mentionOptions.flatMap((m) => [m.token, ...(mentionOptions.filter((n) => n.name.toLowerCase() === m.name.toLowerCase()).length === 1 ? [m.name] : [])]
        .map((token) => [token.toLowerCase(), { target_type: m.type, target_id: m.id || null }])),
    ]),
    emoji: Object.fromEntries([
      ...activeEmoji.flatMap((e) => e.aliases.map((alias) => [alias, e.id])),
      ...activeEmoji.map((e) => [e.name, e.id]), // a primary name wins over an alias collision
    ]),
  });

  let draft = $state('');
  let draftDoc = $state<ComposerDraft>({ text: '', entities: [] });
  const draftProvenance = $derived(updateDraft(draftDoc, draft));
  $effect(() => { draftDoc = draftProvenance; });
  let editingDocs = $state<ComposerDraft[]>([]);
  let beforeEdit: ComposerDraft | null = null;
  const draftKey = (id: string) => `chorus.chat.draft:${sync.accountId}:${id}`;
  function restoreDraft(id: string | undefined): ComposerDraft {
    try {
      const value = JSON.parse(id ? sessionStorage.getItem(draftKey(id)) ?? 'null' : 'null');
      return value && typeof value.text === 'string' && Array.isArray(value.entities) ? value : { text: '', entities: [] };
    } catch { return { text: '', entities: [] }; }
  }
  function saveDraft(id: string | undefined, value: ComposerDraft) {
    if (!id) return;
    try { if (value.text) sessionStorage.setItem(draftKey(id), JSON.stringify(value)); else sessionStorage.removeItem(draftKey(id)); } catch { /* A full browser store must not interrupt typing. */ }
  }
  let cw = $state('');
  // an optional poll on the next message (Advanced, D-081)
  let pollOn = $state(false);
  let pollOptions = $state(['', '']);
  let pollMultiple = $state(false);
  let pollError = $state('');
  let visibilityMode = $state<'all' | 'members' | 'system_only'>('all');
  let visibleTo = $state<string[]>([]);
  let pending = $state<{ file: File; alt: string; spoiler: boolean }[]>([]);
  let uploadError = $state('');
  let fileInput: HTMLInputElement | undefined = $state();
  let photoInput: HTMLInputElement | undefined = $state();
  let attaching = $state(false);
  let replyTo = $state<MessageRow | null>(null);
  let quoting = $state<QuoteValue | null>(null);
  let editing = $state<MessageRow | null>(null);
  let editingParts = $state<string[] | null>(null);
  let forwarding = $state<SnapshotItem[] | null>(null);
  let forwardingSensitive = false;
  let quoteSourceSpaceId = '';
  let quoteSourceChannelId = $state('');
  let quoteSensitive = false;
  let keepQuoteOnNavigation = false;
  let selecting = $state(false);
  let selectedIds = $state(new Set<string>());
  let showPins = $state(false);
  let menuOpen = $state(false);
  // New chat, and editing this chat (D-099)
  let newChat = $state<false | 'message' | 'space'>(false);
  let editingChannel = $state(false);
  let editingGroup = $state(false);
  let dirTick = $state(0);
  // content warning, poll and audience for the next message (Figma: behind the composer's sliders)
  let optionsOpen = $state(false);
  // messages waiting out slow mode (D-076): not in the projection, so follow the sync client
  let held = $state(sync.heldUntil);
  let typingRevision = $state(0);
  const typingSubtitle = $derived.by(() => {
    void typingRevision;
    return typingLabel(sync.typing(current?.id ?? ''), (entry) =>
      typingName(entry, people, directory.get(space?.id ?? '')?.accounts ?? []));
  });
  // the sync client isn't reactive state: what the template shows of it is copied here on change
  let syncLive = $state(sync.status === 'live');
  let windowed = $state(sync.windowed);
  const unheld = sync.subscribe(() => {
    held = sync.heldUntil;
    typingRevision++;
    syncLive = sync.status === 'live';
    windowed = sync.windowed;
  });
  onDestroy(unheld);
  let picking = $state(false);
  let box: HTMLTextAreaElement | undefined = $state();
  let cursor = $state(0);
  let selectionEnd = $state(0);
  let composing = $state(false);
  let inputSelection: { from: number; to: number; type: string } | null = null;
  const segmentInputSelections = new Map<number, { from: number; to: number; type: string }>();
  let mentionIndex = $state(0);
  let dismissedMention = $state('');
  const mentionRange = $derived(!editing && !composing ? mentionAt(draft, cursor, selectionEnd) : null);
  const mentionMatches = $derived(mentionRange && dismissedMention !== `${draft}|${cursor}` ? mentionOptions.filter((m) => m.name.toLowerCase().includes(mentionRange.query) || !!m.handle?.startsWith(mentionRange.query)).slice(0, 4) : []);
  function trackCursor() { const next = box?.selectionStart ?? draft.length; if (next !== cursor) mentionIndex = 0; cursor = next; selectionEnd = box?.selectionEnd ?? cursor; }
  function insertText(text: string, from = cursor, to = selectionEnd) {
    const result = replaceSelection(draft, from, to, text);
    draftDoc = editDraft(draftProvenance, from, to, text);
    draft = result.text; cursor = selectionEnd = result.cursor;
    queueMicrotask(() => { box?.focus(); box?.setSelectionRange(result.cursor, result.cursor); });
  }
  function insertMention(m: MentionChoice) {
    if (!mentionRange) return;
    const from = mentionRange.from;
    // another account's member by their handle, ours by name (Figma F5.13)
    const label = m.foreign && m.handle ? m.handle : m.name;
    insertText(`@${label} `, from, mentionRange.to);
    draftDoc = { text: draft, entities: [...draftDoc.entities, { type: 'mention', offset: from, length: label.length + 1, target_type: m.type, ...(m.id ? { target_id: m.id } : {}) }] };
  }
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
    insertText(`:${name}: `, from, box?.selectionEnd ?? at);
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
    untrack(() => {
      saveDraft(activeChannelId, beforeEdit ?? draftProvenance);
      draftDoc = restoreDraft(current?.id);
      draft = draftDoc.text;
      beforeEdit = null;
      cursor = selectionEnd = draft.length;
    });
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
  $effect(() => { const value = draftProvenance; const isEditing = editing; untrack(() => { if (activeChannelId && !isEditing) saveDraft(activeChannelId, value); }); });

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
  let quoteIn = $state(false);
  // the chats a forward, a reply or a quote can go to, as faces (F5.20); built only while a sheet is open
  const targetRows = $derived.by((): Target[] => {
    if (!forwarding && !replyIn && !quoteIn) return [];
    const own = new Set(members(projection).map((m) => m.id));
    const open = allChannels.filter((c) => !c.archived && c.kind !== 'thread' && shownNow(c));
    return open.map((c) => {
      const sp = ss.find((x) => x.id === c.space_id);
      const siblings = open.filter((o) => o.space_id === c.space_id).length;
      const sides = privateSides(c, sp, (id) => own.has(id));
      const theirs = sides?.theirs.map((id) => people.get(id)?.display_name ?? people.get(id)?.name).filter(Boolean).join(' & ');
      const title = sides ? (theirs || (sp ? spaceTitle(sp, directory.get(sp.id), sync.accountId) : c.name))
        : c.kind === 'member_dm' ? c.name
        : sp?.kind === 'dm' ? spaceTitle(sp, directory.get(sp.id), sync.accountId)
        : sp?.kind === 'shared' ? (siblings > 1 ? `${sp.name} · ${c.name}` : sp.name)
        : c.name;
      const partner = c.kind === 'member_dm' && !sides ? people.get(c.member_ids?.[0] ?? '')
        : sides?.theirs.length ? people.get(sides.theirs[0])
        : sp?.kind === 'dm' ? dmFace(directory.get(sp.id), sync.accountId, cards) : undefined;
      const letter = (title.match(/\p{L}|\p{N}/u)?.[0] ?? '#').toUpperCase();
      const disc = c.avatar_blob ? { glyph: c.icon ?? letter, color: c.color ?? discColor(c.id), blob: c.avatar_blob }
        : partner ? { glyph: partner.sigils[0] ?? partner.name[0] ?? '?', color: partner.color, blob: partner.avatar_blob ?? undefined }
        : { glyph: c.icon ?? letter, color: c.color ?? discColor(c.id) };
      return { id: c.id, title, names: [sp?.name, c.name], at: latestMessage(projection, c.id)?.occurred_at ?? 0, disc };
    });
  });
  // the red card (F5.20) when a copy leaves where it was written, and who is on the other side
  function leavingCard(targetId: string) {
    const target = allChannels.find((c) => c.id === targetId);
    if (!target || !current || !space) return null;
    const outward = forwarding ? sharingOutward(target.id, target.space_id, current.space_id, forwardingSensitive)
      : sharingOutward(target.id, target.space_id, quoteSourceSpaceId, quoteSensitive);
    if (!outward) return null;
    const source = space.kind === 'internal' ? (systemName === 'your system' ? 'this chat' : systemName) : space.kind === 'dm' ? 'this chat' : space.name || 'this chat';
    const there = ss.find((x) => x.id === target.space_id);
    const audience = there?.kind === 'internal' ? [] : (directory.get(target.space_id)?.accounts ?? []).filter((a) => a.id !== sync.accountId).map(accountName);
    return forwardCard(source, audience, targetRows.find((t) => t.id === targetId)?.title ?? target.name);
  }
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

  const preview: Composed | null = $derived(draft.trim() && !editing ? parseComposer(draftProvenance, names, { speakers, options: { segments: parseSegments }, defaults: defaultAuthors }) : null);
  const retainedAllowed = $derived(!lockedOut && (!quoteSourceChannelId || !quoting || shownNow(allChannels.find((c) => c.id === quoteSourceChannelId))) &&
    (!replyTo || shownNow(allChannels.find((c) => c.id === replyTo?.channel_id))));
  const messageAuthors = $derived(preview?.authors ?? defaultAuthors);
  let typingDraft: { channel: string; member: string | null; audience: 'all' | 'system' } | null = null;
  let lastTypingDraft = '';
  function stopTyping() {
    if (typingDraft) sync.sendTyping(typingDraft.channel, typingDraft.member, typingDraft.audience, false);
    typingDraft = null;
  }
  $effect(() => {
    const channel = current?.id;
    const text = draft;
    const member = messageAuthors[0] ?? speaker;
    const audience = visibilityMode === 'all' && (!threadParent?.visibility || threadParent.visibility.mode === 'all') ? 'all' : 'system';
    untrack(() => {
      const change = JSON.stringify([channel, text, member, audience]);
      if (change === lastTypingDraft) return;
      lastTypingDraft = change;
      const switched = typingDraft !== null && typingDraft.channel !== channel;
      if (typingDraft && (typingDraft.channel !== channel || typingDraft.member !== member || typingDraft.audience !== audience || !text.trim())) stopTyping();
      if (!switched && channel && text.trim()) {
        typingDraft = { channel, member, audience };
        sync.sendTyping(channel, member, audience, true);
      }
    });
  });
  onDestroy(stopTyping);

  // the audience bar over a shared space's composer (F5.4, F5.16): everyone here, or an aside only our system sees
  const hasAside = $derived(asideOffered(space?.kind, !!self) && !!current && !lockedOut && current.kind !== 'thread');
  const aside = $derived(hasAside && visibilityMode === 'system_only');
  const canSend = $derived(!!current && (!!preview?.rich.text.trim() || pending.length > 0) &&
    messageAuthors.length > 0 && messageAuthors.every((id) => canWriteAs(projection, id, sync.accountId)) &&
    (visibilityMode !== 'members' || visibleTo.length > 0));
  const previewNames = $derived(
    preview ? preview.segments.map((s) => s.authors.map((a) => people.get(a)?.name ?? '?').join(' & ')).join(' → ') : '',
  );

  // own recent Journal posts for the attach sheet's Journal tile
  const ownPosts = $derived(attaching ? posts(projection).filter((p) => !p.deleted && !p.repost_of && !p.reply_to && p.account_id === sync.accountId)
    .sort((a, b) => b.occurred_at - a.occurred_at).slice(0, 30).map((p) => ({ id: p.id, label: (p.title || p.text).replace(/\s+/g, ' ').slice(0, 80) || 'Post' })) : []);
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
    if (!current || !retainedAllowed) return;
    if (editing) {
      const result = editingParts
        ? editedSegments(editingParts, editing.segments, names, (source, i) => parseComposer(updateDraft(editingDocs[i], source), names).rich)
        : { rich: parseComposer(draftProvenance, names).rich, segments: [{ offset: 0, length: 0, authors: [...editing.authors] }] };
      if (!result.rich.text.trim()) return;
      if (!editingParts) result.segments[0].length = result.rich.text.length;
      sync.create('message.edit', scope, editing.id, {
        message_id: editing.id, text: result.rich.text, entities: result.rich.entities, segments: result.segments,
      });
      editing = null;
      editingParts = null;
      draftDoc = beforeEdit ?? { text: '', entities: [] };
      draft = draftDoc.text; beforeEdit = null;
      return;
    }
    if (!canSend) return;
    const pollDraft = pollOn ? pollPayload(pollOptions, pollMultiple) : {};
    pollError = pollDraft.error ?? '';
    if (pollDraft.error) return;
    const authors = messageAuthors;
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
    stopTyping();
    sync.create('message.send', scope, sync.newId(), {
      channel_id: current.id,
      authors,
      text: preview?.rich.text ?? '',
      entities: preview?.rich.entities ?? [],
      ...(preview && preview.segments.length > 1 ? { segments: preview.segments } : {}),
      ...(attachmentIds.length ? { attachments: attachmentIds } : {}),
      ...(replyTo ? { reply_to: replyTo.id } : {}),
      ...(quoting ? { quote: quoteForTarget(quoting, quoteSourceSpaceId || current.space_id, current.space_id) } : {}),
      ...(cw.trim() ? { cw: cw.trim() } : {}),
      ...(visibilityMode === 'members' ? { visibility: { mode: 'members', member_ids: visibleTo } } :
        visibilityMode === 'system_only' ? { visibility: { mode: 'system_only' } } : {}),
      ...(pollDraft.poll ? { poll: pollDraft.poll } : {}),
      sent_offline: sync.status !== 'live',
    });
    draft = '';
    cw = '';
    pollOn = false;
    pollOptions = ['', ''];
    pollMultiple = false;
    visibilityMode = afterSend(visibilityMode) as typeof visibilityMode;
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
    if (e.isComposing || composing || e.keyCode === 229) return;
    if (mentionMatches.length) {
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp') { e.preventDefault(); mentionIndex = (mentionIndex + (e.key === 'ArrowDown' ? 1 : -1) + mentionMatches.length) % mentionMatches.length; return; }
      if (e.key === 'Enter' || e.key === 'Tab') { e.preventDefault(); insertMention(mentionMatches[mentionIndex] ?? mentionMatches[0]); return; }
      if (e.key === 'Escape') { e.preventDefault(); dismissedMention = `${draft}|${cursor}`; return; }
    }
    if (e.key === 'ArrowUp' && !draft && !editing && !replyTo && !quoting && !pending.length && !pollOn && !lockedOut) {
      const latest = visibleMsgs.findLast((m) => !m.deleted && m.account_id === sync.accountId && (!sides?.mine.length || m.authors.every((id) => sides.mine.includes(id))));
      if (latest) { e.preventDefault(); startEdit(latest); } return;
    }
    if (e.key === 'Escape' && (emojiPicker || completion.length)) { emojiPicker = false; e.preventDefault(); return; }
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      void send();
    }
    if (e.key === 'Escape') cancel();
  }

  function cancel() {
    if (editing) { draftDoc = beforeEdit ?? { text: '', entities: [] }; draft = draftDoc.text; beforeEdit = null; }
    replyTo = quoting = editing = null;
    editingParts = null;
  }

  function startEdit(m: MessageRow) {
    if (!editing) beforeEdit = structuredClone($state.snapshot(draftProvenance));
    editing = m;
    editingDocs = m.segments.map((segment) => markupDraft(segmentRich(m, segment)));
    editingParts = m.segments.length > 1 ? editingDocs.map((d) => d.text) : null;
    replyTo = quoting = null;
    // Segment editing keeps each part's authors; a single segment uses the plain composer.
    draftDoc = editingParts ? { text: '', entities: [] } : markupDraft(m);
    draft = draftDoc.text;
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
    quoteSourceChannelId = current.id;
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
    return (sourceSpace?.kind === 'internal' && targetSpaceId !== sourceSpaceId) || sensitive || !!privacyRoot?.member_ids?.length;
  }

  function moveQuote(targetId: string) {
    const target = allChannels.find((c) => c.id === targetId && !c.archived);
    if (!retainedAllowed || !target || !shownNow(target) || !quoting || target.id === current?.id) return;
    keepQuoteOnNavigation = true;
    router.go(`/chat/${target.id}`);
  }

  function forwardTo(targetId: string, comment = '', showAuthors = true) {
    const target = allChannels.find((c) => c.id === targetId);
    const items = forwarding;
    if (lockedOut || !target || !shownNow(target) || target.archived || !items || !speaker || !current) return;
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
      text: comment.trim(),
      entities: [],
      forward_of_id: items[0].message_id,
      forward_snapshot: snapshotsFor(items, current.space_id, target.space_id, showAuthors),
      ...(attachments.length ? { attachments } : {}),
    });
    forwarding = null;
    router.go(`/chat/${target.id}`);
  }

  // chats with something unread, per folder: the rail's badges
  const folderCounts = $derived.by(() => {
    const counts: Record<string, number> = {};
    const shown = visibleFolders(folderSettings);
    const prefs = projection.rows.pref ?? {};
    for (const c of allChannels) {
      if (c.archived || c.kind === 'thread' || c.id === current?.id) continue;
      const n = unread(projection, c.id, sync.accountId);
      if (!n) continue;
      const kind = folderOf(c, ss.find((s) => s.id === c.space_id));
      const muted = mutedChat(prefs, sync.accountId, c.id);
      for (const id of shown) if (contains(folderSettings, id, c.id, kind, n, muted)) counts[id] = (counts[id] ?? 0) + 1;
    }
    return counts;
  });

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
  // a group's name, description and face: its owner and admins (D-099)
  const groupEditable = $derived(!!space && canEditSpace(space.kind, roleIn(projection.rows.space_member, space.id, directory.get(space.id), sync.accountId)));
  const canManage = $derived.by(() => {
    if (!space) return false;
    const info = directory.get(space.id);
    if (info?.guest) return false;
    if (space.kind === 'dm') return true;
    const owner = info?.owner_account_id ?? (space.kind === 'internal' ? sync.accountId : undefined);
    return owner === sync.accountId || projection.rows.space_member?.[`${space.id}|${sync.accountId}`]?.fields.role === 'admin';
  });

  // who may edit this chat: our own space's chats, a group's for its managers, our private chat in a DM
  const canEditChannel = $derived(!!current && current.kind !== 'thread' && !lockedOut &&
    (space?.kind === 'internal' ? !self : space?.kind === 'dm' ? current.kind === 'member_dm' && !!sides?.mine.length : canManage));

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
  // who reads as here, for display: members inside a fronting subsystem come with it (D-092);
  // speakers, reads and visibility keep `fronting`, the front exactly as switched
  // the front exactly as switched (D-102: subsystems expand only for which side a bubble sits on)
  const shownFront = $derived({ entries: fronting, via: new Map<string, string>() });
  // the conversation's face and words (Figma F5.21 header and info)
  const nameOf = (id: string) => { const m = people.get(id); return m?.display_name ?? m?.name ?? 'Someone'; };
  const systemName = $derived(String(projection.rows.system?.[sync.accountId]?.fields.name ?? 'your system'));
  const roomTitle = $derived(lockedOut ? 'Private chat' : current?.kind === 'thread' ? 'Thread'
    : sides?.theirs.length ? sides.theirs.map(nameOf).join(' & ')
    : space?.kind === 'dm' ? spaceTitle(space, directory.get(space.id), sync.accountId)
    : current?.kind === 'member_dm' ? current.name
    : space?.kind === 'shared' && spaceChannels.length <= 1 ? space.name
    : current?.name ?? 'Chat');
  // a channel is named with its # (Figma F5.16); a chat that carries a person's or a space's name is not
  const headTitle = $derived(!lockedOut && current && current.kind !== 'thread' && current.kind !== 'member_dm' && roomTitle === current.name && space?.kind !== 'dm' ? `# ${roomTitle}` : roomTitle);
  const roomSubtitle = $derived.by(() => {
    if (lockedOut) return 'Opens while its member is here';
    if (current?.kind === 'thread') return parentChannel ? `in #${parentChannel.name}` : '';
    if (sides && space) return sides.mine.length ? `Private · ${sides.mine.map(nameOf).join(' & ')}`
      : `Private chat · ${spaceTitle(space, directory.get(space.id), sync.accountId)}`;
    if (current?.topic) return current.topic;
    if (space?.kind === 'internal') {
      const here = shownFront.entries.filter((e) => e.subject_type === 'member' && people.has(e.subject_id)).map((e) => nameOf(e.subject_id));
      return here.length ? `${here.slice(0, 2).join(' & ')}${here.length > 2 ? ` & ${here.length - 2} more` : ''} here` : `${roomMembers.length} members`;
    }
    // a DM with someone you follow: who's fronting there, as far as they share it
    if (space?.kind === 'dm') return frontingLine((dmPartner ? frontOf(dmPartner.id) : undefined)?.map((m) => m.name) ?? []) ?? 'Direct message';
    const label = accountsLabel(directory.get(space?.id ?? '')?.accounts ?? []);
    return `${space?.kind === 'shared' && spaceChannels.length > 1 ? `${space.name} · ` : ''}${label || 'Shared space'}`;
  });
  const dmPartner = $derived(space?.kind === 'dm' ? directory.get(space.id)?.accounts.find((a) => a.id !== sync.accountId) : undefined);
  $effect(() => {
    const id = dmPartner?.id;
    if (!id) return;
    void refreshFront(id);
    const t = setInterval(() => void refreshFront(id), 60_000);
    return () => clearInterval(t);
  });
  const roomDisc = $derived.by((): { glyph: string; member?: MemberRow; style: string; blob?: string } => {
    if (lockedOut) return { glyph: '🔒', style: 'background: var(--surface-2); color: var(--ink-2)' };
    if (current?.avatar_blob && current.kind !== 'thread') {
      const c = core.adaptColor(current.color ?? discColor(current.id), dark);
      return { glyph: current.icon ?? (roomTitle.match(/\p{L}|\p{N}/u)?.[0] ?? '#').toUpperCase(), blob: current.avatar_blob, style: `background: ${c.ring}; color: ${c.avatar_ink}` };
    }
    if (current?.kind === 'member_dm' && (space?.kind === 'internal' || sides?.theirs.length)) {
      const m = people.get(sides?.theirs[0] ?? current.member_ids?.find((id) => id !== speaker) ?? current.member_ids?.[0] ?? '');
      if (m) { const c = core.adaptColor(m.color, dark); return { glyph: m.sigils[0] ?? m.name[0] ?? '?', member: m, style: `background: ${c.ring}; color: ${c.avatar_ink}` }; }
    }
    // a DM with another account shows who it is with, as in the list
    if (space?.kind === 'dm' && current && current.kind !== 'member_dm' && current.kind !== 'thread') {
      const m = dmFace(directory.get(space.id), sync.accountId, cards);
      if (m) { const c = core.adaptColor(m.color, dark); return { glyph: m.sigils[0] ?? m.name[0] ?? '?', member: m, style: `background: ${c.ring}; color: ${c.avatar_ink}` }; }
    }
    if (space?.kind === 'internal' && current?.kind !== 'thread' && !current?.icon && !current?.color) return { glyph: '#', style: 'background: var(--accent-soft); color: var(--accent)' };
    const c = core.adaptColor(current?.color ?? discColor(current?.id ?? ''), dark);
    if (current?.icon) return { glyph: current.icon, style: `background: ${c.ring}; color: ${c.avatar_ink}` };
    return { glyph: (roomTitle.match(/\p{L}|\p{N}/u)?.[0] ?? '#').toUpperCase(), style: `background: ${c.ring}; color: ${c.avatar_ink}` };
  });
  // beside a name: the author's layer when they aren't in the innermost one, or another system's name
  const innermost = $derived(frontLayers(projection, sync.accountId)[0]?.id);
  function tagOf(m: MessageRow): string | undefined {
    if (space?.kind !== 'internal') {
      if (!m.account_id || m.account_id === sync.accountId) return undefined;
      const a = directory.get(space?.id ?? '')?.accounts.find((x) => x.id === m.account_id);
      return a?.display_name ?? (a?.handle ? `@${a.handle}` : undefined);
    }
    if (m.authors.length !== 1) return undefined;
    const e = shownFront.entries.find((f) => f.subject_type === 'member' && f.subject_id === m.authors[0]);
    return e && e.level !== innermost ? layerNames.get(e.level)?.toLowerCase() : undefined;
  }
  // members by where they are now (innermost layer first), with how far behind each one reads
  const byLayer = $derived.by(() => {
    const order = frontLayers(projection, sync.accountId).map((l) => l.id);
    const marks = new Map(marksHere.map((x) => [x.member, x.at]));
    const inRoom = current?.member_ids?.length ? roomMembers.filter((m) => current.member_ids?.includes(m.id)) : roomMembers;
    return inRoom.map((m) => {
      const e = shownFront.entries.find((f) => f.subject_type === 'member' && f.subject_id === m.id);
      const via = shownFront.via.get(m.id);
      const mark = perMember ? marks.get(m.id) : undefined;
      const behind = mark === undefined ? 0 : visibleMsgs.filter((x) => !x.deleted && x.occurred_at > mark && !x.authors.includes(m.id)).length;
      return { m, rank: (e ? order.indexOf(e.level) : 99) * 2 + (via ? 1 : 0), layer: e ? `${layerNames.get(e.level)?.toLowerCase() ?? ''}${via ? ` with ${via}` : ''}` : undefined, unread: behind };
    }).sort((a, b) => a.rank - b.rank || nameOf(a.m.id).localeCompare(nameOf(b.m.id)));
  });
  const roomMedia = $derived(visibleMsgs.filter((m) => !m.deleted && !m.cw).flatMap((m) => m.attachments.filter((a) => a.mime.startsWith('image/') && !a.is_spoiler)).reverse());
  const roomFiles = $derived(visibleMsgs.filter((m) => !m.deleted && !m.cw).flatMap((m) => m.attachments.filter((a) => !a.mime.startsWith('image/') && !a.is_spoiler)).reverse());
  const roomPolls = $derived(visibleMsgs.filter((m) => !m.deleted && pollOf(projection, m.id)).reverse().map((m) => {
    const poll = pollOf(projection, m.id)!;
    return { id: m.id, title: poll.question || m.text.replace(/\s+/g, ' ').slice(0, 80) || poll.options.map((o) => o.text).join(' · ').slice(0, 80),
      votes: tallyOf(projection, m.id)?.voters ?? 0, closed: closedAt(projection, m.id, poll) != null };
  }));
  type InfoTab = 'members' | 'media' | 'files' | 'links' | 'polls';
  let infoTab = $state<InfoTab>('media');
  // the chat's folders, from its info
  let folderSheet = $state(false);
  const chatFolder = $derived(current ? folderOf(current, spaces(projection).find((x) => x.id === current.space_id)) : 'internal');
  function toggleFolder(id: string) {
    const folder = folderSettings.custom.find((c) => c.id === id);
    if (!folder || !current) return;
    const next = withCustom(folderSettings, setChat(folder, current.id, chatFolder, !holds(folder, current.id, chatFolder)));
    sync.create('pref.set', sync.accountScope, null, { device: '', key: FOLDERS_PREF, value: toJson(next) });
  }
  const roomLinks = $derived(visibleMsgs.filter((m) => !m.deleted && !m.cw).flatMap((m) => m.entities.flatMap((e) => {
    if (!['url', 'text_link'].includes(e.type) || m.entities.some((s) => s.type === 'spoiler' && s.offset < e.offset + e.length && s.offset + s.length > e.offset)) return [];
    const url = e.type === 'url' ? m.text.slice(e.offset, e.offset + e.length) : String(e.url ?? '');
    return /^https?:\/\//i.test(url) ? [{ id: `${m.id}:${e.offset}`, url, label: m.text.slice(e.offset, e.offset + e.length) }] : [];
  })).reverse());

  const infoTabs = $derived<{ id: InfoTab; label: string; count: number }[]>([
    ...(space?.kind === 'internal' ? [{ id: 'members' as const, label: 'Members', count: byLayer.length }] : []),
    { id: 'media', label: 'Media', count: roomMedia.length }, { id: 'files', label: 'Files', count: roomFiles.length },
    { id: 'links', label: 'Links', count: roomLinks.length }, { id: 'polls', label: 'Polls', count: roomPolls.length }]);
  const infoShown = $derived(infoTabs.some((t) => t.id === infoTab) ? infoTab : infoTabs[0].id);

  const grouped = $derived(
    visibleMsgs.map((m, j) => {
      const prev = visibleMsgs[j - 1];
      const cont =
        !!prev && !prev.deleted && prev.authors.join() === m.authors.join() && m.occurred_at - prev.occurred_at < 300_000 && m.segments.length === 1;
      return { m, cont };
    }),
  );
  // messages with "Today"-style day pills and, in our own chats, who came to the front meanwhile
  const clockFormat = new Intl.DateTimeFormat([], { hour: '2-digit', minute: '2-digit' });
  type Line = { kind: 'msg'; key: string; m: MessageRow; cont: boolean; last: boolean } | { kind: 'day' | 'event'; key: string; text: string };
  const frontLines = $derived(space?.kind === 'internal' && grouped.length
    ? frontEvents(projection.fronts[sync.accountId]?.intervals ?? [], grouped[0].m.occurred_at, Date.now(),
      (type, id) => (type === 'member' ? (people.get(id)?.display_name ?? people.get(id)?.name) : undefined), (l) => layerNames.get(l))
    : []);
  const timeline = $derived.by(() => {
    const out: Line[] = [];
    let day = '';
    let e = 0;
    const dayIn = (t: number) => {
      const d = new Date(t).toDateString();
      if (d === day) return false;
      day = d;
      out.push({ kind: 'day', key: `day:${d}`, text: dayLabel(t) });
      return true;
    };
    for (const { m, cont } of grouped) {
      let broke = false;
      while (e < frontLines.length && frontLines[e].at <= m.occurred_at) {
        dayIn(frontLines[e].at);
        out.push({ kind: 'event', key: `front:${frontLines[e].at}:${e}`, text: `${frontLines[e].text} · ${clockFormat.format(frontLines[e].at)}` });
        e++; broke = true;
      }
      if (dayIn(m.occurred_at)) broke = true;
      out.push({ kind: 'msg', key: m.id, m, cont: cont && !broke, last: true });
    }
    for (; e < frontLines.length; e++) {
      dayIn(frontLines[e].at);
      out.push({ kind: 'event', key: `front:${frontLines[e].at}:${e}`, text: `${frontLines[e].text} · ${clockFormat.format(frontLines[e].at)}` });
    }
    for (let i = 0; i < out.length; i++) {
      const line = out[i];
      const next = out[i + 1];
      if (line.kind === 'msg') line.last = !(next?.kind === 'msg' && next.cont);
    }
    return out;
  });
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
    current && !lockedOut ? pinnedMessages(projection, current.id).filter((m) => memberVisible(m, space?.kind, activeMembers, viewingAs)) : [],
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
  // D-142: the same reaction as several of our members at once (in a private chat only its own)
  const frontingIds = $derived(new Set(fronting.filter((e) => e.subject_type === 'member').map((e) => e.subject_id)));
  function reactAs(m: MessageRow, emoji: string, change: MemberChange) {
    for (const [on, ids] of [[true, change.add], [false, change.remove]] as const) {
      for (const id of ids) sync.create(on ? 'reaction.add' : 'reaction.remove', scope, m.id, { target_type: 'message', target_id: m.id, emoji, member_id: id });
    }
  }

  // reading the channel moves the read mark forward (never backward, SYNC.md §5.5); only while
  // the page is actually visible, and again when it becomes visible
  let visible = $state(!document.hidden);
  $effect(() => {
    const on = () => (visible = !document.hidden);
    document.addEventListener('visibilitychange', on);
    return () => document.removeEventListener('visibilitychange', on);
  });
  // what this view already marked: on a deep link this effect runs before App follows the
  // projection, so it would see its own mark missing and write it again, a thousand times over
  const marked = new Map<string, string>();
  $effect(() => {
    if (!current || !visible || !atLatest || lockedOut || listing) return;
    const latest = visibleMsgs.findLast((m) => !m.deleted);
    if (!latest) return;
    // the account, and with "track reading per member" whoever is fronting (core decides)
    const marks = new Map(marksHere.map((m) => [m.member, m.at]));
    for (const reader of channelReaders(projection, sync.accountId, current.id, perMember, frontingMembers, ownSenders.map((m) => m.id))) {
      const at = reader === '' ? lastRead(projection, current.id, sync.accountId) : marks.get(reader) ?? 0;
      const key = `${current.id}|${reader}`;
      if (latest.occurred_at <= at || marked.get(key) === latest.id) continue;
      marked.set(key, latest.id);
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
    void dirTick;
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
  let atLatest = $state(true);
  let unreadBoundary = $state(0);
  let readChannel = '';
  // with reading tracked per member, the boundary is the reading member's own mark (as when the chat was opened)
  let markBoundary = $state(new Map<string, number>());
  const reader = $derived(perMember && !self && speaker ? people.get(speaker) ?? null : null);
  const readerName = $derived(reader ? reader.display_name ?? reader.name : null);
  const boundary = $derived(reader ? markBoundary.get(reader.id) ?? 0 : unreadBoundary);
  const unreadRows = $derived(visibleMsgs.filter((m) => !m.deleted && m.account_id !== sync.accountId && m.occurred_at > boundary));
  const pendingUnread = $derived(unreadRows.length);
  const firstUnread = $derived(unreadRows[0]?.id);
  // unread messages that name the reader, for the @ jump
  const mentionedUnread = $derived(unreadRows.filter((m) => m.entities.some((e) => e.type === 'mention' && e.target_type === 'member' &&
    (reader ? e.target_id === reader.id : typeof e.target_id === 'string' && ownIds.has(e.target_id)))));
  const readerSince = $derived(reader && boundary ? dayLabel(boundary).toLowerCase() : '');
  function jumpMention() {
    const m = mentionedUnread[0];
    const el = m && list?.querySelector<HTMLElement>(`[data-message-id="${CSS.escape(m.id)}"]`);
    if (!el) return;
    el.scrollIntoView({ block: 'center', behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' });
    flash(el);
  }
  $effect(() => {
    if (current?.id === readChannel) return;
    readChannel = current?.id ?? '';
    unreadBoundary = current ? lastRead(projection, current.id, sync.accountId) : 0;
    markBoundary = new Map(marksHere.map((x) => [x.member, x.at]));
    atLatest = true;
    jumpTrail = [];
  });
  // where each reply jump here started, newest last (D-112): ↓ first goes back one jump at a time,
  // then to the latest message; reaching the latest forgets the trail
  let jumpTrail = $state<string[]>([]);
  $effect(() => { if (atLatest) jumpTrail = []; });
  const returnTo = $derived(jumpBack(jumpTrail, (id) => !!list?.querySelector(`[data-message-id="${CSS.escape(id)}"]`) || visibleMsgs.some((m) => m.id === id)));
  function jumpLatest() {
    const back = returnTo;
    if (back) {
      jumpTrail = back.rest;
      const el = list?.querySelector<HTMLElement>(`[data-message-id="${CSS.escape(back.id)}"]`);
      if (el) {
        el.scrollIntoView({ block: 'center', behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' });
        flash(el);
        return;
      }
    }
    jumpTrail = [];
    list?.scrollTo({ top: list.scrollHeight });
    atLatest = true;
  }
  $effect(() => {
    if (retainedAllowed) return;
    if (editing) { draftDoc = beforeEdit ?? { text: '', entities: [] }; draft = draftDoc.text; beforeEdit = null; }
    replyTo = quoting = editing = forwarding = replyIn = null;
    editingParts = null; selectedIds = new Set(); selecting = false;
    editingChannel = editingGroup = menuOpen = emojiPicker = false;
    history = null; older = []; stopTyping();
  });
  $effect(() => {
    if (focusId) return;
    void msgs.length;
    if (untrack(() => atLatest)) queueMicrotask(() => list?.scrollTo({ top: list.scrollHeight }));
  });
  $effect(() => {
    const id = focusId;
    if (!id || lockedOut || visibleMsgs.some((m) => m.id === id)) { history = null; historyError = ''; return; }
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
    const id = focusId;
    queueMicrotask(() => {
      const el = list?.querySelector<HTMLElement>(`[data-message-id="${CSS.escape(id)}"]`);
      el?.scrollIntoView({ block: 'center' });
      if (el) flash(el);
    });
  });
  /** A moment's ring around the message a jump landed on. */
  function flash(el: HTMLElement) {
    if (matchMedia('(prefers-reduced-motion: reduce)').matches) return;
    const accent = getComputedStyle(el).getPropertyValue('--accent').trim() || '#42A397';
    el.animate([{ boxShadow: `0 0 0 3px ${accent}` }, { boxShadow: '0 0 0 3px transparent' }], { duration: 1400, easing: 'ease-out' });
  }
  /** The message a reply, quote or forward points at: here, scroll to it; elsewhere, open its chat at it. */
  function showMessage(target: MessageRow, from?: string) {
    if (target.channel_id === current?.id) {
      const el = list?.querySelector<HTMLElement>(`[data-message-id="${CSS.escape(target.id)}"]`);
      if (el) {
        jumpTrail = pushJump(jumpTrail, from, target.id);
        el.scrollIntoView({ block: 'center', behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' });
        flash(el);
        return;
      }
    }
    location.hash = `#/chat/${target.channel_id}/${target.id}`;
  }
</script>

{#snippet pollFor(id: string)}
  {@const poll = pollOf(projection, id)}
  {@const m = messageById(projection, id)}
  {#if poll}<PollCard {projection} parentId={id} {poll} member={speaker} onclose={m && m.account_id === sync.accountId ? () => closeMessage(scope, id, m.channel_id) : undefined} />{/if}
{/snippet}

<div class="chat" class:with-info={showInfo && !listing} class:listing>
  <div class="desk-rail"><ChatFolderRail folder={openFolder} folders={railFolders} counts={folderCounts} person={!!self} onfolder={(f) => (folder = f)}
    onedit={() => (foldersAt = '')} {onmenu} /></div>
  <div class="desk-list"><ChatList {projection} {dark} currentId={current?.id} folder={openFolder} folders={folderSettings} {foldersAt}
    onfolders={(at) => (foldersAt = at)} {directory} person={!!self} {onswitch} {viewingAs}
    onnewchat={() => (newChat = startFrom(openFolder, folderSettings))} tabs={railFolders} counts={folderCounts} onfolder={(f) => (folder = f)} /></div>

  <section class="room" aria-label={current ? `#${current.name}` : 'Chat'} ondragover={(e) => { if (e.dataTransfer?.types.includes('Files')) e.preventDefault(); }} ondrop={dropFiles}>
    <header class="room-head" inert={searchHere}>
      {#if current?.kind !== 'thread'}<a class="icon-button list-back" href="#/chat" aria-label="Back to chats"><span aria-hidden="true">‹</span></a>{/if}
      {#if current?.kind === 'thread'}
        <a class="icon-button thread-back" href="#/chat/{parentChannel?.id ?? ''}" aria-label="Back to #{parentChannel?.name ?? 'channel'}"><span aria-hidden="true">‹</span></a>
      {/if}
      <span class="room-disc" style={roomDisc.style}>{#if roomDisc.member}<AvatarImage hash={roomDisc.member.avatar_blob} glyph={roomDisc.glyph} name={roomTitle} />{:else if roomDisc.blob}<AvatarImage hash={roomDisc.blob} glyph={roomDisc.glyph} name={roomTitle} />{:else}{roomDisc.glyph}{/if}</span>
      <button class="titles" onclick={() => (showInfo = !showInfo)} aria-label="Chat info" aria-expanded={showInfo}>
        <h1>{headTitle}</h1>
        {#if typingSubtitle}<span class="sub typing" role="status">{typingSubtitle}</span>{:else if roomSubtitle}<span class="sub">{roomSubtitle}</span>{/if}
      </button>
      <button class="icon-button" onclick={() => searchHere = !searchHere} aria-label="Search this chat" aria-expanded={searchHere}><Icon name="search" size={22} /></button>
      {#if current && !lockedOut}
        <details class="room-menu" bind:open={menuOpen}>
          <summary class="icon-button" aria-label="Channel menu"><Icon name="more" size={22} /></summary>
          <div class="menu-items">
            <button onclick={() => { showPins = !showPins; menuOpen = false; }}><Icon name="pin" size={18} />{showPins ? 'Hide pinned messages' : `Pinned messages · ${pinned.length}`}</button>
            <a href="#/stage/{current.id}"><Icon name="select" size={18} />Stage… (screenshot)</a>
            <a href="#/trash/{current.id}"><Icon name="delete" size={18} />Show deleted</a>
            {#if canEditChannel}<button onclick={() => { editingChannel = true; menuOpen = false; }}><Icon name="edit" size={18} />{current.kind === 'member_dm' ? 'Edit chat…' : 'Edit channel…'}</button>{/if}
            {#if space?.kind === 'shared' && canManage}<button onclick={() => { addingChannel = true; menuOpen = false; }}><Icon name="hash" size={18} />New channel in {space.name}…</button>{/if}
            {#if space?.kind === 'internal'}
              <label class="notify">Viewing as
                <select aria-label="Viewing as member" value={viewingAs ?? ''} onchange={(e) => (viewingAs = e.currentTarget.value || null)}>
                  <option value="">Current front</option>
                  {#each members(projection).filter((m) => !m.deleted && !m.archived) as person (person.id)}
                    <option value={person.id}>{person.display_name ?? person.name}</option>
                  {/each}
                </select>
              </label>
            {/if}
            {#if !self && frontChoices.length > 1}
              <label class="notify">Front for this channel
                <select aria-label="Front for this channel" value={channelFront} onchange={(e) => setChannelFront(e.currentTarget.value)}>
                  {#each frontChoices as f (f.id)}<option value={f.id}>{f.name}</option>{/each}
                  {#if !frontChoices.some((f) => f.id === channelFront)}<option value={channelFront}>Unavailable front</option>{/if}
                </select>
              </label>
            {/if}
            {#if !self}
              <label class="notify">Speaker in this channel
                <select aria-label="Autoproxy for this channel" value={autoproxy.mode}
                  onchange={(e) => setAutoproxy(e.currentTarget.value as Autoproxy, e.currentTarget.value === 'member' ? (autoproxy.member ?? speaker ?? undefined) : undefined)}>
                  <option value="front">Whoever is fronting</option>
                  <option value="latch">Whoever spoke last here</option>
                  <option value="member">Always one member</option>
                  <option value="members">Chosen members</option>
                  <option value="off">Nobody (pick each time)</option>
                </select>
              </label>
              {#if autoproxy.mode === 'members'}
                <fieldset class="notify speakers-pick" aria-label="Speakers in this channel">
                  <legend>Speakers here (the first is the default)</legend>
                  {#each ownSenders as person (person.id)}
                    <label><input type="checkbox" checked={autoproxy.members.includes(person.id)}
                      onchange={(e) => setAutoproxy('members', undefined, e.currentTarget.checked ? [...autoproxy.members, person.id] : autoproxy.members.filter((x) => x !== person.id))} />
                      {person.display_name ?? person.name}</label>
                  {/each}
                </fieldset>
              {/if}
              {#if autoproxy.mode === 'member'}
                <label class="notify">Always
                  <select aria-label="Sticky speaker" value={autoproxy.member ?? ''} onchange={(e) => setAutoproxy('member', e.currentTarget.value)}>
                    {#each members(projection).filter((m) => !m.deleted && !m.archived) as person (person.id)}
                      <option value={person.id}>{person.display_name ?? person.name}</option>
                    {/each}
                  </select>
                </label>
              {/if}
            {/if}
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
            <label class="notify">Notify me
              <select value={notifyLevel} onchange={(e) => setNotifyLevel(e.currentTarget.value)} aria-label="Notifications for this channel">
                <option value="all">for every message</option>
                <option value="mentions">for mentions and replies</option>
                <option value="none">never</option>
              </select>
            </label>
            {#if sync.device?.is_admin}
              <details class="emoji-manager">
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
          </div>
        </details>
      {/if}
    </header>
    {#if searchHere && current}
      <div class="search-overlay">{#key current.id}<Search {projection} {dark} channelId={current.id} onclose={() => searchHere = false}
        onopen={(m) => { searchHere = false; void tick().then(() => showMessage(m)); }} />{/key}</div>
    {/if}
    <div class="search-underlay" inert={searchHere} aria-hidden={searchHere}>
    {#if reader && !lockedOut}
      <div class="reading-as"><span class="who" style="background: {color(reader.id).ring}; color: {color(reader.id).avatar_ink}"><AvatarImage hash={reader.avatar_blob} glyph={reader.sigils[0] ?? reader.name[0]} name={reader.name} /></span>
        <span class="says">Reading as {readerName}{readerSince ? ` · last read ${readerSince}` : ''}</span>
        <button onclick={() => (picking = true)}>Change</button></div>
    {/if}
    {#if pinned.length && !showPins}
      {@const latestPin = pinned[pinned.length - 1]}
      <button class="pinned-bar" onclick={() => (showPins = true)} aria-label="Show pinned messages">
        <span class="pin-text"><strong>Pinned message{pinned.length > 1 ? ` · ${pinned.length}` : ''}</strong>
          <span>{latestPin.cw ? `Content warning: ${latestPin.cw}` : latestPin.text.replace(/\s+/g, ' ').slice(0, 160) || 'Attachment'}</span></span>
        <Icon name="pin" size={18} />
      </button>
    {/if}

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

    <div class="list" bind:this={list} onscroll={() => { if (!list) return; atLatest = list.scrollHeight - list.scrollTop - list.clientHeight < 80; if (list.scrollTop < 200) showOlder(); }}>
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
      {#if windowed && current && !olderDone && !first && !lockedOut}
        <button class="older-load" disabled={olderBusy || !syncLive} onclick={() => void loadOlder()}>
          {olderBusy ? 'Loading…' : 'Older messages (from the server)'}
        </button>
        {#if olderError}<p class="hint" role="status">{olderError}</p>{/if}
      {/if}
      {#each lockedOut ? [] : older as o (o.id)}
        <article class="older">
          <span class="who">{o.authors.map((id) => people.get(id)?.name ?? 'Someone').join(' & ')}</span>
          <time>{new Date(o.occurred_at).toLocaleString([], { dateStyle: 'short', timeStyle: 'short' })}</time>
          {#if o.cw}<div class="hint">Content warning: {o.cw}</div>{:else}<p><RichText text={o.text} entities={o.entities} emoji={emojiById} /></p>{/if}
        </article>
      {/each}
      {#if lockedOut}
        <div class="locked" role="status">
          <span class="lock-disc"><Icon name="lock" size={28} /></span>
          <strong>This chat is private</strong>
        </div>
      {/if}
      {#each timeline as line (line.key)}
        {#if line.kind === 'day'}
          <div class="service day"><span>{line.text}</span></div>
        {:else if line.kind === 'event'}
          <div class="service"><span>{line.text}</span></div>
        {:else if line.kind === 'msg'}
        {@const m = line.m}
        {#if m.id === firstUnread && boundary > 0}<div class="service unread-divider"><span>{pendingUnread} unread{readerName ? ` for ${readerName}` : ''}</span></div>{/if}
        <Message
          {m}
          handleOf={(id) => shownHandle(people.get(id), ownHandles)}
          cont={line.cont}
          last={line.last}
          fresh={m.occurred_at > openedAt}
          tag={tagOf(m)}
          {people}
          {dark}
          lookup={(id) => { const found = messageById(projection, id); return found && memberVisible(found, space?.kind, activeMembers, viewingAs) ? found : undefined; }}
          mine={m.account_id === sync.accountId}
          right={alignsRight(m, sync.accountId, alignedIds)}
          onreply={() => { replyTo = m; quoting = null; editing = null; editingParts = null; box?.focus(); }}
          onreplyin={() => (replyIn = m)}
          unseen={unseenBy(m).map((id) => people.get(id)?.name ?? '?')}
          onreplyprivately={(space?.kind === 'internal' ? m.authors[0] !== speaker : m.account_id !== sync.accountId) ? () => void replyPrivately(m) : undefined}
          onquote={(q) => { quoting = q; forwarding = null; quoteSourceSpaceId = current?.space_id ?? ''; quoteSourceChannelId = current?.id ?? ''; quoteSensitive = !!privacyRoot?.member_ids?.length || !!m.visibility && m.visibility.mode !== 'all'; replyTo = m; editing = null; editingParts = null; box?.focus(); }}
          onedit={() => startEdit(m)}
          ondelete={() => sync.create('message.delete', scope, m.id, {}, { memberId: speaker ?? undefined })}
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
          {systemName}
          {emojiById}
          {cwAutoExpand}
          heldUntil={held[m.id]?.until}
          oncancelheld={() => sync.cancelHeld(m.id)}
          onreact={(emoji, on) => react(m, emoji, on)}
          reactors={ownSenders} fronting={frontingIds} onreactas={(emoji, change) => reactAs(m, emoji, change)}
          onjump={(t) => showMessage(t, m.id)}
          profileOf={(id) => profileHref(people.get(id), sync.accountId)}
          postOf={(id) => postsById.get(id)}
          {embedLookup}
          extra={pollFor}
        />
        {/if}
      {:else}
        {#if !lockedOut}<EmptyState icon="chat" label="No messages yet" />{/if}
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
    {#if mentionedUnread.length && !lockedOut}<button class="jump-latest jump-mention" onclick={jumpMention} aria-label="{mentionedUnread.length} unread {mentionedUnread.length === 1 ? 'mention' : 'mentions'}{readerName ? ` for ${readerName}` : ''}">@ · {mentionedUnread.length}</button>{/if}
    {#if (!atLatest || returnTo) && !lockedOut}<button class="jump-latest" onclick={jumpLatest} aria-label={returnTo ? 'Back to where you jumped from' : `Jump to latest${pendingUnread ? `, ${pendingUnread} unread` : ''}`}>{returnTo ? '↓ Back' : `↓ Latest${pendingUnread ? ` · ${pendingUnread}` : ''}`}</button>{/if}
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
    {#if retainedAllowed && (replyTo || quoting || editing)}
      <div class="bar">
        {#if editing}Editing message{:else if quoting}Quoting {#if 'items' in quoting}{quoting.items.length} {quoting.items.length === 1 ? 'message' : 'messages'}{:else}“{quoting.text.slice(0, 60)}”{/if}{:else if replyTo}Replying to {replyTo.authors.map((a) => people.get(a)?.name ?? '?').join(' & ')}{/if}
        {#if quoting}
          <button onclick={() => (quoteIn = true)}>Quote in…</button>
        {/if}
        <button class="x" onclick={cancel} aria-label="Cancel">✕</button>
      </div>
    {/if}

    {#if optionsOpen && !editing}
      <div class="msg-options" role="group" aria-label="Message options">
        <label>Content warning <input aria-label="Content warning" bind:value={cw} placeholder="Optional label" /></label>
        <details class="poll-draft" bind:open={pollOn}>
          <summary>Add a poll</summary>
          {#each pollOptions as _, i (i)}
            <input aria-label="Poll option {i + 1}" bind:value={pollOptions[i]} placeholder="Option {i + 1}" maxlength="200" />
          {/each}
          {#if pollOptions.length < 20}<button type="button" onclick={() => (pollOptions = [...pollOptions, ''])}>Add option</button>{/if}
          <label><input type="checkbox" bind:checked={pollMultiple} /> Allow more than one choice</label>
          {#if pollError}<p class="error" role="alert">{pollError}</p>{/if}
        </details>
        {#if space?.kind === 'internal'}
        <label>Visibility
          <select aria-label="Message visibility" bind:value={visibilityMode}>
            <option value="all">Everyone in this space</option>
            <option value="members">Chosen members</option>
          </select>
        </label>
        {/if}
        {#if visibilityMode === 'members'}
          <div class="visibility-members" aria-label="Members who can view this message">
            {#each members(projection).filter((m) => !m.deleted && !m.archived) as person (person.id)}
              <label><input type="checkbox" checked={visibleTo.includes(person.id)} onchange={(e) => {
                visibleTo = e.currentTarget.checked ? [...visibleTo, person.id] : visibleTo.filter((id) => id !== person.id);
              }} /> {person.display_name ?? person.name}</label>
            {/each}
          </div>
          {#if !visibleTo.length}<p class="hint" role="alert">Choose at least one member.</p>{/if}
        {/if}
      </div>
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
    {#if !lockedOut && !editing && roomSpeakerIds.length > 1}
      <div class="speaker-chips" role="radiogroup" aria-label="Who is speaking">
        {#each roomSpeakerIds as id (id)}
          {@const c = color(id)}
          <button role="radio" aria-checked={id === speaker} class:on={id === speaker} onclick={() => (chosen = id)} style="--ring: {c.ring}">
            <span class="avatar" style="background: {c.ring}; color: {c.avatar_ink}"><AvatarImage hash={people.get(id)?.avatar_blob} glyph={people.get(id)?.sigils[0] ?? people.get(id)?.name[0] ?? '?'} name={people.get(id)?.name} /></span>
            {people.get(id)?.display_name ?? people.get(id)?.name ?? '?'}
          </button>
        {/each}
      </div>
    {/if}
    {#if mentionMatches.length && !lockedOut}
      <div class="mention-suggestions" role="listbox" aria-label="Mention suggestions">
        {#each mentionMatches as mention, i (`${mention.type}:${mention.id}`)}
          {@const who = mention.id ? people.get(mention.id) : undefined}
          {@const layer = who ? shownFront.entries.find((e) => e.subject_type === 'member' && e.subject_id === who.id) : undefined}
          <button class:active={i === mentionIndex} role="option" aria-selected={i === mentionIndex} onmousedown={(e) => e.preventDefault()} onclick={() => insertMention(mention)}>
            {#if who}<span class="m-face" style="background: {color(who.id).ring}; color: {color(who.id).avatar_ink}"><AvatarImage hash={who.avatar_blob} glyph={who.sigils[0] ?? who.name[0]} name={who.name} /></span>
            {:else}<span class="m-face" class:front={mention.type === 'front'}>{mention.type === 'front' ? '@' : mention.name[0]?.toUpperCase()}</span>{/if}
            <strong>@{mention.name}</strong>
            <small>{mention.type === 'front' ? 'whoever fronts when it arrives' : who ? (layer ? layerNames.get(layer.level)?.toLowerCase() ?? '' : mention.handle ? `@${mention.handle}` : '') : ''}</small>
          </button>
        {/each}
      </div>
    {/if}
    {#if hasAside && !editing}
      <div class="audience" class:on={aside}>
        <Icon name={aside ? 'lock' : 'globe'} size={18} />
        <span class="says">{aside ? asideOn(systemName) : everyone(directory.get(space?.id ?? '')?.accounts)}</span>
        <button class="aside-pill" aria-pressed={aside} onclick={() => (visibilityMode = aside ? 'all' : 'system_only')}><Icon name="lock" size={14} />Aside</button>
      </div>
    {/if}
    {#if !lockedOut}
    <div class="composer">
      <input class="file-input" type="file" multiple accept="image/*,video/*" bind:this={photoInput} onchange={(e) => { addFiles(e.currentTarget.files ?? []); e.currentTarget.value = ''; }} aria-label="Choose photos" />
      <input class="file-input" type="file" multiple bind:this={fileInput} onchange={(e) => { addFiles(e.currentTarget.files ?? []); e.currentTarget.value = ''; }} aria-label="Choose attachments" />
      {#if !editing}<div class="chip-wrap">
        <button class="send-as" onclick={() => (picking = !picking)} title="Speaking as {speaker ? people.get(speaker)?.name ?? '' : 'no one'}" aria-label="Speaking as {speaker ? people.get(speaker)?.name ?? '' : 'no one'}. Change">
          {#if speaker}
            <span class="avatar" style="background: {color(speaker).ring}; color: {color(speaker).avatar_ink}"><AvatarImage hash={people.get(speaker)?.avatar_blob} glyph={people.get(speaker)?.sigils[0] ?? people.get(speaker)?.name[0] ?? '?'} name={people.get(speaker)?.name} /></span>
          {:else}
            <span class="avatar">?</span>
          {/if}
        </button>
        {#if picking}
          <div class="picker" role="listbox">
            {#each ownSenders as p (p.id)}
              <button onclick={() => { chosen = p.id; picking = false; }} style="color: {color(p.id).name}">{p.sigils[0] ?? ''} {p.display_name ?? p.name}</button>
            {/each}
          </div>
        {/if}
      </div>{/if}
      <div class="pill" class:aside>
        {#if !editing}<div class="chip-wrap">
          <button class="in-pill" onclick={() => (emojiPicker = !emojiPicker)} title="Emoji" aria-label="Emoji" aria-expanded={emojiPicker}><Icon name="smile" size={22} /></button>
          {#if emojiPicker}
            <div class="picker emoji-pick" role="listbox" aria-label="Custom emoji">
              <div class="unicode-emoji" aria-label="Unicode emoji">
                {#each unicodeEmoji as emoji}<button title={emoji} aria-label={`Insert ${emoji}`} onclick={() => { insertText(emoji); emojiPicker = false; }}>{emoji}</button>{/each}
              </div>
              {#each activeEmoji as e (e.id)}
                <button onclick={() => insertEmoji(e.name)}><EmojiImage hash={e.blob_hash} name={e.name} /> :{e.name}: <small>{e.category}</small></button>
              {:else}
                <span class="hint">No custom emoji yet.</span>
              {/each}
            </div>
          {/if}
        </div>{/if}
        <div class="input">
          {#if preview && !editing && (preview.segments.length > 1 || chosen)}<span class="as">as {previewNames || 'no one — pick a speaker'}</span>{/if}
          {#if editing && editingParts}
            {#each editingParts as part, i (i)}
              <label class="segment-edit">
                <span>{editing.segments[i].authors.map((a) => people.get(a)?.name ?? 'Someone').join(' & ')}</span>
                <textarea value={part} onbeforeinput={(e) => segmentInputSelections.set(i, { from: e.currentTarget.selectionStart, to: e.currentTarget.selectionEnd, type: e.inputType })}
                  oninput={(e) => { editingDocs[i] = inputDraft(editingDocs[i], e.currentTarget.value, segmentInputSelections.get(i) ?? null); segmentInputSelections.delete(i); editingParts![i] = e.currentTarget.value; }} onkeydown={onkey} rows="2" aria-label={`Edit segment ${i + 1}`}></textarea>
              </label>
            {/each}
          {:else}
            <textarea
              bind:this={box}
              value={draft}
              onkeydown={onkey}
              onbeforeinput={(e) => (inputSelection = { from: e.currentTarget.selectionStart, to: e.currentTarget.selectionEnd, type: e.inputType })}
              oninput={(e) => { draftDoc = inputDraft(draftProvenance, e.currentTarget.value, inputSelection); inputSelection = null; draft = e.currentTarget.value; trackCursor(); }}
              onselect={trackCursor}
              onclick={trackCursor}
              onkeyup={trackCursor}
              oncompositionstart={() => (composing = true)}
              oncompositionend={() => { composing = false; trackCursor(); }}
              onpaste={pasteFiles}
              rows="1"
              placeholder={editing ? 'Edit message' : !current ? '' : aside ? asidePlaceholder(systemName) : 'Message'}
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
        {#if !editing}
          <button class="in-pill" class:on={optionsOpen || !!cw.trim() || pollOn || visibilityMode === 'members'} onclick={() => (optionsOpen = !optionsOpen)}
            aria-label="Message options" aria-expanded={optionsOpen} title="Content warning, poll, who can see it"><Icon name="sliders" size={20} /></button>
          <button class="in-pill" onclick={() => (attaching = true)} title="Attach" aria-label="Attach"><Icon name="attach" size={22} /></button>
        {/if}
      </div>
      <button class="send" class:ready={editing ? true : canSend} onclick={() => void send()} aria-label={editing ? 'Save' : aside ? 'Send as aside' : 'Send'} title={editing ? 'Save' : aside ? 'Send as aside' : 'Send'}
        disabled={editing ? (editingParts ? !editingParts.some((part) => part.trim()) : !draft.trim()) : !canSend}><Icon name={editing ? 'read' : 'send'} size={20} /></button>
    </div>
    {/if}
    </div>
  </section>
  {#if showInfo}
    <aside class="room-info" aria-label="Chat info">
      <div class="info-heading"><strong>{space?.kind === 'dm' || (current?.kind === 'member_dm' && (current.member_ids?.length ?? 0) <= 2) ? 'Info' : 'Group info'}</strong><button class="icon-button" aria-label="Close chat info" onclick={() => (showInfo = false)}><Icon name="close" size={22} /></button></div>
      <div class="info-hero">
        <span class="room-symbol" style={roomDisc.style}>{#if roomDisc.member}<AvatarImage hash={roomDisc.member.avatar_blob} glyph={roomDisc.glyph} name={roomTitle} />{:else if roomDisc.blob}<AvatarImage hash={roomDisc.blob} glyph={roomDisc.glyph} name={roomTitle} />{:else}{roomDisc.glyph}{/if}</span>
        <h2>{roomTitle}</h2>
        <p class="info-kind">{space?.kind === 'internal' ? `Internal · only ${systemName}` : sides ? roomSubtitle : space?.kind === 'dm' ? 'Direct message'
          : spaceChannels.length > 1 ? `Shared · ${space?.name ?? 'space'}` : `Group · ${accountsLabel(directory.get(space?.id ?? '')?.accounts ?? []) || 'shared'}`}</p>
        {#if current?.topic}<p class="info-topic">{current.topic}</p>{/if}
      </div>
      <div class="info-actions" hidden={lockedOut}>
        <button onclick={() => setNotifyLevel(notifyLevel === 'none' ? (space?.kind === 'dm' ? 'all' : 'mentions') : 'none')} aria-pressed={notifyLevel === 'none'}>
          <Icon name={notifyLevel === 'none' ? 'muted' : 'muted'} size={20} />{notifyLevel === 'none' ? 'Unmute' : 'Mute'}</button>
        <button onclick={() => { searchHere = true; showInfo = false; }}><Icon name="search" size={20} />Search this chat</button>
        <button onclick={() => { showPins = !showPins; if (window.innerWidth < 1280) showInfo = false; }}><Icon name="pin" size={20} />Pinned{pinned.length ? ` · ${pinned.length}` : ''}</button>
        <button onclick={() => (folderSheet = true)}><Icon name="folder" size={20} />Folder</button>
        {#if groupEditable && spaceChannels.length <= 1}<button onclick={() => (editingGroup = true)}><Icon name="edit" size={20} />Edit</button>
        {:else if canEditChannel}<button onclick={() => (editingChannel = true)}><Icon name="edit" size={20} />Edit</button>{/if}
      </div>
      {#if space && !lockedOut}
        <SpacePanel {projection} {dark} {space} channel={current} info={directory.get(space.id)} {people} {speaker} isShown={shownNow}
          onchanged={() => dirTick++} oneditgroup={() => (editingGroup = true)}
          oneditchannel={canEditChannel && !(groupEditable && spaceChannels.length <= 1) ? () => (editingChannel = true) : undefined} />
      {/if}
      <div class="info-tabs"><UnderlineTabs tabs={infoTabs} bind:value={infoTab} label="Chat info" /></div>
      {#if infoShown === 'members'}
        <div class="info-members">
          {#each byLayer.slice(0, 30) as { m, layer, unread: behind } (m.id)}
            {@const c = core.adaptColor(m.color, dark)}
            <a href="#/profile/{m.id}" class:here={!!layer}>
              <span class="info-avatar" style="background: {c.ring}; color: {c.avatar_ink}"><AvatarImage hash={m.avatar_blob} glyph={m.sigils[0] ?? m.name[0]} name={m.name} /></span>
              <span class="info-name">{m.display_name ?? m.name}{#if layer}<small>{layer}</small>{/if}</span>
              {#if behind}<span class="behind">{behind} unread</span>{/if}
            </a>
          {/each}
          {#if byLayer.length > 30}<a class="see-all" href="#/members">See all {byLayer.length} members</a>{/if}
        </div>
      {:else if infoShown === 'media'}
        {#if roomMedia.length}
          <div class="info-media">
            {#each roomMedia.slice(0, 30) as a (a.id)}<div class="media-tile"><AttachmentView attachment={a} /></div>{/each}
          </div>
        {:else}<EmptyState icon="image" label="No media" />{/if}
      {:else if infoShown === 'files'}
        {#each roomFiles.slice(0, 30) as attachment (attachment.id)}<AttachmentView {attachment} />{:else}<EmptyState icon="doc" label="No files" />{/each}
      {:else if infoShown === 'links'}
        {#each roomLinks.slice(0, 30) as link (link.id)}<a class="info-link" href={link.url} target="_blank" rel="noopener noreferrer">{link.label}</a>{:else}<EmptyState icon="link" label="No links" />{/each}
      {:else}
        {#each roomPolls.slice(0, 30) as poll (poll.id)}
          <a class="info-poll" href="#/chat/{current?.id}/{poll.id}" onclick={() => { if (window.innerWidth < 1280) showInfo = false; }}>
            <Icon name="poll" size={18} /><span class="info-name">{poll.title}<small>{poll.closed ? 'Closed' : 'Open'} · {poll.votes} {poll.votes === 1 ? 'vote' : 'votes'}</small></span></a>
        {:else}<EmptyState icon="poll" label="No polls" />{/each}
      {/if}
    </aside>
  {/if}
</div>
{#if folderSheet && current}
  <Sheet title="Folder" onclose={() => (folderSheet = false)}>
    {#each folderSettings.custom as f (f.id)}
      <button class="folder-row" role="menuitemcheckbox" aria-checked={holds(f, current.id, chatFolder)} onclick={() => toggleFolder(f.id)}>
        <Icon name="folder" size={20} /><span>{f.name || 'Folder'}</span>{#if holds(f, current.id, chatFolder)}<Icon name="check" size={18} />{/if}</button>
    {/each}
    <button class="folder-row" onclick={() => { folderSheet = false; foldersAt = `new:${current?.id}`; }}><Icon name="plus" size={20} /><span>New folder…</span></button>
  </Sheet>
{/if}
{#if attaching && !lockedOut}
  <AttachSheet bind:pending bind:draft {pollOn} {canSend} journal={ownPosts} onphotos={() => photoInput?.click()} onfiles={() => fileInput?.click()}
    onpoll={() => { optionsOpen = true; pollOn = true; }} onlink={(id) => { draft = draft + (draft && !/\s$/.test(draft) ? ' ' : '') + linkTo(location.origin, { kind: 'post', id }); }}
    onsend={() => void send()} onclose={() => (attaching = false)} />
{/if}
{#if forwarding && !lockedOut}
  <ForwardSheet targets={targetRows} {dark} speaker={speaker ? people.get(speaker) ?? null : null} label="Forward to" exclude={current?.id}
    warning={leavingCard} onsend={forwardTo} onclose={() => (forwarding = null)} />
{/if}
{#if replyIn}
  <ForwardSheet targets={targetRows} {dark} label="Reply in" placeholder="Reply in…" exclude={current?.id} onpick={replyElsewhere} onclose={() => (replyIn = null)} />
{/if}
{#if quoteIn && quoting}
  <ForwardSheet targets={targetRows} {dark} label="Quote in" placeholder="Quote in…" exclude={current?.id} warning={leavingCard}
    onpick={(id) => { quoteIn = false; moveQuote(id); }} onclose={() => (quoteIn = false)} />
{/if}
{#if newChat}<NewChat {projection} {dark} start={newChat} onclose={() => (newChat = false)} />{/if}
{#if editingGroup && space && !lockedOut}
  <ChannelEditor {projection} {dark} spaceId={space.id} spaceKind={space.kind} group={space}
    channel={spaceChannels.length <= 1 ? spaceChannels[0] : undefined} onclose={() => (editingGroup = false)} />
{/if}
{#if addingChannel && space}
  <ChannelEditor {projection} {dark} spaceId={space.id} spaceKind={space.kind} onclose={() => (addingChannel = false)} onsaved={(id) => router.go(`/chat/${id}`)} />
{/if}
{#if editingChannel && current && space && !lockedOut}
  <ChannelEditor {projection} {dark} spaceId={space.id} spaceKind={space.kind} channel={current}
    canDelete={space.kind === 'internal' || canManage || !!sides?.mine.length} onclose={() => (editingChannel = false)} />
{/if}

<style>
  .room { position: relative; }
  .search-overlay { position: absolute; inset: 0; z-index: 5; background: var(--bg); overflow: auto; }
  .search-underlay { display: contents; }
  .info-link { display: block; overflow-wrap: anywhere; padding: 8px 0; }
  .unicode-emoji { display: grid; grid-template-columns: repeat(8, 1fr); gap: 2px; }
  .unicode-emoji button { font-size: 22px; padding: 7px; justify-content: center; }
  .mention-suggestions { display: grid; padding: 4px 8px; border-top: 1px solid var(--line); background: var(--surface); }
  .mention-suggestions button { display: flex; align-items: center; gap: 12px; min-height: 48px; padding: 4px 8px; border: 0; border-radius: 10px; background: none; color: var(--ink); font: inherit; text-align: left; cursor: pointer; }
  .mention-suggestions button:hover, .mention-suggestions button.active { background: var(--surface-2); }
  .mention-suggestions strong { flex: 1; min-width: 0; font-size: 15px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .mention-suggestions small { color: var(--ink-3); font-size: 12px; white-space: nowrap; }
  .m-face { width: 32px; height: 32px; flex: none; display: grid; place-items: center; overflow: hidden; border-radius: 50%; background: var(--surface-3, var(--surface-2)); color: var(--ink-2); font-weight: 600; font-size: 14px; }
  .m-face.front { background: var(--accent-soft); color: var(--accent); }
  .m-face :global(img) { width: 100%; height: 100%; object-fit: cover; }
  .reading-as { display: flex; align-items: center; gap: 8px; padding: 0 4px 0 12px; background: var(--accent-soft, var(--surface-2)); color: var(--ink-2); font-size: 12px; line-height: 16px; }
  .reading-as .who { width: 24px; height: 24px; flex: none; display: grid; place-items: center; overflow: hidden; border-radius: 50%; font-weight: 700; }
  .reading-as .says { flex: 1; min-width: 0; }
  .reading-as button { min-height: 36px; padding: 0 10px; border: 0; background: none; color: var(--accent); font: inherit; cursor: pointer; }
  .jump-latest { align-self: flex-end; margin: 8px 16px; border-radius: 24px; padding: 8px 14px; background: var(--surface-2); animation: chorus-pop-in var(--t-fast) var(--ease); }
  /* bars over the composer, the info panel and in-chat search arrive rather than pop in */
  .bar, .not-sent li, .msg-options { animation: chorus-rise-in var(--t-fast) var(--ease); }
  .room-info { animation: chorus-slide-in var(--t-base) var(--ease); }
  .search-overlay { animation: chorus-fade-in var(--t-fast) var(--ease); }
  .history-message { display: grid; gap: var(--s-2); padding: var(--s-4); border: 1px solid var(--line); border-radius: var(--r-sm); background: var(--surface-2); }
  .history-message button { width: fit-content; font: inherit; border: 1px solid var(--line); border-radius: var(--r-sm); background: var(--surface); color: var(--ink); padding: var(--s-2); cursor: pointer; }
  .history-message p { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; }
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
  .pending-file button { border: 0; background: none; color: var(--accent); cursor: pointer; }
  .upload-error { color: var(--danger, #b33); font-size: var(--fs-sm); }
  .speaker-chips { display: flex; gap: 6px; overflow-x: auto; padding: 6px 12px 0; scrollbar-width: none; }
  .speaker-chips button { flex: none; display: inline-flex; align-items: center; gap: 6px; padding: 3px 10px 3px 3px; border: 1px solid var(--line); border-radius: var(--r-full);
    background: none; color: var(--ink-2); font: inherit; font-size: 13px; cursor: pointer; transition: background var(--t-fast) var(--ease), border-color var(--t-fast) var(--ease); }
  .speaker-chips button.on { border-color: var(--ring); background: var(--accent-soft); color: var(--ink); }
  .speaker-chips .avatar { width: 22px; height: 22px; border-radius: 50%; display: grid; place-items: center; overflow: hidden; font-size: 11px; font-weight: 700; }
  .speakers-pick { display: grid; gap: 2px; border: 0; padding: 0; margin: 0; max-height: 200px; overflow: auto; }
  .speakers-pick label { display: flex; align-items: center; gap: 6px; }
  .locked { display: grid; justify-items: center; gap: 8px; margin: auto; max-width: 340px; padding: 32px 16px; text-align: center; color: var(--ink-2); }
  .lock-disc { width: 64px; height: 64px; border-radius: 50%; display: grid; place-items: center; background: var(--surface-2); color: var(--ink-2); }
  .chat {
    display: grid;
    grid-template-columns: 260px minmax(0, 1fr);
    gap: 0;
    height: calc(100dvh - 76px);
    min-width: 0;
  }
  @media (max-width: 1023px) {
    .chat {
      grid-template-columns: minmax(0, 1fr);
      grid-template-rows: minmax(0, 1fr);
      height: calc(100dvh - 144px - env(safe-area-inset-bottom));
    }
    .chat.listing .desk-list { display: grid; grid-template-columns: minmax(0, 1fr); min-height: 0; min-width: 0; }
    .chat.listing .desk-list > :global(*) { min-height: 0; }
    .chat.listing .room, .chat.listing .room-info { display: none; }
  }
  .room {
    display: flex;
    flex-direction: column;
    min-height: 0;
    background: var(--bg);
    min-width: 0;
  }
  header {
    display: flex;
    gap: var(--s-3);
    align-items: center;
    flex-wrap: wrap;
    background: var(--surface);
    padding: 10px 16px;
    border-bottom: 1px solid var(--line);
  }
  h1 {
    font-size: var(--fs-lg);
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
    padding: 20px 16px;
    gap: 8px;
    display: flex;
    flex-direction: column;
    min-height: 0;
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
    background: var(--surface);
    border-top: 1px solid var(--line);
  }
  .older-load { align-self: center; margin: 8px auto; padding: 4px 14px; border: 0; border-radius: var(--r-full); background: var(--surface-2); color: var(--accent); font: inherit; font-size: 13px; font-weight: 600; cursor: pointer; }
  .older-load:disabled { color: var(--ink-3); cursor: default; }
  .older { padding: var(--s-1) var(--s-3); color: var(--ink-2); }
  .older .who { font-weight: 600; margin-right: var(--s-2); }
  .older time { font-size: var(--fs-xs); color: var(--ink-3); }
  .not-sent { list-style: none; margin: 0; padding: var(--s-2); display: grid; gap: var(--s-1); border-left: 2px solid var(--line); }
  .not-sent li { display: flex; flex-wrap: wrap; align-items: center; gap: var(--s-2); }
  .not-sent .why { color: var(--ink-2); font-size: var(--fs-sm); }
  .not-sent .text { color: var(--ink-3); overflow-wrap: anywhere; }
  .segment-edit { display: grid; gap: 2px; font-size: var(--fs-xs); color: var(--ink-2); }
  .segment-edit + .segment-edit { margin-top: var(--s-2); }
  .chip-wrap {
    position: relative;
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
    min-width: 0;
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
    color: var(--on-accent);
    border: 0;
    border-radius: var(--r-full);
    padding: var(--s-2) var(--s-4);
    font-weight: 600;
    cursor: pointer;
  }
  .send:disabled {
    opacity: 0.5;
  }
  .room-info { background: var(--surface); padding: 16px; border-left: 1px solid var(--line); min-width: 0; overflow-y: auto; text-align: center; }
  .info-heading { display: flex; align-items: center; justify-content: space-between; font-size: var(--fs-sm); }
  .room-symbol { display: grid; place-items: center; width: 72px; height: 72px; margin: 16px auto; background: var(--accent); color: var(--on-accent); font-size: 28px; border-radius: 50%; }
  .room-info h2 { font-size: var(--fs-lg); overflow-wrap: anywhere; }
  .info-kind, .info-topic { font-size: var(--fs-xs); color: var(--ink-3); overflow-wrap: anywhere; }
  .info-actions { display: flex; gap: 8px; margin-block: 20px; }
  .info-actions button { display: flex; flex: 1; align-items: center; justify-content: center; flex-direction: column; gap: 6px; padding: 12px; border-radius: 12px; background: var(--surface-2); color: var(--accent); border: 0; font-size: var(--fs-xs); text-decoration: none; cursor: pointer; }
  .info-members { display: grid; gap: 12px; padding-block: 16px; text-align: left; }
  .info-members a { display: flex; align-items: center; gap: 10px; color: var(--ink); text-decoration: none; font-size: var(--fs-sm); min-height: 40px; }
  .info-avatar { display: grid; place-items: center; flex: none; width: 36px; height: 36px; border-radius: 50%; border: 2px solid var(--ring); overflow: hidden; }
  .info-members small { display: block; color: var(--ink-3); }
  @media (min-width: 1280px) { .chat.with-info { grid-template-columns: 260px minmax(0, 1fr) 260px; } }
  .desk-rail, .desk-list { display: none; }
  /* desktop (Figma F5.21): folder rail, chat list, conversation and, when open, info */
  @media (min-width: 1024px) {
    .chat { grid-template-columns: 76px 344px minmax(0, 1fr); height: 100dvh; }
    .desk-rail, .desk-list { display: grid; grid-template-columns: minmax(0, 1fr); min-height: 0; min-width: 0; }
    .desk-rail > :global(*), .desk-list > :global(*) { min-height: 0; }
  }
  @media (min-width: 1280px) { .chat.with-info { grid-template-columns: 76px 344px minmax(0, 1fr) 300px; } }
  @media (max-width: 1279px) { .room-info { position: fixed; inset: 76px 0 0 auto; width: min(340px, 100%); z-index: 8; box-shadow: var(--shadow-pop); } }
  @media (max-width: 1023px) { .room-info { top: 72px; bottom: calc(72px + env(safe-area-inset-bottom)); } header { gap: 4px; } }
  /* ── Figma F5.21 conversation ───────────────────────────────────────────── */
  .room-head { flex-wrap: nowrap; gap: 4px; padding: 8px 4px 8px 12px; min-height: 57px; }
  .room-head .icon-button.list-back { display: none; font-size: 26px; line-height: 1; color: var(--ink-2); text-decoration: none; width: 36px; }
  @media (max-width: 1023px) { .room-head .icon-button.list-back { display: grid; } }
  .room-head .thread-back { font-size: 26px; line-height: 1; color: var(--ink-2); text-decoration: none; width: 36px; }
  .room-disc { flex: none; width: 40px; height: 40px; border-radius: 50%; display: grid; place-items: center; overflow: hidden; font-weight: 700; font-size: 16px; }
  .titles { flex: 1; min-width: 0; display: grid; gap: 1px; padding: 0 6px; border: 0; background: none; color: inherit; text-align: left; cursor: pointer; font: inherit; }
  .titles h1 { margin: 0; font-size: 16px; line-height: 21px; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .titles .sub { font-size: 13px; line-height: 17px; color: var(--ink-3); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .titles .sub.typing { color: var(--accent); }
  .room-head .icon-button, .room-menu summary { width: 40px; height: 40px; display: grid; place-items: center; border-radius: 50%; color: var(--ink-2); }
  .room-head .icon-button:hover, .room-menu summary:hover, .room-menu[open] summary { background: var(--surface-2); color: var(--ink); }
  .room-menu summary::-webkit-details-marker { display: none; }
  .menu-items { top: calc(100% + 4px); width: 300px; padding: 6px; gap: 2px; background: var(--surface); border-radius: 14px; box-shadow: var(--shadow-pop); }
  .menu-items > button, .menu-items > a { display: flex; align-items: center; gap: 12px; min-height: 40px; padding: 8px 10px; border: 0; border-radius: 10px; background: none;
    color: var(--ink); font: inherit; font-size: var(--fs-sm); text-decoration: none; cursor: pointer; text-align: left; }
  .menu-items > button :global(.icon), .menu-items > a :global(.icon) { color: var(--ink-3); }
  .menu-items > button:hover, .menu-items > a:hover { background: var(--surface-2); }
  .room-menu .notify { border-top: 0; color: var(--ink-2); padding: 6px 10px; }
  .room-menu .notify select { padding: 4px 6px; }
  .emoji-manager { padding: 6px 10px; font-size: var(--fs-xs); color: var(--ink-3); }
  .emoji-manager summary { cursor: pointer; }
  .pinned-bar { display: flex; align-items: center; gap: 10px; width: 100%; min-height: 52px; padding: 8px 16px 8px 0; border: 0; border-bottom: 1px solid var(--line);
    background: var(--surface); color: var(--ink-3); text-align: left; cursor: pointer; font: inherit; }
  .pinned-bar:hover { background: var(--surface-2); }
  .pin-text { flex: 1; min-width: 0; display: grid; margin-left: 16px; padding-left: 10px; border-left: 2px solid var(--accent); font-size: 13px; line-height: 17px; }
  .pin-text strong { color: var(--accent); font-weight: 600; }
  .pin-text span { color: var(--ink-2); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .list { padding: 8px 16px 12px; gap: 0; }
  /* a short chat sits at the bottom, by the composer, like any messenger */
  .list::before { content: ''; flex: 1 0 0; }
  .service { display: flex; justify-content: center; margin: 10px 0 4px; }
  .service span { padding: 3px 12px; border-radius: var(--r-full); background: color-mix(in srgb, var(--surface-2) 85%, transparent); color: var(--ink-2); font-size: 12px; line-height: 17px; font-weight: 500; }
  .composer { align-items: center; gap: 8px; padding: 8px; }
  .composer .chip-wrap { flex: none; }
  .send-as { display: grid; place-items: center; padding: 2px; border: 1.5px solid var(--accent); border-radius: 50%; background: none; cursor: pointer; }
  .send-as .avatar { width: 32px; height: 32px; box-shadow: none; font-size: 13px; font-weight: 700; overflow: hidden; }
  .pill { flex: 1; min-width: 0; display: flex; align-items: flex-end; gap: 6px; padding: 5px 6px; border-radius: 20px; background: var(--surface-2); }
  .pill.aside { outline: 1.5px dashed var(--accent); outline-offset: -1.5px; }
  .audience { display: flex; align-items: center; gap: 10px; padding: 6px 8px 6px 14px; border-top: 1px solid var(--line); background: var(--surface); color: var(--ink-3); font-size: 13px; }
  .audience.on { background: var(--accent-soft); color: var(--accent); }
  .audience .says { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .aside-pill { flex: none; display: inline-flex; align-items: center; gap: 6px; min-height: 30px; padding: 0 12px; border: 1px solid var(--line); border-radius: var(--r-full); background: var(--surface-2);
    color: var(--ink-2); font: inherit; font-size: 13px; font-weight: 600; cursor: pointer; }
  .aside-pill[aria-pressed='true'] { background: var(--accent); border-color: var(--accent); color: var(--on-accent); }
  .pill:focus-within { box-shadow: 0 0 0 1.5px color-mix(in srgb, var(--accent) 60%, transparent); }
  .in-pill { flex: none; width: 32px; height: 32px; display: grid; place-items: center; border: 0; border-radius: 50%; background: none; color: var(--ink-3); cursor: pointer; }
  .in-pill:hover { color: var(--ink); background: color-mix(in srgb, var(--ink) 8%, transparent); }
  .in-pill.on { color: var(--accent); }
  .pill .input { align-self: center; }
  .pill textarea { border: 0; background: none; padding: 6px 2px; font-size: 15px; line-height: 20px; outline: 0; }
  .send { flex: none; width: 40px; height: 40px; padding: 0; display: grid; place-items: center; border-radius: 50%; color: var(--on-accent);
    transition: transform var(--t-fast) var(--ease), opacity var(--t-fast) var(--ease); }
  .send:disabled { opacity: .45; cursor: default; }
  .send.ready:not(:disabled):active { transform: scale(.92); }
  .msg-options { display: grid; gap: 6px; margin: 0 8px; padding: 10px 12px; border-radius: 14px; background: var(--surface); border: 1px solid var(--line);
    font-size: var(--fs-sm); color: var(--ink-2); }
  .msg-options label { display: flex; align-items: center; gap: 8px; }
  .msg-options input:not([type='checkbox']), .msg-options select { font: inherit; color: var(--ink); background: var(--surface-2); border: 1px solid var(--line); border-radius: var(--r-sm); padding: 4px 8px; }
  .msg-options summary { cursor: pointer; color: var(--ink-2); }
  .poll-draft { display: grid; gap: 6px; }
  .room-info { padding: 0; text-align: left; }
  .info-heading { display: flex; align-items: center; justify-content: space-between; min-height: 54px; padding: 0 9px 0 16px; font-size: 16px; }
  .info-heading strong { font-weight: 600; }
  .info-hero { display: grid; justify-items: center; gap: 4px; padding: 8px 16px 14px; text-align: center; }
  .room-symbol { margin: 0 0 4px; overflow: hidden; font-weight: 700; }
  .info-hero h2 { margin: 0; font-size: 18px; line-height: 24px; }
  .info-kind, .info-topic { margin: 0; }
  .info-actions { gap: 8px; margin: 0; padding: 0 16px 13px; border-bottom: 1px solid var(--line); }
  .info-actions button { gap: 4px; padding: 8px 4px; min-height: 54px; font-size: 11px; color: var(--accent); }
  .info-actions button[aria-pressed='true'] { color: var(--ink-2); }
  .info-tabs { position: sticky; top: 0; z-index: 1; background: var(--surface); }
  .info-poll { display: flex; align-items: center; gap: 10px; padding: 8px 16px; min-height: 56px; color: var(--ink); text-decoration: none; }
  .info-poll:hover { background: var(--surface-2); }
  .folder-row { display: flex; align-items: center; gap: 12px; width: 100%; min-height: 46px; padding: 8px 20px; border: 0; background: none; color: var(--ink); font: inherit; font-size: 15px; text-align: left; cursor: pointer; }
  .folder-row span { flex: 1; }
  .folder-row :global(.icon:last-child:not(:first-child)) { color: var(--accent); }
  .info-members { gap: 0; padding: 0 0 8px; }
  .info-members a { gap: 10px; padding: 8px 16px; min-height: 56px; }
  .info-members a:hover { background: var(--surface-2); }
  .info-avatar { width: 40px; height: 40px; border: 0; font-weight: 700; }
  .info-name { flex: 1; min-width: 0; display: grid; font-size: 15px; line-height: 20px; font-weight: 600; }
  .info-name small { color: var(--accent); font-size: 12px; line-height: 16px; font-weight: 400; }
  .behind { color: var(--accent); font-size: 13px; font-weight: 600; white-space: nowrap; }
  .see-all { color: var(--accent) !important; font-size: var(--fs-sm); min-height: 40px !important; }
  .info-media { display: grid; grid-template-columns: repeat(3, 1fr); gap: 3px; padding: 4px 16px 16px; }
  .media-tile { aspect-ratio: 87 / 72; overflow: hidden; border-radius: 6px; background: var(--surface-2); }
  .media-tile :global(img) { width: 100%; height: 100%; object-fit: cover; display: block; }
  .media-tile :global(figure), .media-tile :global(a), .media-tile :global(button) { margin: 0; width: 100%; height: 100%; }
</style>
