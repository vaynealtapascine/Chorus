// How Fronting shows each front (Figma F3.1, F3.4, F3.9, F3.11; D-122; Android FrontingDisplay.kt).
// One account pref, `fronting.display`, shared by the system's devices like `chat_folders`.
// Display only: layer meaning, order and names stay in `front.layer` / `front.define` (D-080).
// Also `member_status:<id>` = {tags, note, at, clear_on_leave, text}: a member's states while here
// (F3.10, docs/FRONTS.md); an older `{text, at}` reads as a note.
import type { FrontPresence } from './core/fronts';
import { sync } from './sync/client';

export const PREF = 'fronting.display';
export const STATUS = 'member_status:';
/** The pseudo-layer of everyone not in a front; never a real layer ID. */
export const AWAY = 'away';

export type Style = 'solid' | 'dashed' | 'faint' | 'plain';
export const STYLES: Style[] = ['solid', 'dashed', 'faint', 'plain'];
export type Preset = 'simple' | 'detailed' | 'complete';
export const PRESETS: { id: Preset; label: string; about: string }[] = [
  { id: 'simple', label: 'Simple', about: 'Front and co-conscious' },
  { id: 'detailed', label: 'Detailed', about: 'Layers + custom front statuses' },
  { id: 'complete', label: 'Complete', about: 'All layers, status, & front notes' },
];
/** Front icons a system can pick (web icon names). */
export const ICONS = ['target', 'message', 'book', 'briefcase', 'monitor', 'layers', 'globe'];
/** `followsBody`: the template starts by following Body (Figma F3.4's Chorus front). */
export const TEMPLATES: { name: string; icon: string; about: string; followsBody?: boolean }[] = [
  { name: 'Online', icon: 'message', about: 'Who can post in chats. Follows Body until changed by hand.', followsBody: true },
  { name: 'Work', icon: 'briefcase', about: 'Who handles work.' },
  { name: 'Inner world', icon: 'book', about: 'Inner world. Layers are rooms you named yourselves.' },
  { name: 'Subsystem', icon: 'layers', about: "A subsystem's own front." },
];

export interface LayerLook { style: Style | null; about: string | null; separate: boolean; color: string | null }
export interface FrontView {
  icon: string | null; about: string | null; hidden: boolean; preset: Preset; hiddenLayers: string[];
  hideEmpty: boolean; collapseAway: boolean; showTime: boolean; showOthers: boolean;
}
export interface Settings { layers: Record<string, LayerLook>; fronts: Record<string, FrontView>; order: string[]; hintDismissed: boolean }

export const DEFAULT_VIEW: FrontView = {
  icon: null, about: null, hidden: false, preset: 'detailed', hiddenLayers: [],
  hideEmpty: true, collapseAway: true, showTime: true, showOthers: true,
};
const NO_LOOK: LayerLook = { style: null, about: null, separate: false, color: null };

const str = (v: unknown): string | null => (typeof v === 'string' && v.trim() ? v : null);
const strings = (v: unknown): string[] => (Array.isArray(v) ? v.map((x) => (typeof x === 'string' || typeof x === 'number' ? String(x) : '')).filter(Boolean) : []);
const bool = (v: unknown, d: boolean) => (typeof v === 'boolean' ? v : d);
const obj = (v: unknown): Record<string, unknown> => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : {});

