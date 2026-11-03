// Chat folders (D-113, Figma F5.25; Android's ChatFolders.kt): the built-in folders (All, Internal,
// Shared, Direct) can be hidden, every folder can be put in order, and folders of our own hold
// chosen chats, whole kinds of chat (less any left out), or both, optionally only while unread or
// leaving muted chats out. With the chat list's display choices it is one account pref,
// `chat_folders`, so the system's devices share it; which folder is open stays on the device.
import type { Folder } from './chat-list';

export const PREF = 'chat_folders';
export const BUILT_IN = ['all', 'internal', 'shared', 'direct'] as const;
/** The kinds of chat, as the built-in folders sort them (`folderOf`). */
export const KINDS = ['internal', 'shared', 'direct'] as const;
export type Kind = Exclude<Folder, 'all'>;

export interface Custom {
  id: string; name: string; chats: string[]; kinds: Kind[]; except: string[];
  unreadOnly: boolean; hideMuted: boolean; preset?: string;
}
export interface Settings { order: string[]; hidden: string[]; custom: Custom[]; showHere: boolean; compact: boolean }

/** A chat as the folder editor lists it; `own` = in our own internal space. */
export interface FolderChat { id: string; title: string; kind: Kind; own: boolean }

export const DEFAULTS: Settings = { order: [...BUILT_IN], hidden: [], custom: [], showHere: true, compact: false };

/** Every folder in display order: the saved order first, then any it doesn't name yet. */
export function ordered(s: Settings): string[] {
  const all: string[] = [...BUILT_IN, ...s.custom.map((f) => f.id)];
  const named = [...new Set(s.order.filter((id) => all.includes(id)))];
  return [...named, ...all.filter((id) => !named.includes(id))];
}

/** The folders shown in the rail. */
export function visible(s: Settings): string[] {
  return ordered(s).filter((id) => !s.hidden.includes(id));
}

const LABELS: Record<string, string> = { all: 'All chats', internal: 'Internal', shared: 'Shared', direct: 'Direct' };

export function label(s: Settings, id: string): string {
  return LABELS[id] ?? (s.custom.find((f) => f.id === id)?.name.trim() || 'Folder');
}

/** Chosen for `f`, by name or by kind, before its unread/muted filters. */
export function holds(f: Custom, channelId: string, kind: Kind): boolean {
  return f.chats.includes(channelId) || (f.kinds.includes(kind) && !f.except.includes(channelId));
}

/** Whether a chat of `kind` is in folder `id` right now (`unread` for whoever is reading). */
export function contains(s: Settings, id: string, channelId: string, kind: Kind, unread: number, muted: boolean): boolean {
  if (id === 'all') return true;
  if ((KINDS as readonly string[]).includes(id)) return kind === id;
  const f = s.custom.find((c) => c.id === id);
  return !!f && holds(f, channelId, kind) && (!f.unreadOnly || unread > 0) && (!f.hideMuted || !muted);
}

/** Put a chat in or out of `f`: a chosen chat, or a chat of an included kind left out. */
export function setChat(f: Custom, channelId: string, kind: Kind, inside: boolean): Custom {
  const byKind = f.kinds.includes(kind);
  if (inside) return { ...f, chats: byKind ? f.chats.filter((c) => c !== channelId) : f.chats.includes(channelId) ? f.chats : [...f.chats, channelId],
    except: f.except.filter((c) => c !== channelId) };
  return { ...f, chats: f.chats.filter((c) => c !== channelId),
    except: byKind ? [...new Set([...f.except, channelId])] : f.except.filter((c) => c !== channelId) };
}

/** Include or stop including every chat of `kind`; its left-out chats are forgotten either way. */
export function setKind(f: Custom, kind: Kind, on: boolean, kindOf: (channelId: string) => Kind | undefined): Custom {
  return { ...f,
    kinds: on ? [...new Set([...f.kinds, kind])] : f.kinds.filter((k) => k !== kind),
    except: f.except.filter((c) => kindOf(c) !== kind),
    chats: on ? f.chats.filter((c) => kindOf(c) !== kind) : f.chats };
}

/** Move folder `id` to place `to` in `ordered`. */
export function move(s: Settings, id: string, to: number): Settings {
  const order = ordered(s);
  const from = order.indexOf(id);
  if (from < 0 || to < 0 || to >= order.length || from === to) return s;
  order.splice(to, 0, ...order.splice(from, 1));
  return { ...s, order };
}

