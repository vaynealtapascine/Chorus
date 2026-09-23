// Words for a fuzzed time a follower sees (NOTIFICATIONS.md §2.9): never more precise than the
// server allowed. `precision` comes from core's `Displayed`, or from the follow's time rule.
export type Precision = 'exact' | 'approx' | 'part_of_day' | 'none';
export type Part = 'night' | 'morning' | 'afternoon' | 'evening';

export function precisionOfRule(rule: { mode?: string } | null | undefined): Precision {
  switch (rule?.mode) {
    case 'exact':
      return 'exact';
    case 'round':
    case 'jitter':
      return 'approx';
    case 'part_of_day':
      return 'part_of_day';
    default:
      return 'none';
  }
}

function partOf(d: Date): Part {
  const h = d.getHours();
  if (h < 5 || h >= 22) return 'night';
  if (h < 12) return 'morning';
  if (h < 17) return 'afternoon';
  return 'evening';
}

function dayWord(d: Date, now: Date): string | null {
  const start = (x: Date) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime();
  const days = Math.round((start(now) - start(d)) / 86_400_000);
  if (days === 0) return null;
  if (days === 1) return 'yesterday';
  if (days < 7) return d.toLocaleDateString(undefined, { weekday: 'long' });
  return d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
}

/** "at 14:37", "around 14:30", "this evening", "yesterday morning", or "" when hidden. */
export function fuzzyWhen(at: number | null | undefined, precision: Precision, part?: Part | null, now = new Date()): string {
  if (at == null || precision === 'none') return '';
  const d = new Date(at);
  const day = dayWord(d, now);
  if (precision === 'part_of_day') {
    const p = part ?? partOf(d);
    if (!day) return p === 'night' ? 'tonight' : `this ${p}`;
    return day === 'yesterday' && p === 'night' ? 'last night' : `${day} ${p}`;
  }
  const time = d.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' });
  const prefix = precision === 'approx' ? 'around' : 'at';
  return day ? `${day} ${prefix} ${time}` : `${prefix} ${time}`;
}
