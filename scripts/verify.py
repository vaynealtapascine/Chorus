#!/usr/bin/env python3
"""Everything that must pass before a commit (AGENTS.md).

    python scripts/verify.py            # rust + web (if web/node_modules exists)
    python scripts/verify.py --quick    # skip the long simulator/proptest runs (debug build)
    python scripts/verify.py --android  # also gradle lint + unit tests (needs JDK 17 + SDK)
    python scripts/verify.py --changed  # only the stages what changed since main can affect (D-118)
    python scripts/verify.py --changed=REF  # the same, since REF
    python scripts/verify.py --release  # the Rust tests on the shipping profile (10-15 min after a change)

It also runs scripts/api-check.py (docs/API.md documents every route).

Run it in full, with --android, before a deploy (AGENTS.md).

Exits non-zero on the first failure and prints the command that failed, then what each step took.
"""
import os
import shutil
import subprocess
import sys
import time

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))

# What a change under each path can affect (D-118). Clients reach the server over HTTP, so a server
# change can't break their tests, and no tree's tests read another's files. Core reaches the clients
# through wasm and FFI and fixtures are the cross-language contract, so those run everything, as
# does any path not listed here. The quick checks at the start always run (they read docs/ too).
EVERYTHING = {'rust', 'wasm', 'web', 'android', 'editor'}
SCOPES = (
    ('docs/', set()),
    ('crates/chorus-server/', {'rust'}),
    ('web/', {'web'}),
    ('android/', {'android'}),
    ('tools/pluralspec-editor/', {'editor'}),
)
DOCS = {'AGENTS.md', 'CLAUDE.md', 'LICENSE', 'PRODUCT.md', 'PROGRESS.md', 'README.md'}


def stages_for(paths):
    stages = set()
    for path in paths:
        if path in DOCS:
            continue
        for prefix, scope in SCOPES:
            if path.startswith(prefix):
                stages |= scope
                break
        else:
            return set(EVERYTHING)
    return stages


def changed_paths(base):
    """Everything that differs from where this branch left [base], committed or not."""
    def git(*args):
        return subprocess.run(['git', *args], cwd=ROOT, capture_output=True, text=True, encoding='utf-8', check=True).stdout
    fork = git('merge-base', 'HEAD', base).strip()
    # --no-renames: a file moved out of a path has to count against that path as well
    return sorted(set(filter(None, (git('diff', '--name-only', '--no-renames', fork) + git('ls-files', '--others', '--exclude-standard')).split('\n'))))


TIMES = []


def timings():
    print('\nTime by step:')
    for seconds, cmd in TIMES:
        print(f'{seconds:6.0f}s  {cmd[:90]}')
    print(f'{sum(t for t, _ in TIMES):6.0f}s  total')


def run(cmd, cwd=ROOT, env=None):
    print(f'\n▶ {" ".join(cmd)}', flush=True)
    started = time.monotonic()
    r = subprocess.run(cmd, cwd=cwd, env={**os.environ, **(env or {})}, shell=(os.name == 'nt' and cmd[0] in ('npm', 'npx')))
    TIMES.append((time.monotonic() - started, ' '.join(cmd)))
    if r.returncode != 0:
        print(f'\n✗ failed: {" ".join(cmd)}', file=sys.stderr)
        timings()
        sys.exit(r.returncode)


def bundle_budgets(dist):
    """SPEC §9: the JS that opens the app ≤ 300 KB gz (D-116, D-156), wasm ≤ 500 KB gz (D-108).

    The opening JS is index.html's script and everything it imports statically, followed through the
    chunks; screens behind `import()` (web/src/lib/lazy-screens.ts) are fetched later and only counted
    in the total, which is printed so its growth stays visible."""
    import gzip
    import re
    assets = os.path.join(dist, 'assets')
    sizes, sources = {}, {}
    for name in os.listdir(assets):
        with open(os.path.join(assets, name), 'rb') as f:
            data = f.read()
        if name.endswith(('.js', '.wasm')):
            sizes[name] = len(gzip.compress(data, 9))
        if name.endswith('.js'):
            sources[name] = data.decode('utf-8')
    with open(os.path.join(dist, 'index.html'), encoding='utf-8') as f:
        todo = re.findall(r'<script[^>]+src="/assets/([^"]+\.js)"', f.read())
    if not todo:
        print('\n✗ failed: index.html names no script', file=sys.stderr)
        sys.exit(1)
    opening = set()
    while todo:
        name = todo.pop()
        if name in opening or name not in sources:
            continue
        opening.add(name)
        # `import … from"./x.js"` and `import"./x.js"`; a lazy `import("./x.js")` has a bracket and is left out
        todo += re.findall(r'(?:\bfrom|\bimport)\s*["\']\./([^"\']+\.js)["\']', sources[name])
    js = sum(sizes[name] for name in opening)
    total = sum(size for name, size in sizes.items() if name.endswith('.js'))
    wasm = sum(size for name, size in sizes.items() if name.endswith('.wasm'))
    print(f'\nbundle: opening JS {js / 1024:.0f} KB gz (≤ 300) in {len(opening)} of {len(sources)} files, '
          f'all JS {total / 1024:.0f} KB gz, wasm {wasm / 1024:.0f} KB gz (≤ 500)')
    if js > 300 * 1024 or wasm > 500 * 1024:
        print('\n✗ failed: SPEC §9 bundle budget', file=sys.stderr)
        sys.exit(1)