export function parse(value: unknown): Settings {
  const o = obj(value);
  const layers: Record<string, LayerLook> = {};
  for (const [id, raw] of Object.entries(obj(o.layers))) {
    const v = obj(raw);
    const style = str(v.style);
    const color = str(v.color);
    layers[id] = { style: STYLES.includes(style as Style) ? (style as Style) : null, about: str(v.about), separate: v.separate === true,
      color: color && /^#[0-9A-Fa-f]{6}$/.test(color) ? color : null };
  }
  const fronts: Record<string, FrontView> = {};
  for (const [id, raw] of Object.entries(obj(o.fronts))) {
    const v = obj(raw);
    const icon = str(v.icon);
    const preset = str(v.preset);
    fronts[id] = {
      icon: icon && ICONS.includes(icon) ? icon : null, about: str(v.about), hidden: v.hidden === true,
      preset: PRESETS.some((p) => p.id === preset) ? (preset as Preset) : DEFAULT_VIEW.preset, hiddenLayers: strings(v.hidden_layers),
      hideEmpty: bool(v.hide_empty, true), collapseAway: bool(v.collapse_away, true), showTime: bool(v.show_time, true), showOthers: bool(v.show_others, true),
    };
  }
  return { layers, fronts, order: [...new Set(strings(o.order))], hintDismissed: o.hint_dismissed === true };
}

export function toJson(s: Settings): Record<string, unknown> {
  const layers: Record<string, unknown> = {};
  for (const [id, l] of Object.entries(s.layers)) {
    const v: Record<string, unknown> = {};
    if (l.style) v.style = l.style;
    if (l.about) v.about = l.about;
    if (l.separate) v.separate = true;
    if (l.color) v.color = l.color;
    layers[id] = v;
  }
  const fronts: Record<string, unknown> = {};
  for (const [id, f] of Object.entries(s.fronts)) {
    const v: Record<string, unknown> = { hidden: f.hidden, preset: f.preset, hidden_layers: [...f.hiddenLayers].sort(),
      hide_empty: f.hideEmpty, collapse_away: f.collapseAway, show_time: f.showTime, show_others: f.showOthers };
    if (f.icon) v.icon = f.icon;
    if (f.about) v.about = f.about;
    fronts[id] = v;
  }
  return { layers, fronts, order: s.order, hint_dismissed: s.hintDismissed };
}

type PrefRows = Record<string, { exists?: boolean; fields: Record<string, unknown> } | undefined>;

/** The pref as this device sees it: a pending local change wins over the stamped row. */
export function displayPrefs(prefs: PrefRows | undefined, accountId: string): Settings {
  return parse((prefs?.[`||${PREF}`] ?? prefs?.[`${accountId}||${PREF}`])?.fields.value);
}

/** A member's states while here (F3.10; Android MemberStatus): built-in keys, `state:<custom state id>`
 * or their own words, and a note. `extra` keeps fields this client doesn't know, so a write keeps them. */
export interface MemberStatus { tags: string[]; note: string | null; at: number; clearOnLeave: boolean; extra?: Record<string, unknown> }
export interface CustomState { id: string; name: string }
export const MAX_STATUS_TAG = 40;
export const MAX_STATUS_NOTE = 200;
export const BUILT_IN_STATUSES: [string, string][] = [['tired', 'Tired'], ['nonverbal', 'Nonverbal'], ['busy', 'Busy'],
  ['low_spoons', 'Low spoons'], ['needs_quiet', 'Needs quiet'], ['out_and_about', 'Out and about']];
const KNOWN_STATUS = new Set(['tags', 'note', 'at', 'clear_on_leave', 'text']);

export function statusLabel(tag: string, states: CustomState[]): string {
  const built = BUILT_IN_STATUSES.find(([k]) => k === tag);
  if (built) return built[1];
  if (tag.startsWith('state:')) return states.find((s) => s.id === tag.slice(6))?.name ?? 'State';
  return tag;
}
/** "Tired · Nonverbal · Typing only today, please" */
export const statusLine = (s: MemberStatus, states: CustomState[] = []): string =>
  [...s.tags.map((t) => statusLabel(t, states)), ...(s.note ? [s.note] : [])].join(' · ');
/** What the state picker offers: the built-in states, the system's own, and any words this one already has. */
export const statusChoices = (states: CustomState[], current: MemberStatus | null | undefined): string[] =>
  [...new Set([...BUILT_IN_STATUSES.map(([k]) => k), ...states.map((s) => `state:${s.id}`), ...(current?.tags ?? [])])];
/** Whether a state still shows for someone here since `since`: one meant to clear stays gone once they
 * left and came back (a minute's slack for the clock of the device that set it). */
export const statusShowing = (s: MemberStatus, since: number | null | undefined): boolean =>
  !s.clearOnLeave || since == null || since <= s.at + 60_000;

export function status(value: unknown): MemberStatus | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  const o = value as Record<string, unknown>;
  const tags = [...new Set(strings(o.tags).map((t) => t.slice(0, MAX_STATUS_TAG)))].slice(0, 8);
  const note = str('tags' in o ? o.note : o.text)?.trim().slice(0, MAX_STATUS_NOTE) || null;
  if (!tags.length && !note) return null;
  const extra = Object.fromEntries(Object.entries(o).filter(([k]) => !KNOWN_STATUS.has(k)));
  return { tags, note, at: Number(o.at) || 0, clearOnLeave: o.clear_on_leave === true, ...(Object.keys(extra).length ? { extra } : {}) };
}
/** The pref value for a status (null clears it); `states` name the system's own states in `text`, for older readers. */
export function statusValue(s: MemberStatus | null, states: CustomState[] = []): Record<string, unknown> | null {
  const note = s?.note?.trim().slice(0, MAX_STATUS_NOTE) || null;
  if (!s || (!s.tags.length && !note)) return null;
  const tidy = { ...s, note };
  return { ...s.extra, tags: s.tags, ...(note ? { note } : {}), at: s.at, clear_on_leave: s.clearOnLeave, text: statusLine(tidy, states) };
}
/** The system's custom states (ENTITIES §2), for state labels. */
export function customStates(rows: Record<string, { exists?: boolean; fields: Record<string, unknown> } | undefined> | undefined): CustomState[] {
  return Object.entries(rows ?? {}).filter(([, r]) => r?.exists && !r.fields.deleted_at)
    .map(([id, r]) => ({ id, name: str(r!.fields.name) ?? 'State' }));
}

