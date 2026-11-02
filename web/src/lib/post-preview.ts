import type { Entity } from './core';
/** Count Unicode scalar values; entity ranges remain UTF-16. Never cut a surrogate pair. */
export function postPreview(text: string, entities: Entity[], limit = 300) {
  let preview = '';
  let count = 0;
  let truncated = false;
  for (const char of text) {
    if (count >= limit) { truncated = true; break; }
    preview += char;
    count++;
  }
  const end = preview.length;
  return { text: preview, truncated, entities: entities.filter((e) => e.offset < end && e.offset >= 0 && e.length > 0)
    .filter((e) => !['mention', 'custom_emoji'].includes(e.type) || e.offset + e.length <= end)
    .map((e) => ({ ...e, length: Math.min(e.length, end - e.offset) })) };
}
