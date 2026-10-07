#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
import sys
import tempfile
import time

STATUSES = ("PENDING", "IN_PROGRESS", "HAS_QUESTIONS", "DONE", "CANCELED")
OPEN = ("PENDING", "IN_PROGRESS", "HAS_QUESTIONS")


def configure_stdio():
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8")
        except Exception:
            pass
    try:
        sys.stdin.reconfigure(encoding="utf-8")
    except Exception:
        pass


def read(path, retries=20):
    last = None
    for _ in range(retries):
        try:
            with open(path, encoding="utf-8-sig") as f:
                text = f.read()
            if not text.strip():
                return {"comments": []}
            data = json.loads(text)
            if isinstance(data, list):
                data = {"comments": data}
            data.setdefault("comments", [])
            return data
        except FileNotFoundError:
            return {"comments": []}
        except (json.JSONDecodeError, PermissionError) as e:
            last = e
            time.sleep(0.1)
    raise SystemExit("Cannot read %s: %s" % (path, last))


def write(path, data):
    directory = os.path.dirname(os.path.abspath(path))
    fd, tmp = tempfile.mkstemp(prefix=".ai-review-", suffix=".tmp", dir=directory)
    with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.write("\n")
    for _ in range(50):
        try:
            os.replace(tmp, path)
            return
        except PermissionError:
            time.sleep(0.1)
    os.remove(tmp)
    raise SystemExit("Cannot write %s" % path)


def update(path, comment_id, change):
    data = read(path)
    for c in data["comments"]:
        if c.get("id") == comment_id or str(c.get("id", "")).startswith(comment_id):
            change(c)
            write(path, data)
            return c
    raise SystemExit("No comment %s" % comment_id)


def location(c):
    path = c.get("filePath")
    if not path:
        return "общий"
    start, end = c.get("startLine"), c.get("endLine")
    if start is None:
        return path
    return "%s:%s" % (path, start if not end or end == start else "%s-%s" % (start, end))


def short(text, n=90):
    text = " ".join((text or "").split())
    return text if len(text) <= n else text[: n - 1] + "…"


def thread(c):
    return c.get("thread") or []


def needs_attention(c):
    status = c.get("status", "PENDING")
    if status not in OPEN:
        return False
    msgs = thread(c)
    if msgs and msgs[-1].get("author") == "user":
        return True
    if status == "PENDING":
        return True
    if status == "HAS_QUESTIONS":
        qs = c.get("questions") or []
        return bool(qs) and all(q.get("answer") not in (None, "") for q in qs)
    return status == "IN_PROGRESS"


def fingerprint(c):
    return {
        "status": c.get("status", "PENDING"),
        "text": hashlib.sha1((c.get("comment") or "").encode("utf-8")).hexdigest(),
        "users": sum(1 for m in thread(c) if m.get("author") == "user"),
        "answers": [q.get("answer") for q in (c.get("questions") or [])],
        "range": [c.get("filePath"), c.get("startLine"), c.get("endLine")],
    }


def events(prev, cur, c):
    cid = c.get("id", "?")
    head = "%s %s" % (cid[:8], location(c))
    if prev is None:
        return ["NEW %s — %s" % (head, short(c.get("comment")))] if cur["status"] in OPEN else []
    out = []
    if cur["users"] > prev["users"]:
        last = [m for m in thread(c) if m.get("author") == "user"][-1]
        out.append("REPLY %s — %s" % (head, short(last.get("text"))))
    if cur["answers"] != prev["answers"] and any(a not in (None, "") for a in cur["answers"]):
        out.append("ANSWER %s" % head)
    if cur["text"] != prev["text"] and cur["status"] in OPEN:
        out.append("EDIT %s — %s" % (head, short(c.get("comment"))))
    if cur["range"] != prev["range"] and cur["status"] in OPEN:
        out.append("MOVED %s" % head)
    if cur["status"] == "PENDING" and prev["status"] in ("DONE", "CANCELED", "CLOSED") and cur["users"] == prev["users"]:
        out.append("REOPEN %s — %s" % (head, short(c.get("comment"))))
    return out


