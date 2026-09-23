import { core, type Entity, type Rich, type Segment } from './core';
import type { MessageRow } from './data';

/** Segment offsets and entity ranges are UTF-16, as are JavaScript string indices. */
export function segmentRich(message: MessageRow, segment: Segment): Rich {
  return {
    text: message.text.slice(segment.offset, segment.offset + segment.length),
    entities: message.entities
      .filter((e) => e.offset >= segment.offset && e.offset + e.length <= segment.offset + segment.length)
      .map((e) => ({ ...e, offset: e.offset - segment.offset })),
  };
}

export function segmentMarkup(message: MessageRow): string[] {
  return message.segments.map((segment) => core.toMarkup(segmentRich(message, segment)));
}

/** Rebuild one edited message without changing which members authored each segment. */
export function editedSegments(markup: string[], original: Segment[], names: unknown): { rich: Rich; segments: Segment[] } {
  const entities: Entity[] = [];
  const segments: Segment[] = [];
  let text = '';
  for (const [index, source] of markup.entries()) {
    if (index > 0) text += '\n';
    const offset = text.length;
    const part = core.parseMarkup(source, names);
    text += part.text;
    entities.push(...part.entities.map((entity) => ({ ...entity, offset: entity.offset + offset })));
    segments.push({ offset, length: part.text.length, authors: [...original[index].authors] });
  }
  return { rich: { text, entities }, segments };
}
