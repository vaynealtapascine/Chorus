#!/usr/bin/env python3
"""Everything that must pass before a commit (AGENTS.md).

    python scripts/verify.py            # rust + web (if web/node_modules exists)
    python scripts/verify.py --quick    # skip the long simulator/proptest runs (debug build)
    python scripts/verify.py --android  # also gradle lint + unit tests (needs JDK 17 + SDK)

Exits non-zero on the first failure and prints the command that failed.
"""
import os
import shutil
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))


def run(cmd, cwd=ROOT, env=None):
    print(f'\n▶ {" ".join(cmd)}', flush=True)
    r = subprocess.run(cmd, cwd=cwd, env={**os.environ, **(env or {})}, shell=(os.name == 'nt' and cmd[0] in ('npm', 'npx')))
    if r.returncode != 0:
        print(f'\n✗ failed: {" ".join(cmd)}', file=sys.stderr)
        sys.exit(r.returncode)


def main():
    quick = '--quick' in sys.argv
    android = '--android' in sys.argv

    run(['cargo', 'fmt', '--all', '--check'])
    run(['cargo', 'clippy', '--workspace', '--all-targets', '--', '-D', 'warnings'])
    if quick:
        run(['cargo', 'test', '--workspace'], env={'CHORUS_SIM_SEEDS': '50', 'PROPTEST_CASES': '64'})
    else:
        # release: the convergence simulator and proptests are CPU-heavy
        run(['cargo', 'test', '--workspace', '--release'])
    run(['cargo', 'build', '-p', 'chorus-wasm', '--target', 'wasm32-unknown-unknown', '--release'])

    web = os.path.join(ROOT, 'web')
    if os.path.isdir(os.path.join(web, 'node_modules')):
        run(['npm', 'run', 'check'], cwd=web)
        run(['npm', 'test'], cwd=web)
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
        run([gradlew, 'lint', 'testDebugUnitTest', '--console=plain'], cwd=os.path.join(ROOT, 'android'), env=env)
    elif android:
        print('\n(android: no gradlew yet)')

    print('\n✓ verify passed')


if __name__ == '__main__':
    if shutil.which('cargo') is None:
        sys.exit('cargo not found')
    main()
