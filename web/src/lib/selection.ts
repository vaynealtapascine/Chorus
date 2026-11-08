import type { MessageRow, QuoteValue, SnapshotItem, TextRange } from './data';

/** Capture the exact visible text at selection time, including clipped rich-text entities. */
export function snapshot(message: MessageRow, channelName: string, range?: TextRange): SnapshotItem {
  const start = range?.offset ?? 0;
  const end = range ? start + range.length : message.text.length;
  const entities = message.entities.flatMap((entity) => {
    const from = Math.max(start, entity.offset);
    const to = Math.min(end, entity.offset + entity.length);
    return to > from ? [{ ...entity, offset: from - start, length: to - from }] : [];
  });
  return {
    message_id: message.id,
    channel_name: channelName,
    authors: [...message.authors],
    text: message.text.slice(start, end),
    entities,
    occurred_at: message.occurred_at,
    ...(range ? {} : { attachments: message.attachments }),
    ...(range ? { offset: start, length: range.length } : { offset: 0, length: message.text.length }),
  };
}

/** Snapshots as they go to [targetSpaceId]: in another space the source chat's name stays hidden (F5.20). */
export function forTarget(items: SnapshotItem[], sourceSpaceId: string, targetSpaceId: string): SnapshotItem[] {
  if (sourceSpaceId === targetSpaceId) return items;
  return items.map((item) => {
    const copy = { ...item };
    delete copy.channel_name;
    return copy;
  });
}

/** A quote as it goes to [targetSpaceId]; a single text range carries no chat name. */
export function quoteForTarget(quote: QuoteValue, sourceSpaceId: string, targetSpaceId: string): QuoteValue {
  return 'items' in quote ? { items: forTarget(quote.items, sourceSpaceId, targetSpaceId) } : quote;
}
