// Typed views over the core projection (the reference model's output, DATA_MODEL.md §4).
import type { Projection } from './sync/client';

export interface ProxyTag {
  prefix: string;
  suffix: string;
}

export interface MemberRow {
  id: string;
  name: string;
  display_name?: string;
  pronouns?: string;
  color: string;
  sigils: string[];
  proxy_tags: ProxyTag[];
  description?: string;
  birthday?: string;
  archived: boolean;
  deleted: boolean;
  created_at?: number;
}

export interface GroupRow {
  id: string;
  name: string;
  kind: 'subsystem' | 'group';
  parent_id?: string;
  color?: string;
  deleted: boolean;
}

export interface FieldDef {
  id: string;
  name: string;
  type: 'text' | 'long_text' | 'number' | 'date' | 'select' | 'boolean' | 'url' | 'color';
  options: { choices?: string[] };
  sort_key?: string;
  deleted: boolean;
}

type Rows = Record<string, { exists: boolean; fields: Record<string, unknown> }>;

const str = (v: unknown): string | undefined => (typeof v === 'string' && v !== '' ? v : undefined);

export function members(p: Projection): MemberRow[] {
  const rows = (p.rows.member ?? {}) as Rows;
  return Object.entries(rows)
    .filter(([, r]) => r.exists)
    .map(([id, r]) => {
      const f = r.fields;
      return {
        id,
        name: str(f.name) ?? 'Unnamed',
        display_name: str(f.display_name),
        pronouns: str(f.pronouns),
        color: str(f.color) ?? '#A09184',
        sigils: Array.isArray(f.sigils) ? (f.sigils as string[]) : [],
        proxy_tags: Array.isArray(f.proxy_tags) ? (f.proxy_tags as ProxyTag[]) : [],
        description: str(f.description),
        birthday: str(f.birthday),
        archived: f.archived_at != null,
        deleted: f.deleted_at != null,
        created_at: typeof f.created_at === 'number' ? f.created_at : undefined,
      };
    })
    .sort((a, b) => a.name.localeCompare(b.name));
}

export function groups(p: Projection): GroupRow[] {
  const rows = (p.rows.member_group ?? {}) as Rows;
  return Object.entries(rows)
    .filter(([, r]) => r.exists)
    .map(([id, r]) => ({
      id,
      name: str(r.fields.name) ?? 'Untitled',
      kind: r.fields.kind === 'group' ? 'group' : 'subsystem',
      parent_id: str(r.fields.parent_id),
      color: str(r.fields.color),
      deleted: r.fields.deleted_at != null,
    }))
    .filter((g) => !g.deleted)
    .sort((a, b) => a.name.localeCompare(b.name)) as GroupRow[];
}

/** group id → member ids (LWW element set keys are "<group>|{"member_id":"…"}"). */
export function membership(p: Projection): Map<string, Set<string>> {
  const out = new Map<string, Set<string>>();
  for (const [key, present] of Object.entries(p.sets.group_membership ?? {})) {
    if (!present) continue;
    const bar = key.indexOf('|');
    const group = key.slice(0, bar);
    const member = (JSON.parse(key.slice(bar + 1)) as { member_id: string }).member_id;
    if (!out.has(group)) out.set(group, new Set());
    out.get(group)!.add(member);
  }
  return out;
}

export function fieldDefs(p: Projection): FieldDef[] {
  const rows = (p.rows.field_def ?? {}) as Rows;
  return Object.entries(rows)
    .filter(([, r]) => r.exists && r.fields.deleted_at == null)
    .map(([id, r]) => ({
      id,
      name: str(r.fields.name) ?? 'Field',
      type: (str(r.fields.type) ?? 'text') as FieldDef['type'],
      options: (r.fields.options as FieldDef['options']) ?? {},
      sort_key: str(r.fields.sort_key),
      deleted: false,
    }))
    .sort((a, b) => (a.sort_key ?? a.name).localeCompare(b.sort_key ?? b.name));
}

/** member id → field id → value */
export function fieldValues(p: Projection): Map<string, Map<string, unknown>> {
  const out = new Map<string, Map<string, unknown>>();
  for (const [key, r] of Object.entries((p.rows.field_value ?? {}) as Rows)) {
    const [member, field] = key.split('|');
    if (!out.has(member)) out.set(member, new Map());
    out.get(member)!.set(field, r.fields.value);
  }
  return out;
}