/** Every member's status; a pending local change (including a clear) wins over the stamped row. */
export function memberStatuses(prefs: PrefRows | undefined, accountId: string): Map<string, MemberStatus> {
  const stamped = new Map<string, unknown>();
  const pending = new Map<string, unknown>();
  for (const [k, row] of Object.entries(prefs ?? {})) {
    const [acct, device, key] = k.split('|');
    if (device !== '' || !key?.startsWith(STATUS) || (acct !== '' && acct !== accountId) || row?.exists === false) continue;
    (acct === '' ? pending : stamped).set(key.slice(STATUS.length), row?.fields.value);
  }
  const out = new Map<string, MemberStatus>();
  for (const [id, v] of new Map([...stamped, ...pending])) {
    const s = status(v);
    if (s) out.set(id, s);
  }
  return out;
}

export function save(s: Settings) {
  return sync.create('pref.set', sync.accountScope, null, { device: '', key: PREF, value: toJson(s) });
}
export function setStatus(memberId: string, s: MemberStatus | null, states: CustomState[] = []) {
  return sync.create('pref.set', sync.accountScope, null, { device: '', key: STATUS + memberId, value: statusValue(s, states) });
}

export const front = (s: Settings, id: string): FrontView => s.fronts[id] ?? DEFAULT_VIEW;
export const layer = (s: Settings, id: string): LayerLook => s.layers[id] ?? NO_LOOK;
export const withFront = (s: Settings, id: string, f: (v: FrontView) => FrontView): Settings => ({ ...s, fronts: { ...s.fronts, [id]: f(front(s, id)) } });
export const withLayer = (s: Settings, id: string, f: (l: LayerLook) => LayerLook): Settings => ({ ...s, layers: { ...s.layers, [id]: f(layer(s, id)) } });

export const defaultStyle = (nestedIndex: number): Style => (nestedIndex === 0 ? 'solid' : nestedIndex === 1 ? 'dashed' : 'faint');
const ABOUT: Record<string, string> = {
  front: 'In control · counts as fronting',
  cocon: 'Present · counts as co-fronting',
  present: 'Around · shown, not counted',
};
export const defaultAbout = (layerId: string): string | null => ABOUT[layerId] ?? null;
/** The short line under a layer's name on the front card: the first part of its description. */
export function shortAbout(about: string | null): string | null {
  const s = about?.split(' · ')[0]?.trim();
  return s ? s[0].toLowerCase() + s.slice(1) : null;
}

