#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
import re
import select
import subprocess
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlparse

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import ai_review  # noqa: E402

PAGE = os.path.join(HERE, "ai_review_web.html")
PRESETS = os.path.join(HERE, "ai_review_web.presets.json")
CODE_PATTERNS = ["*.cs", "*.asmdef", "*.asmref", "*.json", "*.md", "*.shader", "*.hlsl", "*.cginc", "*.compute",
                 "*.uss", "*.uxml", "*.py", "*.ps1", "*.sh", "*.bat", "*.cmd", "*.js", "*.ts", "*.xml", "*.yml",
                 "*.yaml", ".gitignore", ".gitattributes", ".editorconfig"]
DEFAULT_PRESETS = [
    {"id": "code", "name": "Code only", "include": CODE_PATTERNS, "exclude": []},
    {"id": "all", "name": "All files", "include": [], "exclude": []},
]
USER_STATUSES = ("PENDING", "CANCELED", "CLOSED")
EMPTY_TREE = "4b825dc642cb6eb9a060e54bf8d69288fbee4904"
DIFF_ARGS = ("diff", "--no-color", "--no-ext-diff", "-M", "--relative")
HUNK = re.compile(r"^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@ ?(.*)$")
NO_WINDOW = 0x08000000 if os.name == "nt" else 0
MAX_TEXT = 4 * 1024 * 1024


def now_ms():
    return int(time.time() * 1000)


def new_file(path):
    return {"path": path, "oldPath": path, "status": "M", "binary": False, "additions": 0, "deletions": 0,
            "hunks": []}


def strip_prefix(p):
    p = p.rstrip("\t")
    return p[2:] if p[:2] in ("a/", "b/") else p


def parse_diff(text):
    files, cur, hunk, o, n = [], None, None, 0, 0
    for raw in text.split("\n"):
        line = raw[:-1] if raw.endswith("\r") else raw
        if line.startswith("diff --git "):
            cur, hunk = new_file(None), None
            files.append(cur)
            m = re.match(r"diff --git a/(.*) b/(.*)$", line)
            if m:
                cur["oldPath"], cur["path"] = m.group(1), m.group(2)
            continue
        if cur is None:
            continue
        m = HUNK.match(line)
        if m:
            o, n = int(m.group(1)), int(m.group(2))
            hunk = {"header": line, "section": m.group(3), "lines": []}
            cur["hunks"].append(hunk)
            continue
        if hunk is None:
            if line.startswith("new file mode"):
                cur["status"] = "A"
            elif line.startswith("deleted file mode"):
                cur["status"] = "D"
            elif line.startswith("rename from "):
                cur["oldPath"], cur["status"] = line[12:], "R"
            elif line.startswith("rename to "):
                cur["path"], cur["status"] = line[10:], "R"
            elif line.startswith("--- ") and line[4:] != "/dev/null":
                cur["oldPath"] = strip_prefix(line[4:])
            elif line.startswith("+++ ") and line[4:] != "/dev/null":
                cur["path"] = strip_prefix(line[4:])
            elif line.startswith("Binary files"):
                cur["binary"] = True
            continue
        if not line:
            continue
        tag = line[0]
        if tag == " ":
            hunk["lines"].append({"t": " ", "o": o, "n": n, "s": line[1:]})
            o, n = o + 1, n + 1
        elif tag == "-":
            hunk["lines"].append({"t": "-", "o": o, "n": None, "s": line[1:]})
            o += 1
            cur["deletions"] += 1
        elif tag == "+":
            hunk["lines"].append({"t": "+", "o": None, "n": n, "s": line[1:]})
            n += 1
            cur["additions"] += 1
    return files


def parse_raw(text):
    files, tokens, i = [], text.split("\0"), 0
    while i < len(tokens) and tokens[i].startswith(":"):
        status = tokens[i].split(" ")[-1]
        if status[0] in "RC":
            old, new, i = tokens[i + 1], tokens[i + 2], i + 3
        else:
            old = new = tokens[i + 1]
            i += 2
        item = new_file(new)
        del item["hunks"]
        item["oldPath"], item["status"] = old, "A" if status[0] == "C" else status[0]
        files.append(item)
    return files


