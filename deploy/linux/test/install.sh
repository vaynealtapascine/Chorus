#!/usr/bin/env bash
# Inside the test VPS (scripts/test-linux.ps1): unpack the newest bundle from /bundle and run
# install.sh with the given options. With IMPORT=1, add /bundle/import-test as import/ first.
set -euo pipefail
rm -rf /root/b && mkdir /root/b
tar xzf "$(ls /bundle/chorus-linux-*.tar.gz | tail -n1)" -C /root/b
if [ "${IMPORT:-0}" = 1 ]; then cp -r /bundle/import-test /root/b/chorus/import; fi
cd /root/b/chorus
bash install.sh "$@"
