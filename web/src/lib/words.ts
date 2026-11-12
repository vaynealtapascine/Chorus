// The words a system uses for itself and for being in control (F1.2, D-004; `system.terminology`,
// DATA_MODEL §4.3), as Android's data/Words.kt: `system` is ["system", "systems"] or the chosen
// word, `front` is ["front", "fronting"] or the chosen pair. Lowercase; `cap` starts a sentence.

export interface Words { system: string; systems: string; front: string; fronting: string }

export const DEFAULT_WORDS: Words = { system: 'system', systems: 'systems', front: 'front', fronting: 'fronting' };

/** "We call ourselves": the word and its plural. */
export const SYSTEM_CHOICES: [string, string][] = [['system', 'systems'], ['collective', 'collectives'], ['household', 'households'], ['home', 'homes'], ['family', 'families']];
/** "When someone is in control, they are": what they are, and the thing they're in. */
export const FRONT_CHOICES: [string, string][] = [['fronting', 'front'], ['present', 'presence'], ['out', 'out'], ['steering', 'steer']];

export const cap = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);

type Terminology = Record<string, unknown> | string | null | undefined;

function object(t: Terminology): Record<string, unknown> | null {
  if (typeof t === 'string') {
    try { const o = JSON.parse(t); return o && typeof o === 'object' ? o : null; } catch { return null; }
  }
  return t && typeof t === 'object' ? t : null;
}

export function parseWords(terminology: Terminology): Words {
  const t = object(terminology);
  if (!t) return DEFAULT_WORDS;
  const pair = (key: string): [string, string] | null => {
    const a = t[key];
    if (!Array.isArray(a)) return null;
    const one = String(a[0] ?? '').trim().toLowerCase();
    return one ? [one, String(a[1] ?? '').trim().toLowerCase() || one] : null;
  };
  const [system, systems] = pair('system') ?? ['system', 'systems'];
  const [front, fronting] = pair('front') ?? ['front', 'fronting'];
  return { system, systems, front, fronting };
}

/** The stored terminology (other words kept) with these two choices. */
export function wordsPayload(base: Terminology, w: Words): Record<string, unknown> {
  return { ...(object(base) ?? {}), system: [w.system, w.systems], front: [w.front, w.fronting] };
}

/** A typed word for "We call ourselves": one word, its plural made the plain English way. */
export function customSystem(word: string): [string, string] | null {
  const w = word.trim().toLowerCase().slice(0, 24);
  if (!w) return null;
  if (/(s|x|ch|sh)$/.test(w)) return [w, `${w}es`];
  if (/[^aeiou]y$/.test(w)) return [w, `${w.slice(0, -1)}ies`];
  return [w, `${w}s`];
}

/** A typed word for being in control: what they are, and the same word for the thing they're in. */
export function customFront(word: string): [string, string] | null {
  const w = word.trim().toLowerCase().slice(0, 24);
  return w ? [w, w] : null;
}