def parse_numstat(text):
    stats, tokens, i = {}, text.split("\0"), 0
    while i < len(tokens) and tokens[i]:
        added, deleted, path = tokens[i].split("\t", 2)
        if path:
            i += 1
        else:
            path, i = tokens[i + 2], i + 3
        stats[path] = (None, None) if added == "-" else (int(added), int(deleted))
    return stats


class Git:
    def __init__(self, project, base_ref):
        self.project = project
        self.base_ref = base_ref or self.detect_base()
        self.lock = threading.Lock()
        self.cache = {}

    def run(self, *args, ok=(0,)):
        env = dict(os.environ, GIT_OPTIONAL_LOCKS="0")
        p = subprocess.run(["git", "-c", "core.quotepath=off", *args], cwd=self.project, capture_output=True,
                           env=env, creationflags=NO_WINDOW)
        if p.returncode not in ok:
            raise RuntimeError("git %s: %s" % (args[0], p.stderr.decode("utf-8", "replace").strip()))
        return p.stdout.decode("utf-8", "replace")

    def detect_base(self):
        for ref in ("main", "master", "origin/main", "origin/master"):
            if self.run("rev-parse", "--verify", "--quiet", ref + "^{commit}", ok=(0, 1)).strip():
                return ref
        return "HEAD"

    def head(self):
        return self.run("rev-parse", "HEAD").strip()

    def meta(self):
        head = self.head()
        base = self.run("merge-base", self.base_ref, "HEAD").strip()
        commits = []
        for line in self.run("log", "--format=%H%x1f%h%x1f%s%x1f%an%x1f%at", "%s..HEAD" % base).splitlines():
            parts = line.split("\x1f")
            if len(parts) == 5:
                commits.append({"sha": parts[0], "short": parts[1], "subject": parts[2], "author": parts[3],
                                "at": int(parts[4]) * 1000})
        return {"project": os.path.basename(self.project), "branch": self.run("rev-parse", "--abbrev-ref", "HEAD").strip(),
                "baseRef": self.base_ref, "base": base, "head": head, "commits": commits}

    def revs(self, scope, meta):
        if scope in ("all", "uncommitted"):
            return (meta["base"] if scope == "all" else meta["head"]), None
        new = self.run("rev-parse", "--verify", scope + "^{commit}").strip()
        parents = self.run("rev-list", "--parents", "-n", "1", new).split()
        return (parents[1] if len(parents) > 1 else EMPTY_TREE), new

    def version(self, path):
        try:
            st = os.stat(os.path.join(self.project, path))
        except OSError:
            return "-"
        return "%d:%d" % (st.st_mtime_ns, st.st_size)

    def read_text(self, path):
        full = os.path.join(self.project, path)
        try:
            if os.path.getsize(full) > MAX_TEXT:
                return None
            with open(full, "rb") as f:
                data = f.read()
        except OSError:
            return None
        if b"\0" in data[:8000]:
            return None
        lines = data.decode("utf-8-sig", "replace").split("\n")
        if lines and lines[-1] == "":
            lines.pop()
        return [s.rstrip("\r") for s in lines]

    def untracked_entry(self, path):
        item = new_file(path)
        del item["hunks"]
        item["status"], item["untracked"], item["v"] = "A", True, self.version(path)
        lines = self.read_text(path)
        if lines is None:
            item["binary"] = True
        else:
            item["additions"] = len(lines)
        return item

    def changes(self, scope):
        with self.lock:
            meta = self.meta()
            old, new = self.revs(scope, meta)
            revs = [old] if new is None else [old, new]
            raw = self.run(*DIFF_ARGS, "--raw", "-z", *revs, "--")
            files = parse_raw(raw)
            untracked = []
            if new is None:
                for f in files:
                    f["v"] = "-" if f["status"] == "D" else self.version(f["path"])
                untracked = sorted(p for p in self.run("ls-files", "--others", "--exclude-standard", "-z").split("\0") if p)
            sig = hashlib.sha1(json.dumps([meta["head"], meta["commits"], old, new, raw, [f.get("v") for f in files],
                                           [(p, self.version(p)) for p in untracked]]).encode("utf-8")).hexdigest()
            cached = self.cache.get(scope)
            if cached and cached["sig"] == sig:
                return cached
            stats = parse_numstat(self.run(*DIFF_ARGS, "--numstat", "-z", *revs, "--"))
            for f in files:
                added, deleted = stats.get(f["path"], (0, 0))
                if added is None:
                    f["binary"] = True
                else:
                    f["additions"], f["deletions"] = added, deleted
            files += [self.untracked_entry(p) for p in untracked]
            files.sort(key=lambda f: f["path"])
            result = {"meta": meta, "scope": scope, "oldRev": old, "newRev": new,
                      "diffRange": "%s..%s" % (old, new or "WORKTREE"), "files": files, "sig": sig}
            self.cache[scope] = result
            return result

    def file(self, scope, path, context="3", ignore_ws=False):
        changes = self.changes(scope)
        f = next((x for x in changes["files"] if x["path"] == path), None)
        if f is None:
            raise LookupError("No changes in %s" % path)
        result = {"path": path, "binary": f["binary"], "hunks": []}
        if f.get("untracked"):
            lines = self.read_text(path)
            if lines is None:
                result["binary"] = True
            elif lines:
                result["hunks"] = [{"header": "@@ -0,0 +1,%d @@" % len(lines), "section": "",
                                    "lines": [{"t": "+", "o": None, "n": i + 1, "s": s} for i, s in enumerate(lines)]}]
            return result
        if f["binary"]:
            return result
        revs = [changes["oldRev"]] if changes["newRev"] is None else [changes["oldRev"], changes["newRev"]]
        paths = [path] if f["oldPath"] == path else [f["oldPath"], path]
        unified = "-U1000000" if context == "full" else "-U%d" % max(0, min(int(context), 100000))
        flags = [unified] + (["-w"] if ignore_ws else [])
        parsed = parse_diff(self.run(*DIFF_ARGS, *flags, *revs, "--", *paths))
        match = next((x for x in parsed if x["path"] == path), parsed[0] if parsed else None)
        if match:
            result["hunks"], result["binary"] = match["hunks"], match["binary"]
        return result


