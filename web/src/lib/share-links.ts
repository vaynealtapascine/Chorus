// Links to a post, an entry or a message (D-109): `<server>/post/<id>` and `<server>/message/<id>`.
// They open the thing in the app (web or Android) and, pasted into a chat, show as an embed. Only
// links to this server count; the reader sees the embed only if they may already see the thing.

export type LinkTarget = { kind: 'post' | 'message'; id: string };

const ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** The link to share for a post or a message. */
export function linkTo(origin: string, target: LinkTarget): string {
  return `${origin.replace(/\/+$/, '')}/${target.kind}/${target.id}`;
}

/** A shared link's path (`/post/<id>`, `/message/<id>`), as the server hands the app over for it. */
export function pathTarget(pathname: string): LinkTarget | null {
  const path = pathname.split('/').filter(Boolean);
  if (path.length === 2 && (path[0] === 'post' || path[0] === 'message') && ID.test(path[1])) return { kind: path[0], id: path[1].toLowerCase() };
  return null;
}

/** What a link points at, if it is one of ours on this server: the share forms above, and the
 *  app's own addresses (`#/journal/post/<id>`, `#/chat/<channel>/<id>`) copied from a browser. */
export function parseLink(href: string, origin: string): LinkTarget | null {
  let url: URL;
  try {
    url = new URL(href, origin);
  } catch {
    return null;
  }
  if (url.origin !== new URL(origin).origin) return null;
  const shared = pathTarget(url.pathname);
  if (shared) return shared;
  const hash = url.hash.replace(/^#\/?/, '').split('/').filter(Boolean);
  if (hash[0] === 'journal' && hash[1] === 'post' && hash[2] && ID.test(hash[2])) return { kind: 'post', id: hash[2].toLowerCase() };
  if (hash[0] === 'chat' && hash.length === 3 && ID.test(hash[2])) return { kind: 'message', id: hash[2].toLowerCase() };
  return null;
}

/** The links in a message, in order and once each: `url` entities (their text) and `text_link`
 *  entities (their target). */
export function linksIn(text: string, entities: { type: string; offset: number; length: number; url?: string }[], origin: string): LinkTarget[] {
  const out: LinkTarget[] = [];
  const seen = new Set<string>();
  for (const e of entities) {
    const href = e.type === 'url' ? text.slice(e.offset, e.offset + e.length) : e.type === 'text_link' ? e.url : undefined;
    const target = href ? parseLink(href, origin) : null;
    if (target && !seen.has(`${target.kind}:${target.id}`)) {
      seen.add(`${target.kind}:${target.id}`);
      out.push(target);
    }
  }
  return out;
}
