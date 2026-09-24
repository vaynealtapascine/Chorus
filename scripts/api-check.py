#!/usr/bin/env python3
"""docs/API.md can't drift from the router (verify.py).

    python scripts/api-check.py          # self-test, then fail listing routes API.md doesn't document
    python scripts/api-check.py --list   # print every route the router serves

Routes come from every `.route("…", get(…).post(…))` in crates/chorus-server/src/app.rs; a router
bound with `let name = Router::new()…` and mounted with `.nest("/prefix", name)` gets the prefix.
A route counts as documented when API.md mentions its method and path together: `GET /members/{id}`
in a code block or in prose, `GET/POST /webhooks`, or further paths on the same line
(`GET  /lists  /lists/{id}/timeline`). Path parameters match whatever their name (`{hash}` =
`{sha256}`); a query string ends a path; paths under `/api/v1` may be written without it.
"""
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
APP = os.path.join(ROOT, 'crates', 'chorus-server', 'src', 'app.rs')
DOC = os.path.join(ROOT, 'docs', 'API.md')
API_PREFIX = '/api/v1'
METHODS = ('GET', 'POST', 'PUT', 'DELETE', 'HEAD', 'PATCH')


def normalise(path):
    """`/blobs/{hash}?thumb=1` → `/blobs/{}`; a trailing slash doesn't count."""
    path = re.split(r'[?#]', path, maxsplit=1)[0]
    path = re.sub(r'\{[^}/]*\}', '{}', path)
    return path.rstrip('/') or '/'


def call_end(src, open_paren):
    """Index just past the parenthesis that closes the one at `open_paren` (strings skipped)."""
    depth, i = 0, open_paren
    while i < len(src):
        c = src[i]
        if c == '"':
            i += 1
            while src[i] != '"':
                i += 2 if src[i] == '\\' else 1
        elif c == '(':
            depth += 1
        elif c == ')':
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    raise ValueError('unbalanced parentheses')


def routes(src):
    """[(METHOD, full path)] served by the routers in `src`."""
    # which router each `.route(` belongs to: the nearest `let <name> = Router::new()` before it
    lets = [(m.start(), m.group(1)) for m in re.finditer(r'let\s+(?:mut\s+)?(\w+)\s*=\s*Router::new\(\)', src)]
    prefixes = {m.group(2): m.group(1) for m in re.finditer(r'\.nest\(\s*"([^"]*)"\s*,\s*(\w+)\s*\)', src)}
    out = []
    for m in re.finditer(r'\.route\(\s*"([^"]*)"\s*,', src):
        body = src[m.end():call_end(src, m.start() + len('.route'))]
        owner = next((name for at, name in reversed(lets) if at < m.start()), None)
        prefix = prefixes.get(owner, '')
        methods = re.findall(r'(?<![\w:])(get|post|put|delete|head|patch)\(', body)
        if not methods:
            raise ValueError(f'no method for route {m.group(1)!r}')
        for method in methods:
            out.append((method.upper(), prefix + m.group(1)))
    return out


def documented(doc):
    """{(METHOD, normalised path)} that `doc` mentions."""
    found = set()
    verbs = '|'.join(METHODS)
    pattern = re.compile(rf'\b((?:{verbs})(?:/(?:{verbs}))*)[ \t]+(/[^\s`,)]*)((?:[ \t]+/[^\s`,)]*)*)')
    for m in pattern.finditer(doc):
        paths = [m.group(2)] + m.group(3).split()
        for method in m.group(1).split('/'):
            for path in paths:
                path = normalise(path)
                found.add((method, path))
                if path.startswith(API_PREFIX + '/'):
                    found.add((method, path[len(API_PREFIX):]))
    return found


def gaps(src, doc):
    known = documented(doc)
    missing = []
    for method, path in routes(src):
        rel = path[len(API_PREFIX):] if path.startswith(API_PREFIX + '/') else path
        if (method, normalise(rel)) not in known:
            missing.append(f'{method} {path}')
    return missing


def self_test():
    src = '''
    let api = Router::new()
        .route("/members/{id}", get(member_one))
        .route("/webhooks", get(list).post(create))
        .route(
            "/blobs/{hash}",
            get(blobs::get_blob).head(blobs::head_blob).put(blobs::put_blob).layer(DefaultBodyLimit::max(4 * (1 << 20))),
        )
        .layer(axum::middleware::from_fn_with_state(state.clone(), limit));
    let mut app = Router::new().nest("/api/v1", api).route("/overlay/front", get(overlay));
    '''
    assert routes(src) == [
        ('GET', '/api/v1/members/{id}'),
        ('GET', '/api/v1/webhooks'),
        ('POST', '/api/v1/webhooks'),
        ('GET', '/api/v1/blobs/{hash}'),
        ('HEAD', '/api/v1/blobs/{hash}'),
        ('PUT', '/api/v1/blobs/{hash}'),
        ('GET', '/overlay/front'),
    ], routes(src)
    doc = '''
    GET  /members/{member_id}?x=1         one member
    - `GET/POST /webhooks {url}` → `{id}`
    HEAD /blobs/{sha256}   200
    GET  /lists  /lists/{id}/timeline
    `GET /overlay/front?token=…`
    '''
    assert ('GET', '/lists/{}/timeline') in documented(doc)
    assert gaps(src, doc) == ['GET /api/v1/blobs/{hash}', 'PUT /api/v1/blobs/{hash}'], gaps(src, doc)
    # the real router: its shape is what the parser has to keep up with
    real = routes(open(APP, encoding='utf-8').read())
    for r in [('GET', '/api/v1/members/{id}'), ('HEAD', '/api/v1/blobs/{hash}'), ('POST', '/api/v1/channels/{id}/messages'),
              ('DELETE', '/api/v1/devices/push'), ('GET', '/overlay/front')]:
        assert r in real, f'{r} not found in app.rs'


def main():
    self_test()
    src = open(APP, encoding='utf-8').read()
    if '--list' in sys.argv:
        for method, path in routes(src):
            print(f'{method:<6} {path}')
        return
    missing = gaps(src, open(DOC, encoding='utf-8').read())
    if missing:
        print('docs/API.md does not document these routes (crates/chorus-server/src/app.rs):', file=sys.stderr)
        for m in missing:
            print(f'  {m}', file=sys.stderr)
        sys.exit(1)
    print(f'api-check: all {len(routes(src))} routes are in docs/API.md')


if __name__ == '__main__':
    main()
