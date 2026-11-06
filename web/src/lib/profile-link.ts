/**
 * Where a member's face or name leads (owner, 2026-11-06): one profile page everywhere, the
 * Journal's. Our own members open theirs; another account's member opens the friend profile,
 * whose server read decides what is shared (D-101), so a link reveals nothing by itself.
 */
export function profileHref(member: { id: string; account_id?: string | null } | undefined, me: string): string | null {
  if (!member?.id) return null;
  if (member.account_id && member.account_id !== me) {
    return `#/people/${encodeURIComponent(member.account_id)}/${encodeURIComponent(member.id)}`;
  }
  return `#/profile/${encodeURIComponent(member.id)}`;
}