def load_presets():
    try:
        with open(PRESETS, encoding="utf-8") as f:
            data = json.load(f)
        if data.get("presets"):
            return data
    except (OSError, ValueError):
        pass
    return {"presets": DEFAULT_PRESETS}


def save_presets(body):
    def patterns(values):
        return [str(v).strip() for v in values or [] if str(v).strip()]

    presets = [{"id": str(p.get("id") or uuid.uuid4()), "name": str(p.get("name") or "").strip() or "Untitled",
                "include": patterns(p.get("include")), "exclude": patterns(p.get("exclude"))}
               for p in body.get("presets") or []]
    if not presets:
        raise ValueError("At least one preset is required")
    os.makedirs(os.path.dirname(PRESETS), exist_ok=True)
    ai_review.write(PRESETS, {"presets": presets})
    return {"presets": presets}


class Presence:
    def __init__(self):
        self.lock = threading.Lock()
        self.count = 0
        self.since = time.monotonic()

    def enter(self):
        with self.lock:
            self.count += 1

    def leave(self):
        with self.lock:
            self.count -= 1
            if self.count == 0:
                self.since = time.monotonic()

    def idle_for(self):
        with self.lock:
            return 0 if self.count else time.monotonic() - self.since


class Store:
    def __init__(self, path):
        self.path = path
        self.lock = threading.Lock()

    def live(self):
        try:
            with open(self.path + ".live", encoding="utf-8") as f:
                data = json.load(f)
        except Exception:
            return False, []
        fresh = lambda beat: isinstance(beat, (int, float)) and now_ms() - beat < 15000
        sessions = sorted(s for s, beat in (data.get("sessions") or {}).items() if fresh(beat))
        return fresh(data.get("heartbeat", 0)), sessions

    def snapshot(self):
        live, sessions = self.live()
        return {"comments": ai_review.read(self.path)["comments"], "live": live, "liveSessions": sessions}

    def mutate(self, change):
        with self.lock:
            data = ai_review.read(self.path)
            result = change(data)
            ai_review.write(self.path, data)
            return result


