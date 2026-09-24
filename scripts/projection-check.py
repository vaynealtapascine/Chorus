#!/usr/bin/env python3
"""Every field in DATA_MODEL's op catalogue must land somewhere in SQL (verify.py runs this).

The server projects an op's payload by column name: a field whose name has no column in the
entity's table is silently dropped. That hid messages' `reply_to` and posts' `repost_of` (their
columns are `reply_to_id` / `repost_of_id`). This applies the migrations to an in-memory database
and fails on catalogue fields that have neither a column nor an entry in HANDLED below.
"""
import os
import re
import sqlite3
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))

# fields projected some other way; say where, so a reader can check
HANDLED = {
    ('*', 'fields'): 'generic `.set` payload: its keys are the columns',
    ('message', 'authors'): 'message_author (+ segment authors)',
    ('message', 'segments'): 'message_segment / message_segment_author',
    ('message', 'attachments'): 'item_attachment',
    ('message', 'message_id'): 'the edited message (entity), not a column',
    ('message', 'reply_to'): 'reply_to_id (project.rs)',
    ('message', 'forward'): 'message.forward carries forward_of_id / forward_snapshot instead',
    ('post', 'authors'): 'post_author',
    ('post', 'attachments'): 'item_attachment',
    ('post', 'reply_to'): 'reply_to_id (project.rs)',
    ('post', 'repost_of'): 'repost_of_id (project.rs)',
    ('channel', 'target_type'): 'channel_permission rows (channel.set_permission)',
    ('channel', 'target_id'): 'channel_permission rows',
    ('channel', 'allow'): 'channel_permission rows',
    ('channel', 'deny'): 'channel_permission rows',
}


def schema() -> sqlite3.Connection:
    db = sqlite3.connect(':memory:')
    mig = os.path.join(ROOT, 'crates', 'chorus-server', 'migrations')
    for name in sorted(os.listdir(mig)):
        if name.endswith('.sql'):
            db.executescript(open(os.path.join(mig, name), encoding='utf-8').read())
    return db


def top_level_fields(body: str) -> list[str]:
    depth, parts, cur = 0, [], ''
    for ch in body:
        if ch in '{[':
            depth += 1
        elif ch in '}]':
            depth -= 1
        elif ch == ',' and depth == 0:
            parts.append(cur)
            cur = ''
            continue
        if depth == 0:
            cur += ch
    parts.append(cur)
    out = []
    for p in parts:
        m = re.match(r'\s*(?:\.\.\.)?\s*([a-z_]+)', p)
        if m:
            out.append(m.group(1))
    return out


def main() -> int:
    db = schema()
    tables = {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type = 'table'")}
    doc = open(os.path.join(ROOT, 'docs', 'DATA_MODEL.md'), encoding='utf-8').read()
    problems = []
    for m in re.finditer(r'^\| `([a-z_]+)\.([a-z_]+)` \| `\{([^`]*)\}`', doc, re.M):
        table, action, body = m.groups()
        if table not in tables:
            continue  # special kinds (front.*, pref.*, read.*…) have their own projections
        cols = {r[1] for r in db.execute(f'PRAGMA table_xinfo({table})')}
        for field in top_level_fields(body):
            if field in cols or ('*', field) in HANDLED or (table, field) in HANDLED:
                continue
            problems.append(f'{table}.{action}: `{field}` has no column in {table} and is not in HANDLED')
    for p in problems:
        print(p)
    if problems:
        print('\nmap it in project.rs (or add a column), then list it in scripts/projection-check.py HANDLED')
        return 1
    print('op catalogue fields all land in SQL')
    return 0


if __name__ == '__main__':
    sys.exit(main())
