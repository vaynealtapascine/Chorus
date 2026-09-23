![AI Disclosure: Repo code is fully AI-generated. Makes use of anthropic/claude-opus-5.5 and openai/gpt-6-sol](assets/ai-transparency-disclosure.png)

# Chorus

A cozy, local-first home for plural systems: chat as your members, keep journals, switch in one tap, and share who's around on your terms.

> **Status:** in active development. The server, sync and web app work end to end (members,
> switching, chat, stage mode, follows with delayed/fuzzed switch notifications); the Android app
> and its home-screen widget are being finished. See [PROGRESS.md](PROGRESS.md).

<img src="assets/screenshot-web-light.png" alt="Chorus on the web: the home screen with who's fronting and quick switch" width="360">

Chorus is a self-hosted PluralKit replacement that lives on your own server over Tailscale:

- **Chat** — Discord-style spaces and channels, where every message is sent *as* one or more
  members (proxy tags, emoji sigils, multi-author messages), with replies, quotes, forwards,
  threads, pins, reactions and Telegram-level formatting.
- **Profiles & journals** — each member gets a profile with posts, long-form entries,
  highlights, relationships, lists and custom feeds.
- **Stage mode** — show just the messages you want in a screenshot; hide or grey out the rest,
  redact names, pick a style.
- **Quick switching** — an Android home-screen widget built for big systems with nested
  subsystems, co-fronting, co-con and present-but-not-fronting.
- **Notifications for the people you trust** — with random delays, time fuzzing and digests, and
  a guarantee that the app never leaks more than the notifications do.
- **Your data, readable** — plain SQL tables, documented views, CSV/JSONL/SQLite exports, a live
  event stream and webhooks.
- **Offline-first** — the Android app and web app work without a connection and resync cleanly.

## Running it

Chorus runs on one Windows PC and is reached over Tailscale through Caddy
([Operations](docs/OPS.md)).

```powershell
pwsh scripts/deploy.ps1            # build the server + web app into ~\selfhost\chorus
pwsh scripts/deploy.ps1 -Android   # also publish the Android app for in-app updates
~\selfhost\chorus\install.cmd      # once, as administrator: Windows service + Caddy site + first invite
```

For development: `cargo run -p chorus-server -- --dev serve` (port 5251) and, in `web/`,
`npm run dev` (port 5252). `python scripts/verify.py` runs every check.

## Documentation

| Doc | |
| --- | --- |
| [Product spec](docs/SPEC.md) | What Chorus does |
| [Decisions](docs/DECISIONS.md) | What's settled |
| [Data model](docs/DATA_MODEL.md) | Schema, op log, analysis views |
| [Sync](docs/SYNC.md) | Offline-first protocol |
| [Notifications](docs/NOTIFICATIONS.md) | Switch notifications and privacy |
| [API](docs/API.md) | REST, stream, webhooks |
| [Design](docs/DESIGN.md) | Look, screens, settings |
| [Clients](docs/CLIENTS.md) | Android, widget, web |
| [Operations](docs/OPS.md) | Running the server |

## License

MIT. See [LICENSE](LICENSE).