def find(data, comment_id):
    for c in data["comments"]:
        if c.get("id") == comment_id:
            return c
    raise LookupError("No comment %s" % comment_id)


def required_text(body, key):
    text = (body.get(key) or "").strip()
    if not text:
        raise ValueError("Empty %s" % key)
    return text


class Handler(BaseHTTPRequestHandler):
    server_version = "AiReviewWeb"

    def log_message(self, fmt, *args):
        pass

    def send(self, code, body, content_type):
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def send_json(self, obj, code=200):
        self.send(code, json.dumps(obj, ensure_ascii=False).encode("utf-8"), "application/json; charset=utf-8")

    def hold_presence(self, presence):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        presence.enter()
        try:
            self.wfile.write(b"retry: 1000\n\n")
            while True:
                readable, _, _ = select.select([self.connection], [], [], 15)
                if readable and not self.connection.recv(1):
                    break
                self.wfile.write(b": ping\n\n")
        except OSError:
            pass
        finally:
            presence.leave()

    def body(self):
        length = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(length).decode("utf-8") or "{}")

    def route(self, method):
        url = urlparse(self.path)
        try:
            self.dispatch(method, url.path, parse_qs(url.query))
        except LookupError as e:
            self.send_json({"error": str(e)}, 404)
        except ValueError as e:
            self.send_json({"error": str(e)}, 400)
        except (Exception, SystemExit) as e:
            self.send_json({"error": str(e)}, 500)

    def dispatch(self, method, path, query):
        app = self.server.app
        arg = lambda name, default="": query.get(name, [default])[0]
        if method == "GET" and path in ("/", "/index.html"):
            with open(PAGE, "rb") as f:
                self.send(200, f.read(), "text/html; charset=utf-8")
        elif method == "GET" and path == "/api/diff":
            data = app.git.changes(arg("scope", "all"))
            self.send_json({"sig": data["sig"], "unchanged": True} if arg("sig") == data["sig"] else data)
        elif method == "GET" and path == "/api/file":
            self.send_json(app.git.file(arg("scope", "all"), arg("path"), arg("context", "3"), arg("ws") == "1"))
        elif method == "GET" and path == "/api/presence":
            self.hold_presence(app.presence)
        elif method == "GET" and path == "/api/info":
            self.send_json({"project": app.git.project, "base": app.git.base_ref, "session": app.session})
        elif method == "GET" and path == "/api/presets":
            self.send_json(load_presets())
        elif method == "POST" and path == "/api/presets":
            self.send_json(save_presets(self.body()))
        elif method == "GET" and path == "/api/comments":
            self.send_json(app.store.snapshot())
        elif method == "POST" and path == "/api/comments":
            self.send_json(app.add(self.body()))
        elif path.startswith("/api/comments/"):
            comment_id = unquote(path[len("/api/comments/"):])
            if method == "POST":
                self.send_json(app.change(comment_id, self.body()))
            elif method == "DELETE":
                self.send_json(app.delete(comment_id))
            else:
                raise LookupError(path)
        else:
            raise LookupError(path)

    def do_GET(self):
        self.route("GET")

    def do_POST(self):
        self.route("POST")

    def do_DELETE(self):
        self.route("DELETE")


