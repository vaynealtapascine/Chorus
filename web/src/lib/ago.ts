// "2 days ago", the words Trash, tokens and the health page use (Android Trash.ago).
const plural = (n: number, unit: string) => (n === 1 ? `1 ${unit} ago` : `${n} ${unit}s ago`);

export function ago(at: number, now: number): string {
  const s = Math.max(0, Math.floor((now - at) / 1000));
  const d = Math.floor(s / 86_400);
  if (s < 60) return 'just now';
  if (s < 3600) return plural(Math.floor(s / 60), 'minute');
  if (s < 86_400) return plural(Math.floor(s / 3600), 'hour');
  if (d < 14) return plural(d, 'day');
  if (d < 60) return plural(Math.floor(d / 7), 'week');
  if (d < 365) return plural(Math.floor(d / 30), 'month');
  return plural(Math.floor(d / 365), 'year');
}