def cmd_watch(path, fresh):
    state_path = path + ".live"
    state = {}
    if not fresh and os.path.exists(state_path):
        try:
            with open(state_path, encoding="utf-8") as f:
                state = json.load(f).get("comments", {})
        except Exception:
            state = {}
    if fresh or not state:
        state = {c.get("id"): fingerprint(c) for c in read(path)["comments"] if c.get("id")}
    last_mtime = None
    last_beat = 0.0

    def save():
        tmp = state_path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump({"heartbeat": int(time.time() * 1000), "pid": os.getpid(), "comments": state}, f)
        try:
            os.replace(tmp, state_path)
        except PermissionError:
            pass

    save()
    print("WATCHING %s" % os.path.abspath(path), flush=True)
    while True:
        try:
            mtime = os.path.getmtime(path) if os.path.exists(path) else None
        except OSError:
            mtime = None
        if mtime != last_mtime:
            last_mtime = mtime
            data = read(path)
            lines = []
            seen = set()
            for c in data["comments"]:
                cid = c.get("id")
                if not cid:
                    continue
                seen.add(cid)
                cur = fingerprint(c)
                lines += events(state.get(cid), cur, c)
                state[cid] = cur
            for cid in list(state):
                if cid not in seen:
                    del state[cid]
            for line in lines:
                print(line, flush=True)
            save()
            last_beat = time.time()
        elif time.time() - last_beat > 5:
            save()
            last_beat = time.time()
        time.sleep(0.7)


def cmd_open(path):
    found = False
    for c in read(path)["comments"]:
        if needs_attention(c):
            found = True
            msgs = thread(c)
            tail = " | последнее от пользователя: %s" % short(msgs[-1].get("text")) if msgs and msgs[-1].get("author") == "user" else ""
            print("%s [%s] %s — %s%s" % (c.get("id"), c.get("status"), location(c), short(c.get("comment"), 120), tail))
    if not found:
        print("Нет комментариев, требующих внимания.")


def cmd_show(path, comment_id):
    for c in read(path)["comments"]:
        if c.get("id") == comment_id or str(c.get("id", "")).startswith(comment_id):
            print(json.dumps(c, ensure_ascii=False, indent=2))
            return
    raise SystemExit("No comment %s" % comment_id)


def set_status(c, status):
    if status not in STATUSES:
        raise SystemExit("Unknown status %s" % status)
    c["status"] = status


def cmd_status(path, comment_id, status):
    c = update(path, comment_id, lambda c: set_status(c, status))
    print("%s → %s" % (c.get("id"), status))


def cmd_say(path, comment_id, status):
    text = sys.stdin.read().strip()
    if not text:
        raise SystemExit("Empty message (pass the text on stdin)")

    def change(c):
        c.setdefault("thread", []).append({"author": "claude", "text": text, "at": int(time.time() * 1000)})
        if status:
            set_status(c, status)

    c = update(path, comment_id, change)
    print("%s: сообщение добавлено%s" % (c.get("id"), " (%s)" % status if status else ""))


def main():
    configure_stdio()
    parser = argparse.ArgumentParser(description="AI review comments helper")
    parser.add_argument("--file", default=".ai-review-comments.json")
    sub = parser.add_subparsers(dest="cmd", required=True)
    w = sub.add_parser("watch")
    w.add_argument("--fresh", action="store_true")
    sub.add_parser("open")
    s = sub.add_parser("show")
    s.add_argument("id")
    st = sub.add_parser("status")
    st.add_argument("id")
    st.add_argument("status")
    sy = sub.add_parser("say")
    sy.add_argument("id")
    sy.add_argument("status", nargs="?")
    args = parser.parse_args()
    if args.cmd == "watch":
        cmd_watch(args.file, args.fresh)
    elif args.cmd == "open":
        cmd_open(args.file)
    elif args.cmd == "show":
        cmd_show(args.file, args.id)
    elif args.cmd == "status":
        cmd_status(args.file, args.id, args.status)
    elif args.cmd == "say":
        cmd_say(args.file, args.id, args.status)


if __name__ == "__main__":
    main()
