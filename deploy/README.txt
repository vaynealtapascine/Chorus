Chorus - self-hosted
====================

install.cmd   One-time setup: Windows service (NSSM), Caddy site, first invite. Safe to rerun.
check.cmd     Shows what install.cmd would do; changes nothing.
chorus.toml   Server settings (written on first install; see docs/OPS.md in the repo).
app\          The server and the web app. Updated by `pwsh scripts/deploy.ps1` in the repo.
data\         Your database and files. Never touched by deploys. Back this folder up.

New invites:  app\chorus-server.exe --config chorus.toml invite --kind system|person
Health:       http://127.0.0.1:5250/api/v1/server
