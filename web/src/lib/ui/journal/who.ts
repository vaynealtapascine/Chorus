// A member as a face (Face.svelte draws a `Who`).
import type { MemberRow } from '../../data';
import type { Who } from '../../fronting-subjects';

export const whoOf = (m: MemberRow | undefined): Who | undefined => m && ({
  type: 'member', id: m.id, name: m.display_name ?? m.name, color: m.color, glyph: m.sigils[0] ?? m.name[0] ?? '?', blob: m.avatar_blob,
});