/** Path of subsystem names from root, e.g. "Stars / Inner". */
export function groupPath(g: GroupRow, all: GroupRow[]): string {
  const byId = new Map(all.map((x) => [x.id, x]));
  const names: string[] = [];
  let cur: GroupRow | undefined = g;
  const seen = new Set<string>();
  while (cur && !seen.has(cur.id)) {
    seen.add(cur.id);
    names.unshift(cur.name);
    cur = cur.parent_id ? byId.get(cur.parent_id) : undefined;
  }
  return names.join(' / ');
}

/** Simple fuzzy match: every query char appears in order. Lower score = better. */
export function fuzzy(query: string, text: string): number | null {
  const q = query.toLowerCase().trim();
  if (!q) return 0;
  const t = text.toLowerCase();
  const direct = t.indexOf(q);
  if (direct >= 0) return direct;
  let ti = 0;
  let gaps = 0;
  for (const ch of q) {
    const found = t.indexOf(ch, ti);
    if (found < 0) return null;
    gaps += found - ti;
    ti = found + 1;
  }
  return 100 + gaps;
}

// ─── chat ────────────────────────────────────────────────────────────────────

export interface SpaceRow {
  id: string;
  kind: 'internal' | 'shared' | 'dm';
  name: string;
}

export interface ChannelRow {
  id: string;
  space_id: string;
  kind: 'text' | 'thread' | 'member_dm';
  name: string;
  topic?: string;
  category?: string;
  archived: boolean;
}

export interface Segment {
  offset: number;
  length: number;
  authors: string[];
}

export interface MessageRow {
  id: string;
  channel_id: string;
  authors: string[];
  text: string;
  entities: import('./core').Entity[];
  segments: Segment[];
  occurred_at: number;
  account_id?: string;
  sent_offline: boolean;
  edited: boolean;
  deleted: boolean;
  pinned: boolean;
  cw?: string;
}

export function spaces(p: Projection): SpaceRow[] {
  return Object.entries((p.rows.space ?? {}) as Rows)
    .filter(([, r]) => r.exists && r.fields.deleted_at == null)
    .map(([id, r]) => ({
      id,
      kind: (str(r.fields.kind) ?? 'shared') as SpaceRow['kind'],
      name: str(r.fields.name) ?? 'Space',
    }))
    .sort((a, b) => (a.kind === 'internal' ? -1 : b.kind === 'internal' ? 1 : a.name.localeCompare(b.name)));
}

export function channels(p: Projection, spaceId?: string): ChannelRow[] {
  return Object.entries((p.rows.channel ?? {}) as Rows)
    .filter(([, r]) => r.exists && r.fields.deleted_at == null)
    .map(([id, r]) => ({
      id,
      space_id: str(r.fields.space_id) ?? '',
      kind: (str(r.fields.kind) ?? 'text') as ChannelRow['kind'],
      name: str(r.fields.name) ?? 'channel',
      topic: str(r.fields.topic),
      category: str(r.fields.category),
      archived: r.fields.archived_at != null,
    }))
    .filter((c) => !spaceId || c.space_id === spaceId)
    .sort((a, b) => a.name.localeCompare(b.name));
}

export function messages(p: Projection, channelId: string): MessageRow[] {
  const rows = (p.rows.message ?? {}) as Record<string, { exists: boolean; fields: Record<string, unknown>; edits?: number }>;
  return Object.entries(rows)
    .filter(([, r]) => r.exists && r.fields.channel_id === channelId)
    .map(([id, r]) => {
      const f = r.fields;
      const authors = Array.isArray(f.authors) ? (f.authors as string[]) : [];
      const text = typeof f.text === 'string' ? f.text : '';
      const segs = Array.isArray(f.segments) && f.segments.length ? (f.segments as Segment[]) : [{ offset: 0, length: text.length, authors }];
      return {
        id,
        channel_id: channelId,
        authors,
        text,
        entities: Array.isArray(f.entities) ? (f.entities as MessageRow['entities']) : [],
        segments: segs,
        occurred_at: typeof f.occurred_at === 'number' ? f.occurred_at : 0,
        account_id: str(f.account_id),
        sent_offline: f.sent_offline === true,
        edited: (r.edits ?? 0) > 0,
        deleted: f.deleted_at != null,
        pinned: f.pinned_at != null,
        cw: str(f.cw),
      };
    })
    .sort((a, b) => a.occurred_at - b.occurred_at || a.id.localeCompare(b.id));
}
