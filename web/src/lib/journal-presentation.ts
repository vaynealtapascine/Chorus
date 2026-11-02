import type { AttachmentRow } from './data';

const olderDate = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' });
const fullDateTime = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' });

/** `"now"`, `"25m"`, `"3h"`, `"2d"`, then medium date after 7 days (parity with Android `JournalPresentation.shortAgo`). */
export function shortAgo(
  ms: number,
  now = Date.now(),
  older: (at: number) => string = (at) => olderDate.format(at),
): string {
  const s = Math.max(0, Math.floor((now - ms) / 1000));
  if (s < 60) return 'now';
  if (s < 3600) return `${Math.floor(s / 60)}m`;
  if (s < 86_400) return `${Math.floor(s / 3600)}h`;
  if (s < 7 * 86_400) return `${Math.floor(s / 86_400)}d`;
  return older(ms);
}

/** Accessible full date and time for tooltips, `aria-label`, and detail lines. */
export function fullTimestamp(ms: number): string {
  return fullDateTime.format(ms);
}

/** `"1 min read"`, `"2 min read"` at 200 wpm, minimum 1 min (parity with Android `JournalPresentation.readTime`). */
export function readTime(text: string): string {
  const trimmed = text.trim();
  const words = trimmed ? trimmed.split(/\s+/).filter(Boolean).length : 0;
  return `${Math.max(1, Math.floor((words + 199) / 200))} min read`;
}

/** `"Journal entry · 2 min read · #tags"`: quiet metadata line under a long-form entry. */
export function entryMeta(text: string, tags: readonly string[]): string {
  const parts = ['Journal entry', readTime(text)];
  const cleaned = tags.map((t) => t.trim()).filter(Boolean);
  if (cleaned.length > 0) parts.push(cleaned.map((t) => `#${t}`).join(' '));
  return parts.join(' · ');
}

export interface PostMediaPlan {
  images: AttachmentRow[];
  shownImages: AttachmentRow[];
  moreCount: number;
  files: AttachmentRow[];
}

/** Split a post's attachments into its 1..4+ image grid and trailing file rows (parity with `JournalMedia.kt`). */
export function postMediaPlan(attachments: readonly AttachmentRow[] | undefined): PostMediaPlan {
  const list = attachments ?? [];
  const images = list.filter((a) => a.mime.startsWith('image/'));
  const shownImages = images.slice(0, 4);
  const moreCount = Math.max(0, images.length - shownImages.length);
  const files = list.filter((a) => !a.mime.startsWith('image/'));
  return { images, shownImages, moreCount, files };
}

/** Single-image alt caption below the 16:9 grid, hidden while a spoiler image remains covered. */
export function singleImageAlt(images: readonly AttachmentRow[], revealedIds: ReadonlySet<string>): string | null {
  if (images.length !== 1) return null;
  const img = images[0];
  if (img.is_spoiler && !revealedIds.has(img.id)) return null;
  const alt = img.alt_text.trim();
  return alt.length > 0 ? alt : null;
}

/** Human-readable attachment byte size (`"840 B"`, `"14 KB"`, `"2.4 MB"`). */
export function formatAttachmentSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return '';
  if (bytes < 1024) return `${Math.round(bytes)} B`;
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}
