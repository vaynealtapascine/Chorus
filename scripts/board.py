#!/usr/bin/env python3
"""Update PROGRESS.md without hand-editing.

  python scripts/board.py start M1.2 "who" "next concrete step"
  python scripts/board.py done  M1.2 "who" "log line"
  python scripts/board.py note  "free text for the Notes line"

`start` marks the task [~] and fills the Now block; `done` ticks it [x], appends a dated log
line and clears Now if it names that task (the next agent picks the next unticked task).
"""
import datetime
import io
import re
import sys

PATH = "PROGRESS.md"


def load():
    return io.open(PATH, encoding="utf-8").read()


def save(s):
    io.open(PATH, "w", encoding="utf-8", newline="\n").write(s)


def set_now(s, task, owner, step):
    s = re.sub(r"^- \*\*In progress:\*\*.*$", f"- **In progress:** {task}", s, count=1, flags=re.M)
    s = re.sub(r"^- \*\*Owner:\*\*.*$", f"- **Owner:** {owner}", s, count=1, flags=re.M)
    s = re.sub(r"^- \*\*Next concrete step:\*\*.*$", lambda _: f"- **Next concrete step:** {step}", s, count=1, flags=re.M)
    return s


def mark(s, task, box):
    pat = re.compile(rf"^- \[[ x~\-]\] {re.escape(task)} ", re.M)
    if not pat.search(s):
        sys.exit(f"task {task} not found on the board")
    return pat.sub(f"- [{box}] {task} ", s, count=1)


def main():
    cmd, *args = sys.argv[1:]
    s = load()
    today = datetime.date.today().isoformat()
    if cmd == "start":
        task, owner, step = args
        s = mark(s, task, "~")
        s = set_now(s, task, f"{owner}, {today}", step)
    elif cmd == "done":
        task, owner, line = args
        s = mark(s, task, "x")
        # clear Now only if it names this task (another agent's work may be recorded there)
        if re.search(rf"^- \*\*In progress:\*\* {re.escape(task)}\b", s, flags=re.M):
            s = set_now(s, "—", "—", "claim the next unticked task on the board")
        s = s.rstrip("\n") + f"\n- {today} {owner} — {task} {line}\n"
    elif cmd == "note":
        (text,) = args
        s = re.sub(r"^- \*\*Notes:\*\*.*$", lambda _: f"- **Notes:** {text}", s, count=1, flags=re.M)
    else:
        sys.exit(__doc__)
    save(s)


if __name__ == "__main__":
    main()
