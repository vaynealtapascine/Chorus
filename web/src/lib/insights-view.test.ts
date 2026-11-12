import { expect, it } from 'vitest';
import { windowStart, type DailyTime } from './insights';
import { duration, insightColumns, insightStats, share, switchDelta } from './insights-view';
import type { Interval } from './sync/client';

const H = 3_600_000;
const iv = (id: string, start: number, end: number | null): Interval => ({ subject_type: 'member', subject_id: id, level: 'front', is_primary: true, start_at: start, end_at: end });

it('counts changes against the span before, finds the busiest hour, the longest stretch and the time together', () => {
  const now = Date.parse('2026-10-26T12:00:00Z');
  const from = windowStart(now, 7, 'UTC');
  const changes = [now - H, now - 25 * H, now - 25 * H + 60_000, now - 9 * 86_400_000];
  const s = insightStats(changes, [iv('kai', now - 6 * H, now - 2 * H), iv('kai', now - 3 * H, now - H), iv('moon', now - 2 * H, now - H)], from, now, 7, 'front', 'UTC');
  expect(s.switches).toBe(4 - 1); // the one nine days back is before the range
  expect(s.previous).toBe(1);
  expect(switchDelta(s, 'week')).toBe('2 more than last week');
  expect(s.busiestHour).toBe(11); // 11:00 once, 11:00 and 11:01 the day before: hour 11 has three
  expect(s.longest).toEqual({ id: 'kai', start: now - 6 * H, seconds: 5 * 3600 }); // the two rows join
  expect(s.together).toBe(3600); // moon and kai overlap for an hour
});

it('writes durations, shares, and stacks columns by day and month', () => {
  expect([duration(11 * 3600, true), duration(6 * 3600 + 1200), duration(2400)]).toEqual(['11 h', '6 h 20 m', '40 m']);
  const daily = [['2026-10-25', 'kai', 3600], ['2026-10-26', 'kai', 1800], ['2026-10-26', 'moon', 7200]].map(([day, id, seconds]) =>
    ({ day, subject_type: 'member', subject_id: id, level: 'front', seconds, as_primary_seconds: 0 }) as DailyTime);
  const now = Date.parse('2026-10-26T12:00:00Z');
  const days = insightColumns(daily, 'week', windowStart(now, 7, 'UTC'), now, 'UTC');
  expect(days).toHaveLength(7);
  expect(days[6].parts).toEqual([{ id: 'moon', seconds: 7200 }, { id: 'kai', seconds: 1800 }]);
  expect(days[5].total).toBe(3600);
  const months = insightColumns(daily, 'year', windowStart(now, 365, 'UTC'), now, 'UTC');
  expect(months.at(-1)?.total).toBe(12600);
  expect(share(daily, 'moon', now - 86_400_000, now)).toBe(8);
});
