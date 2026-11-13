// Pure logic for the Journal's right rail (F6.13): the "Trending in our system" card.

export interface TagTrend { tag: string; count: number; newestBy: string | null }
export interface RailPost { tags: string[]; occurred_at: number; deleted?: boolean; authors: string[] }

const WEEK = 7 * 24 * 60 * 60 * 1000;
const keyOf = (tag: string) => tag.trim().replace(/^#/, '').toLowerCase();

interface Acc { tag: string; firstAt: number; count: number; newestAt: number; newestBy: string | null }

function tally(rows: RailPost[]): Acc[] {
  const by = new Map<string, Acc>();
  for (const p of rows) {
    // one count per post, even if it repeats a tag
    for (const raw of new Set(p.tags.map(keyOf))) {
      if (!raw) continue;
      const written = p.tags.find((t) => keyOf(t) === raw)!.trim().replace(/^#/, '');
      const acc = by.get(raw) ?? { tag: written, firstAt: p.occurred_at, count: 0, newestAt: -Infinity, newestBy: null };
      acc.count += 1;
      if (p.occurred_at < acc.firstAt) { acc.firstAt = p.occurred_at; acc.tag = written; }
      if (p.occurred_at > acc.newestAt) { acc.newestAt = p.occurred_at; acc.newestBy = p.authors[0] ?? null; }
      by.set(raw, acc);
    }
  }
  return [...by.values()].sort((a, b) => b.count - a.count || b.newestAt - a.newestAt || a.tag.localeCompare(b.tag));
}

/** Tags of our own undeleted posts in the last 7 days, most used first (ties: newest use first), at most `limit`;
 *  when fewer than `limit` were used this week, fill with the most used tags of all time. */
export function trendingTags(posts: RailPost[], now: number, limit = 3): (TagTrend & { thisWeek: boolean })[] {
  const live = posts.filter((p) => !p.deleted);
  const week = tally(live.filter((p) => p.occurred_at > now - WEEK && p.occurred_at <= now));
  const out = week.slice(0, limit).map((a) => ({ tag: a.tag, count: a.count, newestBy: a.newestBy, thisWeek: true }));
  if (out.length < limit) {
    const seen = new Set(out.map((t) => keyOf(t.tag)));
    for (const a of tally(live)) {
      if (out.length >= limit) break;
      if (!seen.has(keyOf(a.tag))) out.push({ tag: a.tag, count: a.count, newestBy: a.newestBy, thisWeek: false });
    }
  }
  return out;
}

/** The line under a tag: "12 posts this week", "4 posts · newest from Foxglove". `newestName` is the resolved author name. */
export function tagLine(t: TagTrend & { thisWeek: boolean }, newestName: string | null): string {
  const posts = `${t.count} ${t.count === 1 ? 'post' : 'posts'}`;
  if (t.count <= 4 && newestName) return `${posts} · newest from ${newestName}`;
  return t.thisWeek ? `${posts} this week` : posts;
}
