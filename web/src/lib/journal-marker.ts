// The quiet marker rows of the timeline (Android Journal.kt): "Violet fronting · 3h", "Pinned in #chat: …".
import { shortAgo } from './journal-presentation';

export interface Marker { icon: 'swap' | 'pin'; text: string; ago: string }

export function switchMarker(o: { retracted: boolean; names: string[]; at: number }, now = Date.now()): Marker {
  const text = o.retracted ? 'Undone switch' : `${o.names.join(' & ') || 'No one'} fronting`;
  return { icon: 'swap', text, ago: shortAgo(o.at, now) };
}

export function pinMarker(o: { channel: string | null; text: string; at: number }, now = Date.now()): Marker {
  return { icon: 'pin', text: `Pinned in #${o.channel ?? 'channel'}: ${o.text.replace(/\s+/g, ' ').trim()}`, ago: shortAgo(o.at, now) };
}
