// The "What is new" lines of the update sheet (F10.5): up to three short lines of a text file, lines
// starting with # skipped (the same file shape as Android's whats-new.txt).
export function parseWhatsNew(text: string): string[] {
  return text.split(/\r?\n/).map((l) => l.trim()).filter((l) => l && !l.startsWith('#')).slice(0, 3);
}