export function withCustom(s: Settings, folder: Custom): Settings {
  return { ...s, custom: s.custom.some((f) => f.id === folder.id) ? s.custom.map((f) => (f.id === folder.id ? folder : f)) : [...s.custom, folder] };
}

export function without(s: Settings, id: string): Settings {
  return { ...s, custom: s.custom.filter((f) => f.id !== id), order: s.order.filter((o) => o !== id), hidden: s.hidden.filter((h) => h !== id) };
}

/** Figma F5.25's suggestions, each a folder of our own to start from. */
export const SUGGESTIONS = [
  { preset: 'unread', title: 'Unread', subtitle: 'Unread for whoever is reading' },
  { preset: 'handover', title: 'Handover and notes', subtitle: 'Your handover and notes chats' },
  { preset: 'unmuted', title: 'Not muted', subtitle: 'Every chat but the muted ones' },
] as const;

/** A new folder from a suggestion; "Handover and notes" starts with our own chats named that way. */
export function fromSuggestion(id: string, preset: string, title: string, ours: { id: string; name: string }[]): Custom {
  const base: Custom = { id, name: title, chats: [], kinds: [], except: [], unreadOnly: false, hideMuted: false, preset };
  if (preset === 'unread') return { ...base, kinds: [...KINDS], unreadOnly: true };
  if (preset === 'unmuted') return { ...base, kinds: [...KINDS], hideMuted: true };
  return { ...base, chats: ours.filter((c) => /handover|notes?\b/i.test(c.name)).map((c) => c.id) };
}

function strings(v: unknown): string[] {
  return Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string' && x.trim() !== '') : [];
}

export function parse(value: unknown): Settings {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return { ...DEFAULTS, order: [...BUILT_IN] };
  const o = value as Record<string, unknown>;
  const seen = new Set<string>();
  const custom: Custom[] = [];
  for (const raw of Array.isArray(o.custom) ? o.custom : []) {
    if (!raw || typeof raw !== 'object') continue;
    const f = raw as Record<string, unknown>;
    const id = typeof f.id === 'string' ? f.id : '';
    if (!id.trim() || (BUILT_IN as readonly string[]).includes(id) || seen.has(id)) continue;
    seen.add(id);
    custom.push({ id, name: typeof f.name === 'string' ? f.name : '', chats: [...new Set(strings(f.chats))],
      kinds: [...new Set(strings(f.kinds).filter((k): k is Kind => (KINDS as readonly string[]).includes(k)))],
      except: [...new Set(strings(f.except))], unreadOnly: f.unread_only === true, hideMuted: f.hide_muted === true,
      ...(typeof f.preset === 'string' && f.preset ? { preset: f.preset } : {}) });
  }
  const order = strings(o.order);
  return { order: order.length ? order : [...BUILT_IN], hidden: [...new Set(strings(o.hidden))], custom,
    showHere: o.show_here !== false, compact: o.compact === true };
}

/** The pref's value, as Android writes it. */
export function toJson(s: Settings): Record<string, unknown> {
  const all = ordered(s);
  return {
    order: all,
    hidden: s.hidden.filter((h) => all.includes(h)).sort(),
    custom: s.custom.map((f) => ({ id: f.id, name: f.name, chats: f.chats, kinds: KINDS.filter((k) => f.kinds.includes(k)),
      except: [...f.except].sort(), unread_only: f.unreadOnly, hide_muted: f.hideMuted, ...(f.preset ? { preset: f.preset } : {}) })),
    show_here: s.showHere, compact: s.compact,
  };
}

type PrefRows = Record<string, { fields: Record<string, unknown> } | undefined>;

/** The account's folder settings; a change still on its way wins over the synced one. */
export function folderPrefs(prefs: PrefRows, accountId: string): Settings {
  return parse((prefs[`||${PREF}`] ?? prefs[`${accountId}||${PREF}`])?.fields.value);
}

/** A chat muted for this account (its notify level is "none"). */
export function mutedChat(prefs: PrefRows, accountId: string, channelId: string): boolean {
  return (prefs[`${accountId}||notify_channel:${channelId}`] ?? prefs[`||notify_channel:${channelId}`])?.fields.value === 'none';
}