/** Fronts in the system's chosen order: arranged ones first, then Body, then by name. */
export function ordered<T extends { id: string; name: string }>(fronts: T[], bodyId: string, s: Settings): T[] {
  const rank = new Map(s.order.map((id, i) => [id, i]));
  return [...fronts].sort((a, b) => (rank.get(a.id) ?? Infinity) - (rank.get(b.id) ?? Infinity) || Number(b.id === bodyId) - Number(a.id === bodyId)
    || a.name.toLowerCase().localeCompare(b.name.toLowerCase()) || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
}

export function icon(frontId: string, name: string, bodyId: string, s: Settings): string {
  return front(s, frontId).icon ?? (frontId === bodyId ? 'target' : TEMPLATES.find((t) => t.name.toLowerCase() === name.toLowerCase())?.icon ?? 'layers');
}

/** "1h 24m", "32m", "now". */
export function duration(ms: number): string {
  const m = Math.max(0, Math.floor(ms / 60_000));
  if (m < 1) return 'now';
  if (m < 60) return `${m}m`;
  if (m < 1440) return `${Math.floor(m / 60)}h ${m % 60}m`;
  return `${Math.floor(m / 1440)}d ${Math.floor(m / 60) % 24}h`;
}

export interface SceneLayer { id: string; name: string; about: string | null; style: Style; color: string | null; separate: boolean; hidden: boolean; entries: FrontPresence[] }
export interface Scene { nested: SceneLayer[]; separate: SceneLayer[]; all: SceneLayer[]; unlisted: FrontPresence[]; away: string[]; awayShown: boolean; here: number }

/** Sorts a front's entries into the boxes Fronting draws (Android FrontScene). `members` are the
 * own active member IDs, for Away. */
export function scene(layers: { id: string; name: string }[], entries: FrontPresence[], s: Settings, frontId: string, members: string[]): Scene {
  const view = front(s, frontId);
  let nested = 0;
  const all = layers.map((l): SceneLayer => {
    const look = layer(s, l.id);
    const style = look.style ?? (look.separate ? 'plain' : defaultStyle(nested));
    if (!look.separate) nested++;
    return { id: l.id, name: l.name, about: look.about ?? defaultAbout(l.id), style, color: look.color, separate: look.separate,
      hidden: view.hiddenLayers.includes(l.id), entries: entries.filter((e) => e.layer_id === l.id) };
  });
  const known = new Set(all.map((l) => l.id));
  const present = new Set(entries.filter((e) => e.subject_type === 'member').map((e) => e.subject_id));
  return {
    nested: all.filter((l) => !l.separate && !l.hidden), separate: all.filter((l) => l.separate && !l.hidden), all,
    unlisted: entries.filter((e) => !known.has(e.layer_id)), away: members.filter((id) => !present.has(id)),
    awayShown: !view.hiddenLayers.includes(AWAY), here: entries.length,
  };
}

/** The primary flag for each placement, walking the front as it changes (Android Front.withPrimary):
 * leaving the innermost layer gives up primary (core keeps the flag otherwise, so the default
 * speaker would stay), and the first to enter an innermost layer with no primary becomes it. */
export function withPrimary<M extends { type: string; id: string; layer: string | null; primary?: boolean }>(
  moves: M[], current: FrontPresence[], inner: string | undefined): M[] {
  const now = new Map(current.map((e) => [`${e.subject_type}:${e.subject_id}`, e]));
  return moves.map((m) => {
    const key = `${m.type}:${m.id}`;
    const was = now.get(key);
    let primary = m.primary;
    if (primary === undefined && m.layer !== null) {
      if (m.layer === inner && ![...now.values()].some((e) => e.layer_id === inner && e.is_primary)) primary = true;
      else if (was?.is_primary && m.layer !== inner) primary = false;
    }
    if (m.layer === null) now.delete(key);
    else now.set(key, { subject_type: m.type as FrontPresence['subject_type'], subject_id: m.id, layer_id: m.layer, is_primary: primary ?? was?.is_primary ?? false });
    return primary === undefined ? m : { ...m, primary };
  });
}

export interface StoryLine { changeId: string; at: number; subjectType: string; subjectId: string; text: string; layerId: string | null }
export interface FrontState { id: string; at: number; entries: FrontPresence[] }

/** Successive front states as plain sentences (F3.7; Android FrontStory). */
export function story(states: FrontState[], order: string[], outermost: string | null, layerName: (id: string) => string,
  name: (type: string, id: string) => string): StoryLine[] {
  const inner = order[0];
  const out: StoryLine[] = [];
  let prev: FrontPresence[] = [];
  const key = (e: FrontPresence) => `${e.subject_type}:${e.subject_id}`;
  for (const s of [...states].sort((a, b) => a.at - b.at)) {
    const before = new Map(prev.map((e) => [key(e), e]));
    const after = new Map(s.entries.map((e) => [key(e), e]));
    const leftFront = prev.filter((e) => e.layer_id === inner && after.get(key(e))?.layer_id !== inner);
    const stayed = s.entries.filter((e) => e.layer_id === inner && before.get(key(e))?.layer_id === inner);
    const mentioned = new Set<string>();
    for (const e of s.entries) {
      const was = before.get(key(e));
      const who = name(e.subject_type, e.subject_id);
      let text: string | null;
      if (was?.layer_id === e.layer_id) text = null;
      else if (e.layer_id === inner && !was && leftFront.length) { mentioned.add(key(leftFront[0])); text = `${who} took over from ${name(leftFront[0].subject_type, leftFront[0].subject_id)}`; }
      else if (e.layer_id === inner && !was && stayed.length) text = `${who} joined ${name(stayed[0].subject_type, stayed[0].subject_id)}`;
      else if (!was && e.layer_id === inner) text = `${who} started fronting`;
      else if (!was && e.layer_id === outermost) text = `${who} came close`;
      else if (!was) text = `${who} is ${layerName(e.layer_id).toLowerCase()}`;
      else {
        const rank = (id: string) => (order.indexOf(id) < 0 ? Infinity : order.indexOf(id));
        text = rank(e.layer_id) < rank(was.layer_id) ? `${who} moved inward` : `${who} moved outward`;
      }
      if (text) out.push({ changeId: s.id, at: s.at, subjectType: e.subject_type, subjectId: e.subject_id, text, layerId: e.layer_id });
    }
    for (const e of prev) {
      if (after.has(key(e)) || mentioned.has(key(e))) continue;
      out.push({ changeId: s.id, at: s.at, subjectType: e.subject_type, subjectId: e.subject_id, text: `${name(e.subject_type, e.subject_id)} left`, layerId: null });
    }
    prev = s.entries;
  }
  return out;
}

/** CSS for a layer look: border, fill, dashed, label and tag colours (Android layerInk). */
export function ink(style: Style, color: string | null) {
  const tint = color ?? 'var(--accent)';
  const mix = (pct: number, base = tint) => `color-mix(in oklab, ${base} ${pct}%, transparent)`;
  switch (style) {
    case 'solid': return { border: tint, fill: color ? mix(16) : 'var(--accent-soft)', dashed: false, label: tint, tagFill: tint, tagInk: 'var(--on-accent)', tagBorder: 'transparent', ring: tint };
    case 'dashed': return { border: mix(55), fill: mix(5), dashed: true, label: tint, tagFill: 'transparent', tagInk: tint, tagBorder: mix(70), ring: mix(70) };
    case 'faint': return { border: mix(40, color ?? 'var(--ink-3)'), fill: 'transparent', dashed: true, label: color ?? 'var(--ink-3)', tagFill: 'transparent', tagInk: color ?? 'var(--ink-3)', tagBorder: color ?? 'var(--ink-3)', ring: mix(50, color ?? 'var(--ink-3)') };
    default: return { border: 'var(--line)', fill: 'transparent', dashed: false, label: color ?? 'var(--ink)', tagFill: 'var(--line)', tagInk: color ?? 'var(--ink)', tagBorder: 'transparent', ring: 'var(--line)' };
  }
}
/** Inline style for an element drawn as a layer (box, tag, swatch). */
export function inkVars(style: Style, color: string | null): string {
  const i = ink(style, color);
  return `--l-border: ${i.border}; --l-fill: ${i.fill}; --l-dash: ${i.dashed ? 'dashed' : 'solid'}; --l-label: ${i.label}; --l-tag-fill: ${i.tagFill}; --l-tag-ink: ${i.tagInk}; --l-tag-border: ${i.tagBorder}; --l-ring: ${i.ring}`;
}