class App:
    def __init__(self, git, store, session=None):
        self.git = git
        self.store = store
        self.session = session
        self.presence = Presence()

    def add(self, body):
        c = {"id": str(uuid.uuid4())}
        for key in ("filePath", "kind", "startLine", "endLine", "selectedText"):
            if body.get(key) is not None:
                c[key] = body[key]
        c["comment"] = required_text(body, "comment")
        c["commitHash"] = self.git.head()
        for key in ("revision", "diffRange", "diffSnippet"):
            if body.get(key) is not None:
                c[key] = body[key]
        c["status"] = "PENDING"
        c["createdAt"] = now_ms()
        if self.session:
            c["sessionId"] = self.session
        self.store.mutate(lambda data: data["comments"].append(c))
        return c

    def change(self, comment_id, body):
        action = body.get("action")

        def apply(data):
            c = find(data, comment_id)
            if action == "reply":
                c.setdefault("thread", []).append({"author": "user", "text": required_text(body, "text"),
                                                   "at": now_ms()})
                if c.get("status") in ("DONE", "CANCELED", "CLOSED"):
                    c["status"] = "PENDING"
            elif action == "status":
                if body.get("status") not in USER_STATUSES:
                    raise ValueError("Status %s is not set by the user" % body.get("status"))
                c["status"] = body["status"]
            elif action == "edit":
                c["comment"] = required_text(body, "comment")
                c["editedAt"] = now_ms()
                if c.get("status") in ("DONE", "CANCELED", "CLOSED"):
                    c["status"] = "PENDING"
            elif action in ("edit_reply", "delete_reply"):
                thread = c.get("thread") or []
                index = int(body.get("index", -1))
                if not 0 <= index < len(thread) or thread[index].get("author") != "user":
                    raise ValueError("Only your own replies can be changed")
                if action == "edit_reply":
                    thread[index]["text"] = required_text(body, "text")
                    thread[index]["editedAt"] = now_ms()
                else:
                    del thread[index]
            elif action == "adopt":
                if not self.session:
                    raise ValueError("This panel is not bound to a session")
                c["sessionId"] = self.session
            elif action == "answer":
                questions = c.get("questions") or []
                index = int(body.get("index", -1))
                if not 0 <= index < len(questions):
                    raise ValueError("No question %s" % index)
                questions[index]["answer"] = body.get("answer")
            else:
                raise ValueError("Unknown action %s" % action)
            return c

        return self.store.mutate(apply)

    def delete(self, comment_id):
        def apply(data):
            data["comments"].remove(find(data, comment_id))
            return {"deleted": comment_id}

        return self.store.mutate(apply)


def main():
    global PRESETS
    ai_review.configure_stdio()
    parser = argparse.ArgumentParser(description="Branch diff viewer with AI review comments")
    parser.add_argument("--project", default=".")
    parser.add_argument("--port", type=int, default=7790)
    parser.add_argument("--base", help="base branch (default: main/master)")
    parser.add_argument("--file", default=".ai-review-comments.json")
    parser.add_argument("--session", help="Claude Code session id: new comments are bound to it")
    parser.add_argument("--exit-when-idle", type=float, default=0, metavar="SECONDS",
                        help="stop once no panel page has been open for this long (0: never)")
    parser.add_argument("--presets", help="where the view presets are kept (default: next to this script)")
    args = parser.parse_args()
    if args.presets:
        PRESETS = os.path.abspath(args.presets)
    project = os.path.abspath(args.project)
    git = Git(project, args.base)
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.app = App(git, Store(os.path.join(project, args.file)), args.session)
    if args.exit_when_idle > 0:
        threading.Thread(target=exit_when_idle, args=(server, args.exit_when_idle), daemon=True).start()
    print("AI review web: http://127.0.0.1:%d  project=%s  base=%s" % (args.port, project, git.base_ref), flush=True)
    server.serve_forever()


def exit_when_idle(server, seconds):
    while server.app.presence.idle_for() < seconds:
        time.sleep(1)
    print("AI review web stopped: no panel open for %g s" % seconds, flush=True)
    server.shutdown()


if __name__ == "__main__":
    main()
