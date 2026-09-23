// A tiny hash router: #/, #/members, #/members/<id>. Keeps URLs shareable between devices.
export interface Route {
  name: 'home' | 'members' | 'member' | 'history' | 'chat' | 'trash' | 'people' | 'stage' | 'data' | 'search' | 'insights' | 'journal' | 'profile' | 'post-stage' | 'more';
  id?: string;
  messageId?: string;
}

function parse(hash: string): Route {
  const parts = hash.replace(/^#\/?/, '').split('/').filter(Boolean);
  if (parts[0] === 'members' && parts[1]) return { name: 'member', id: decodeURIComponent(parts[1]) };
  if (parts[0] === 'members') return { name: 'members' };
  if (parts[0] === 'profile' && parts[1]) return { name: 'profile', id: decodeURIComponent(parts[1]) };
  if (parts[0] === 'journal') return { name: 'journal' };
  if (parts[0] === 'stage-post' && parts[1]) return { name: 'post-stage', id: decodeURIComponent(parts[1]) };
  if (parts[0] === 'more') return { name: 'more' };
  if (parts[0] === 'history') return { name: 'history' };
  if (parts[0] === 'chat') return { name: 'chat', id: parts[1] ? decodeURIComponent(parts[1]) : undefined, messageId: parts[2] ? decodeURIComponent(parts[2]) : undefined };
  if (parts[0] === 'search') return { name: 'search' };
  if (parts[0] === 'insights') return { name: 'insights' };
  if (parts[0] === 'stage' && parts[1]) return { name: 'stage', id: decodeURIComponent(parts[1]) };
  if (parts[0] === 'data') return { name: 'data' };
  if (parts[0] === 'people') return { name: 'people' };
  if (parts[0] === 'trash') return { name: 'trash', id: parts[1] ? decodeURIComponent(parts[1]) : undefined };
  return { name: 'home' };
}

class Router {
  route: Route = $state(parse(location.hash));
  constructor() {
    addEventListener('hashchange', () => (this.route = parse(location.hash)));
  }
  go(path: string) {
    location.hash = path;
  }
}

export const router = new Router();
