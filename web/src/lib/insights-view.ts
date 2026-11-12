// F9.1 (Android's data/InsightsData.kt): Week, Month, Year; the stat tiles, Patterns and the columns.
import { localDay, windowStart, type DailyTime } from './insights';
import type { Interval } from './sync/client';

export type InsightRange = 'week' | 'month' | 'year';
export const RANGES: { id: InsightRange; label: string; days: number; noun: string }[] = [
  { id: 'week', label: 'Week', days: 7, noun: 'week' }, { id: 'month', label: 'Month', days: 30, noun: 'month' }, { id: 'year', label: 'Year', days: 365, noun: 'year' },
];

export interface InsightColumn { key: string; label: string; parts: { id: string; seconds: number }[]; total: number }

const addDays = (day: string, n: number) => { const d = new Date(`${day}T00:00:00Z`); d.setUTCDate(d.getUTCDate() + n); return d.toISOString().slice(0, 10); };
const mondayOf = (day: string) => addDays(day, -((new Date(`${day}T00:00:00Z`).getUTCDay() + 6) % 7));

/** Members by their time in the range, most first. */
export function memberOrder(daily: DailyTime[]): string[] {
  const total = new Map<string, number>();
  for (const r of daily) total.set(r.subject_id, (total.get(r.subject_id) ?? 0) + r.seconds);
  return [...total].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0])).map(([id]) => id);
}

/** The chart columns: a week by day, a month by week (from Monday), a year by month; members stack largest first. */
export function insightColumns(daily: DailyTime[], range: InsightRange, from: number, now: number, timeZone?: string): InsightColumn[] {
  const first = localDay(from, timeZone), last = localDay(now, timeZone);
  const key = (day: string) => range === 'week' ? day : range === 'month' ? mondayOf(day) : `${day.slice(0, 7)}-01`;
  const keys: string[] = [];
  for (let day = first; day <= last; day = addDays(day, 1)) { const k = key(day); if (keys[keys.length - 1] !== k) keys.push(k); }
  const order = memberOrder(daily);
  const sums = new Map<string, Map<string, number>>();
  for (const r of daily) {
    const k = key(r.day);
    const m = sums.get(k) ?? new Map<string, number>();
    m.set(r.subject_id, (m.get(r.subject_id) ?? 0) + r.seconds);
    sums.set(k, m);
  }
  return keys.map((k) => {
    const d = new Date(`${k}T00:00:00Z`);
    const label = range === 'week' ? d.toLocaleDateString(undefined, { weekday: 'narrow', timeZone: 'UTC' })
      : range === 'month' ? d.toLocaleDateString(undefined, { day: 'numeric', month: 'short', timeZone: 'UTC' })
        : d.toLocaleDateString(undefined, { month: 'narrow', timeZone: 'UTC' });
    const parts = order.flatMap((id) => { const s = sums.get(k)?.get(id) ?? 0; return s > 0 ? [{ id, seconds: s }] : []; });
    return { key: k, label, parts, total: parts.reduce((n, p) => n + p.seconds, 0) };
  });
}

export interface InsightStats { switches: number; previous: number; together: number; busiestHour: number | null; longest: { id: string; start: number; seconds: number } | null }

/** The tiles and patterns: changes in the range and just before it, the busiest local hour, the
 * longest unbroken stretch of one member, and the time two or more were here at once. */
export function insightStats(changes: readonly number[], intervals: readonly Interval[], from: number, now: number, days: number, layer: string, timeZone?: string): InsightStats {
  const inRange = changes.filter((t) => t >= from && t <= now);
  const previousFrom = windowStart(from - 1, days, timeZone);
  const previous = changes.filter((t) => t >= previousFrom && t < from).length;
  const hourOf = (t: number) => Number(new Intl.DateTimeFormat('en-US', { hour: 'numeric', hourCycle: 'h23', ...(timeZone ? { timeZone } : {}) }).format(new Date(t)));
  const perHour = new Map<number, number>();
  for (const t of inRange) perHour.set(hourOf(t), (perHour.get(hourOf(t)) ?? 0) + 1);
  const busiestHour = [...perHour].sort((a, b) => b[1] - a[1] || a[0] - b[0])[0]?.[0] ?? null;
  // each member's time as unbroken stretches, so a switch that keeps someone does not split them
  const byMember = new Map<string, [number, number][]>();
  for (const i of [...intervals].filter((x) => x.subject_type === 'member' && x.level === layer).sort((a, b) => a.start_at - b.start_at)) {
    const a = Math.max(i.start_at, from), b = Math.min(i.end_at ?? now, now);
    if (b <= a) continue;
    const spans = byMember.get(i.subject_id) ?? [];
    const last = spans[spans.length - 1];
    if (last && a <= last[1]) last[1] = Math.max(last[1], b); else spans.push([a, b]);
    byMember.set(i.subject_id, spans);
  }
  const stretches = [...byMember].flatMap(([id, spans]) => spans.map(([a, b]) => ({ id, start: a, end: b, seconds: Math.floor((b - a) / 1000) })));
  const longest = [...stretches].sort((a, b) => b.seconds - a.seconds || a.start - b.start || a.id.localeCompare(b.id))[0] ?? null;
  const edges = stretches.flatMap((s) => [[s.start, 1], [s.end, -1]] as [number, number][]).sort((a, b) => a[0] - b[0] || a[1] - b[1]);
  let here = 0, at = from, together = 0;
  for (const [t, step] of edges) { if (here >= 2) together += t - at; here += step; at = t; }
  return { switches: inRange.length, previous, together: Math.floor(together / 1000), busiestHour, longest: longest && { id: longest.id, start: longest.start, seconds: longest.seconds } };
}

/** "3 more than last week". */
export function switchDelta(stats: InsightStats, noun: string): string {
  const d = stats.switches - stats.previous;
  return d > 0 ? `${d} more than last ${noun}` : d < 0 ? `${-d} fewer than last ${noun}` : `Same as last ${noun}`;
}

/** "11 h", "6 h 20 m", "40 m". */
export function duration(seconds: number, short = false): string {
  const h = Math.floor(seconds / 3600), m = Math.floor((seconds % 3600) / 60);
  return h === 0 ? `${m} m` : short || m === 0 ? `${h} h` : `${h} h ${m} m`;
}

/** The share of the span this member was here, in whole percent. */
export function share(daily: DailyTime[], id: string, from: number, now: number): number {
  const span = (now - from) / 1000;
  if (span <= 0) return 0;
  return Math.min(100, Math.round(daily.filter((r) => r.subject_id === id).reduce((n, r) => n + r.seconds, 0) * 100 / span));
}
