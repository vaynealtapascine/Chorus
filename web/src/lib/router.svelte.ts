// A tiny hash router: #/, #/members, #/members/<id>. Keeps URLs shareable between devices.
export interface Route {
  name: 'home' | 'members' | 'member' | 'history' | 'chat' | 'trash' | 'people' | 'stage';
  id?: string;
}

function parse(hash: string): Route {
  const parts = hash.replace(/^#\/?/, '').split('/').filter(Boolean);
  if (parts[0] === 'members' && parts[1]) return { name: 'member', id: decodeURIComponent(parts[1]) };
  if (parts[0] === 'members') return { name: 'members' };
  if (parts[0] === 'history') return { name: 'history' };
  if (parts[0] === 'chat') return { name: 'chat', id: parts[1] ? decodeURIComponent(parts[1]) : undefined };
  if (parts[0] === 'stage' && parts[1]) return { name: 'stage', id: decodeURIComponent(parts[1]) };
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
