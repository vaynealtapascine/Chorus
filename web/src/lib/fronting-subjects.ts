// Who can be in a front, by `type:id`, for the Fronting screens (members, subsystems, custom states).
import { groups, members } from './data';
import { sync, type Projection } from './sync/client';

export interface Who { type: 'member' | 'group' | 'state'; id: string; name: string; color: string; glyph: string; blob?: string; pronouns?: string }

export const whoKey = (type: string, id: string) => `${type}:${id}`;

export function whoMap(p: Projection): Map<string, Who> {
  const out = new Map<string, Who>();
  for (const m of members(p)) {
    if (m.deleted) continue;
    out.set(whoKey('member', m.id), { type: 'member', id: m.id, name: m.display_name ?? m.name, color: m.color, glyph: m.sigils[0] ?? m.name[0] ?? '?', blob: m.avatar_blob, pronouns: m.pronouns });
  }
  for (const g of groups(p)) out.set(whoKey('group', g.id), { type: 'group', id: g.id, name: g.name, color: g.color ?? '#A09184', glyph: '◌', blob: g.avatar_blob });
  for (const [id, row] of Object.entries(p.rows.custom_state ?? {})) {
    if (!row.exists || row.fields.deleted_at) continue;
    out.set(whoKey('state', id), { type: 'state', id, name: String(row.fields.name ?? 'State'), color: String(row.fields.color ?? '#A39E92'), glyph: String(row.fields.icon ?? '?') });
  }
  return out;
}

/** Own active members (not archived, not another account's), by name. */
export function ownMembers(p: Projection): Who[] {
  return members(p)
    .filter((m) => !m.deleted && !m.archived && !m.is_self && (!p.rows.member?.[m.id]?.fields.created_by_account_id || p.rows.member?.[m.id]?.fields.created_by_account_id === sync.accountId))
    .map((m) => ({ type: 'member' as const, id: m.id, name: m.display_name ?? m.name, color: m.color, glyph: m.sigils[0] ?? m.name[0] ?? '?', blob: m.avatar_blob, pronouns: m.pronouns }));
}
