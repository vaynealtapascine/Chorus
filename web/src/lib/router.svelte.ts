// A tiny hash router: #/, #/members, #/members/<id>. Keeps URLs shareable between devices.
export interface Route {
  name: 'home' | 'members' | 'member';
  id?: string;
}

function parse(hash: string): Route {
  const parts = hash.replace(/^#\/?/, '').split('/').filter(Boolean);
  if (parts[0] === 'members' && parts[1]) return { name: 'member', id: decodeURIComponent(parts[1]) };
  if (parts[0] === 'members') return { name: 'members' };
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
