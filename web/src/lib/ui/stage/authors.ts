import { core } from '../../core';
import type { MemberRow } from '../../data';

/** Someone who appears on a stage, as this account knows them. */
export interface StageAuthor { id: string; name: string; glyph: string; color: string; blob?: string }

export function stageAuthor(id: string, member: MemberRow | undefined, fallbackName = 'Someone'): StageAuthor {
  const name = member?.display_name ?? member?.name ?? fallbackName;
  return { id, name, glyph: member?.sigils[0] ?? name[0] ?? '?', color: core.adaptColor(member?.color ?? '#A09184', false).ring, blob: member?.avatar_blob };
}
