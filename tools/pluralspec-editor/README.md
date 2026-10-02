# PluralSpec reader & editor

A small local tool for reading and editing the files in `spec/`. Separate from the Chorus app;
no service installation, build step, CDN, or account required.

From this directory:

```powershell
npm ci
npm start
```

The server listens on port **4783**, including the local network. Open the printed LAN link
on another device on the same network. Include `#key=…`: that per-run key grants read/write
access to the specification. The key is stored in the browser session; API requests require
it. HTTP is intended for a trusted home network. Stop with Ctrl+C; restart gives a new key.
Only loopback and RFC1918 clients are accepted. No firewall or system settings are changed.
If Windows blocks access, the owner can allow Node.js on the private network in Windows Firewall.

Use **Read** for formatted Markdown, the document menu for the draft, decisions, prior art,
schemas and example, and the outline for sections. **Edit** opens the source with live preview;
on a phone, Source / Preview switches between them. Save explicitly with the button or Ctrl/⌘ S.
Clicking an outline section while editing positions the source cursor at its heading.

Saves write to the actual repo file, without committing, validating, or rewriting other files.
A file changed in another tab or on disk is refused rather than overwritten. Download the draft
and reload to compare. Unsaved drafts are retained in browser local storage, when available;
reopening that document restores them, including their original conflict-checking revision.
If the browser cannot retain a draft, switching documents is blocked. Save it, or download a
copy and use Reload file to discard the unsaved draft. Tab and Shift+Tab use normal keyboard
navigation out of the source editor.

Before each changed save, the exact previous bytes are backed up under
`.pluralspec-editor/backups/<relative file path>/<timestamp>-<random>.bak`. Writes use a flushed
temporary file and a rename. Backups are never automatically deleted. To restore, stop the
editor, copy the desired `.bak` over its original file, and restart. Runtime links/PID and logs
also live in the gitignored `.pluralspec-editor/` directory. Never commit its access keys.

`npm test` checks backups, conflicting saves, path boundaries and access controls against a
temporary specification directory. Run it plus `python scripts/verify.py` from the repo root
before committing tool changes.

Design: inherit `docs/DESIGN.md` warm paper/ink palette with one accent; a quiet reading column,
persistent desktop outline, collapsed phone outline, and familiar explicit source editing.
Browser system fonts keep this utility self-contained. No images or decorative motion.