def main():
    # ▶ and ✓ crash a Windows console codepage when the output goes through a pipe
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, 'reconfigure'):
            stream.reconfigure(encoding='utf-8')
    # the owner's C: is nearly full: build on F: there unless told otherwise (NOTES, PROGRESS)
    roomy = r'F:\DunBuild\chorus-target'
    if os.name == 'nt' and 'CARGO_TARGET_DIR' not in os.environ and os.path.isdir(roomy):
        os.environ['CARGO_TARGET_DIR'] = roomy
    quick = '--quick' in sys.argv
    android = '--android' in sys.argv
    stages = set(EVERYTHING)
    changed = next((a for a in sys.argv if a == '--changed' or a.startswith('--changed=')), None)
    if changed:
        base = changed.partition('=')[2] or 'main'
        paths = changed_paths(base)
        stages = stages_for(paths)
        # what changed decides: a change to the Android app is never passed without its tests
        android = 'android' in stages
        print(f'{len(paths)} files changed since {base}: {", ".join(sorted(stages)) or "quick checks only"}', flush=True)

    if shutil.which('node'):
        run(['node', 'scripts/gen-tokens.mjs', '--check'])
        editor = os.path.join(ROOT, 'tools', 'pluralspec-editor')
        if 'editor' in stages and os.path.isdir(os.path.join(editor, 'node_modules')):
            run(['npm', 'test'], cwd=editor)
    run([sys.executable, 'scripts/projection-check.py'])
    run([sys.executable, 'scripts/api-check.py'])
    if 'rust' in stages:
        run(['cargo', 'fmt', '--all', '--check'])
        run(['cargo', 'clippy', '--workspace', '--all-targets', '--', '-D', 'warnings'])
        if quick:
            run(['cargo', 'test', '--workspace'], env={'CHORUS_SIM_SEEDS': '50', 'PROPTEST_CASES': '64'})
        elif '--release' in sys.argv:
            run(['cargo', 'test', '--workspace', '--release'])
        else:
            # optimised: the convergence simulator and proptests are CPU-heavy, and speed budgets
            # only hold in optimised builds. Without release's link-time work (Cargo.toml, D-118).
            run(['cargo', 'test', '--workspace', '--profile', 'gate'])
    if 'wasm' in stages:
        run(['cargo', 'build', '-p', 'chorus-wasm', '--target', 'wasm32-unknown-unknown', '--profile', 'wasm'])

    web = os.path.join(ROOT, 'web')
    if 'web' not in stages:
        pass
    elif os.path.isdir(os.path.join(web, 'node_modules')):
        run(['npm', 'run', 'check'], cwd=web)
        run(['npm', 'test'], cwd=web)
        run(['npm', 'run', 'build'], cwd=web)
        bundle_budgets(os.path.join(web, 'dist'))
    elif os.path.isdir(web):
        print('\n(web: skipped — run `npm ci` in web/ first)')

    gradlew = os.path.join(ROOT, 'android', 'gradlew.bat' if os.name == 'nt' else 'gradlew')
    if android and os.path.exists(gradlew):
        # JVM unit tests load the host debug build of chorus-ffi (core-bridge/build.gradle.kts)
        run(['cargo', 'build', '-p', 'chorus-ffi'])
        env = {}
        jbr = r'C:\Program Files\Android\Android Studio\jbr'
        if os.name == 'nt' and os.path.isdir(jbr):
            env['JAVA_HOME'] = jbr
        if os.name == 'nt' and os.path.isdir(r'F:\DunBuild\gradle'):
            env['GRADLE_USER_HOME'] = r'F:\DunBuild\gradle'
        gradle_args = [gradlew, 'lint', 'testDebugUnitTest', '--console=plain']
        if '--offline' in sys.argv:
            gradle_args.append('--offline')
        run(gradle_args, cwd=os.path.join(ROOT, 'android'), env=env)
    elif android:
        print('\n(android: no gradlew yet)')

    timings()
    print('\n✓ verify passed')


if __name__ == '__main__':
    if shutil.which('cargo') is None:
        sys.exit('cargo not found')
    main()
