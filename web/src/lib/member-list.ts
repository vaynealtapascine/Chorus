// The Members list's own settings (D-100): role pills, a line of bio, banner backgrounds, folders
// and a flat view. All account prefs, so they reach the system's devices with no server change;
// mirrored by Android's MemberListPrefs.kt. Display only: member order stays member-order.ts's.
import type { FieldDef, MemberRow } from './data';
import { orderedMembers } from './member-order';

export const VIEW = 'members.view';
export const FOLDERS = 'member_folders';
export const FOLDER_OF = 'member_folder:';

export type PillMode = 'off' | 'first' | 'all';
export interface MembersView {
  /** Role pills on each row: none, the first role only, or every role. */
  pills: PillMode;
  /** The custom field roles come from; absent picks one named "Role(s)" (see roleField). */
  role_field?: string;
  bio: boolean;
  banners: boolean;
  /** Every active member in one list, folders ignored. */
  flat: boolean;
}
export interface MemberFolder { id: string; name: string }

export const DEFAULT_VIEW: MembersView = { pills: 'all', bio: true, banners: true, flat: false };

type PrefRows = Record<string, { fields: Record<string, unknown> } | undefined>;

/** Account-wide Members prefs; a pending local change (empty account id) wins over the stamped row. */
export function memberListPrefs(prefs: PrefRows, accountId: string): { view: MembersView; folders: MemberFolder[]; folderOf: Map<string, string> } {
  const value = (key: string) => (prefs[`||${key}`] ?? prefs[`${accountId}||${key}`])?.fields.value;
  const v = value(VIEW) as Record<string, unknown> | null | undefined;
  const view: MembersView = { ...DEFAULT_VIEW };
  if (v && typeof v === 'object') {
    if (v.pills === 'off' || v.pills === 'first' || v.pills === 'all') view.pills = v.pills;
    if (typeof v.role_field === 'string' && v.role_field) view.role_field = v.role_field;
    if (typeof v.bio === 'boolean') view.bio = v.bio;
    if (typeof v.banners === 'boolean') view.banners = v.banners;
    if (typeof v.flat === 'boolean') view.flat = v.flat;
  }
  const raw = value(FOLDERS);
  const seen = new Set<string>();
  const folders: MemberFolder[] = [];
  for (const f of Array.isArray(raw) ? raw : []) {
    if (!f || typeof f !== 'object' || typeof f.id !== 'string' || !f.id || seen.has(f.id)) continue;
    seen.add(f.id);
    folders.push({ id: f.id, name: typeof f.name === 'string' && f.name.trim() ? f.name : 'Folder' });
  }
  const stamped = new Map<string, unknown>();
  const pending = new Map<string, unknown>();
  for (const [k, row] of Object.entries(prefs)) {
    const [account, device, key] = k.split('|');
    if (device || (account && account !== accountId) || !key?.startsWith(FOLDER_OF) || !row) continue;
    (account ? stamped : pending).set(key.slice(FOLDER_OF.length), row.fields.value);
  }
  const folderOf = new Map<string, string>();
  // a member filed in a folder that was since deleted is simply not in a folder
  for (const [id, f] of new Map([...stamped, ...pending])) if (typeof f === 'string' && seen.has(f)) folderOf.set(id, f);
  return { view, folders, folderOf };
}

const ROLE_TYPES = new Set<FieldDef['type']>(['multi_select', 'select', 'text']);

/** Fields that can feed role pills. */
export function roleFields(defs: FieldDef[]): FieldDef[] {
  return defs.filter((d) => ROLE_TYPES.has(d.type));
}

/** The chosen roles field, else one named "Role" or "Roles", else the first several-choice field. */
export function roleField(view: MembersView, defs: FieldDef[]): FieldDef | undefined {
  const usable = roleFields(defs);
  return (view.role_field ? usable.find((d) => d.id === view.role_field) : undefined)
    ?? usable.find((d) => /^roles?$/i.test(d.name.trim()))
    ?? usable.find((d) => d.type === 'multi_select');
}

/** A member's roles from the field's value: a choice list, one choice, or comma-separated text. */
export function rolesOf(value: unknown): string[] {
  const parts = Array.isArray(value) ? value : typeof value === 'string' ? value.split(/[,;]/) : [];
  const out: string[] = [];
  for (const p of parts) {
    if (typeof p !== 'string') continue;
    const t = p.trim();
    if (t && !out.includes(t)) out.push(t);
  }
  return out;
}

export function pillsFor(roles: string[], mode: PillMode): string[] {
  return mode === 'off' ? [] : mode === 'first' ? roles.slice(0, 1) : roles;
}

/** The first line of a bio with its spacing folded, or nothing. */
export function bioLine(description: string | undefined): string | undefined {
  const line = description?.split('\n').map((l) => l.replace(/\s+/g, ' ').trim()).find(Boolean);
  return line || undefined;
}

/** A steady pill colour for a role name, from the member palette. */
const PILLS = ['#B99CE8', '#F08C7F', '#EEAA62', '#DCA3CC', '#8FB8E8', '#86BFA5', '#C9B37A'];
export function roleColor(role: string): string {
  let h = 0;
  const key = role.toLowerCase();
  for (let i = 0; i < key.length; i++) h = (h * 31 + key.charCodeAt(i)) >>> 0;
  return PILLS[h % PILLS.length];
}

export interface MemberSection<T> { folder: MemberFolder | null; members: T[] }

/** Folders in their arranged order (empty ones too, so there is somewhere to file people), then
 *  everyone not in a folder. Each section keeps the members' own order. */
export function memberSections<T extends MemberRow>(members: T[], folders: MemberFolder[], folderOf: Map<string, string>): MemberSection<T>[] {
  const by = new Map<string | null, T[]>([...folders.map((f) => [f.id, []] as [string, T[]]), [null, []]]);
  for (const m of orderedMembers(members)) by.get(folderOf.get(m.id) ?? null)!.push(m);
  return [...folders.map((f) => ({ folder: f, members: by.get(f.id)! })), { folder: null, members: by.get(null)! }];
}

export function addFolder(folders: MemberFolder[], id: string, name: string): MemberFolder[] {
  return [...folders, { id, name: name.trim() || 'Folder' }];
}

export function renameFolder(folders: MemberFolder[], id: string, name: string): MemberFolder[] {
  return folders.map((f) => (f.id === id ? { ...f, name: name.trim() || f.name } : f));
}

export function removeFolder(folders: MemberFolder[], id: string): MemberFolder[] {
  return folders.filter((f) => f.id !== id);
}

/** Moves a folder `by` places (−1 earlier, +1 later), staying in bounds. */
export function moveFolder(folders: MemberFolder[], id: string, by: number): MemberFolder[] {
  const i = folders.findIndex((f) => f.id === id);
  const j = Math.max(0, Math.min(folders.length - 1, i + by));
  if (i < 0 || i === j) return folders;
  const out = [...folders];
  out.splice(j, 0, ...out.splice(i, 1));
  return out;
}
