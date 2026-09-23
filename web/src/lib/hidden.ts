import type { MessageRow } from './data';

/** Member visibility is a soft local view rule, not an account security boundary. */
export function memberVisible(
  message: Pick<MessageRow, 'visibility'>,
  spaceKind: string | undefined,
  active: Iterable<string>,
  viewingAs: string | null,
): boolean {
  if (message.visibility?.mode !== 'members' || spaceKind !== 'internal') return true;
  const allowed = message.visibility.member_ids ?? [];
  return (viewingAs !== null && allowed.includes(viewingAs)) || [...active].some((id) => allowed.includes(id));
}

export function activeViewers(front: { subject_type: string; subject_id: string; level: string }[]): string[] {
  return front.filter((entry) => entry.subject_type === 'member' && (entry.level === 'front' || entry.level === 'cocon'))
    .map((entry) => entry.subject_id);
}
