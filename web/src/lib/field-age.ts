// "Show age, not the date" (F4.6): a date field with `options.show_age` reads as an age. Display only;
// the date itself is still the stored value and still what the audience rules protect.

/** "27" from "1999-04-02" on [now]; nothing for a date without a year (--MM-DD), a future date, or junk. */
export function ageOf(value: unknown, now: Date): string | null {
  if (typeof value !== 'string') return null;
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (!m) return null;
  const [y, mo, d] = [Number(m[1]), Number(m[2]), Number(m[3])];
  let age = now.getFullYear() - y;
  if (now.getMonth() + 1 < mo || (now.getMonth() + 1 === mo && now.getDate() < d)) age--;
  return age >= 0 && age < 200 ? String(age) : null;
}

/** The sort keys that give [count] fields a fixed order: "0001", "0002", ... (compared as text). */
export function fieldKeys(count: number): string[] {
  return Array.from({ length: count }, (_, i) => String(i + 1).padStart(4, '0'));
}

/** The key for a field added after the others: one past the largest numeric key, or by name when none. */
export function nextFieldKey(keys: (string | undefined)[]): string | undefined {
  const nums = keys.map((k) => (k && /^\d{4,}$/.test(k) ? Number(k) : null)).filter((n): n is number => n !== null);
  return nums.length ? String(Math.max(...nums) + 1).padStart(4, '0') : undefined;
}
