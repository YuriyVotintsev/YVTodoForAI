# YVTodoForAI

A JetBrains Rider plugin for writing code review comments that an AI assistant (Claude Code) processes.

## Features

- **Comment code** – select code (or just put the caret on a line) and press `Ctrl+Alt+,`
  (or right-click → "Комментарий для Claude…").
- **Comment files and folders** – right-click a file or folder in the Explorer (Unity / File System / Solution) or on
  an editor tab.
- **Review branch changes** – Explorer → "AI Review" view: all changes of the current branch relative to `main`
  (or any other branch), optionally with uncommitted ones. Works for already merged branches too: the base is the
  `main` state the branch was merged into. Flat or tree view, open comments counted per file.
- **Review commits** – Git Log → select one or two commits → right-click → "AI Review: …".
- **Diff tab per file** – syntax highlighting with IDE colors, word-level changes, ±N lines of context with
  expandable gaps, one or two columns. Drag over line numbers (left column – old version, right – new) or select code
  and press `c` to comment right in the diff.
- **AI Review tool window** – all comments grouped by file: statuses, the AI's questions, a conversation thread under
  every comment, "Завершённые" for comments the user has checked.
- Open comments are marked in the editor gutter.

Comments are stored in `.ai-review-comments.json` in the project root (the plugin adds it to `.git/info/exclude`).

## Claude Code

`claude/` holds the Claude Code side:

- `commands/review_comments.md` – `/review_comments`: process all comments at once (questions first, then the work).
- `commands/review_live.md` – `/review_live`: a watcher stays open and Claude reacts to every new comment or reply
  as it appears. Not needed while the `review-bridge` mod is loaded; its "Реакция на события" rules are what the mod
  points Claude to.
- `scripts/ai_review.py` – the helper both commands use (`open`, `show`, `status`, `say`, `watch`).
- `scripts/ai_review_web.py` + `scripts/ai_review_web.html` – the review panel for the Claude Desktop app: a local
  server (127.0.0.1, Python standard library only) and a page opened in the app's built-in browser. The branch diff one
  file at a time (unified or split, more or fewer context lines, the whole file, whitespace and invisible-symbol
  toggles), editable presets of what to show (code only, all files), a flat list or a folder tree, comments on lines
  of the old or the new version, on a whole file, on a folder or general, and threads with Claude's replies. Comments
  go to the same `.ai-review-comments.json`.
- `mods/review-bridge/` – a Claude Code mod:
  - watches `.ai-review-comments.json` and hands every new comment, reply, edit or answer to Claude in the running
    session within seconds (no watcher to restart) and lights "Claude live" in the panel; the prompt is the comment
    itself (place and text), sent as the person's words;
  - binds comments to sessions: the panel a session opens runs its own server with `--session <id>`, which stamps
    `sessionId` on every comment written there, and the mod hands a comment only to its own session. The panel's
    Comments tab shows "This session" or "All sessions"; a comment of another session, or one written outside a
    session (Rider, older files), carries a tag there and can be taken into this session with one click. Unbound
    comments are never handed over by themselves;
  - gives Claude the tools `open`, `show`, `status` and `reply` (`mcp__review-bridge__*`, listed up front) so it
    answers in the thread without running `ai_review.py` through Bash; they call that same helper, so the file keeps
    one writer;
  - shows a "Review" button in the prompt footer that opens the panel, with this session's open comments on it
    ("Review: 2 open · 1 question"); it stays while the project has a review file, even with nothing open;
  - `/review-panel [--base <branch>]` opens the panel without a model turn: picks a free port (7790–7799), starts the
    server itself with `--exit-when-idle 60` (an open panel page holds an event stream to it, so the server stops a
    minute after the last panel closes and the next open starts it again in a fraction of a second),
    writes an attach-only `code-review` configuration (`url`, no command) to the project's `.claude/launch.json`,
    excludes that file from git and shows the page in the built-in browser. A panel left open waits for its server and
    reconnects by itself. The mod lets its own `preview_start` call for `code-review` through a permission ask (auto
    mode cannot classify a call no prompt asked for), so no settings rule is needed; a deny rule still wins;
  - `/review-auto on|off` switches the hand-over per project;
  - `claude plugin test claude/mods/review-bridge` runs its tests.

Install:

- copy `claude/commands/*.md` to `~/.claude/commands/` and `claude/scripts/*` to `~/.claude/scripts/`;
- load the mod straight from the repository: add its folder to `CLAUDE_CODE_PLUGIN_DIRS` in the `env` block of
  `~/.claude/settings.json`, e.g. `"CLAUDE_CODE_PLUGIN_DIRS": "D:/Projects/rider-comments-plugin/claude/mods/review-bridge"`
  (several folders are separated by `;` on Windows, `:` elsewhere). New sessions load it, and an interactive session
  reloads it when its files change.

The Rider plugin never starts Claude itself: comments are handed over by the mod, or by the commands run by hand in a
Claude Code session.

## Build

```
build.bat
```

Uses the locally installed Rider (`ideLocalPath` in `gradle.properties`), otherwise downloads Rider 2025.3.1. Install
the zip from `build/distributions` via Settings → Plugins → ⚙ → Install Plugin from Disk.

## Requirements

- JetBrains Rider 2025.3+
- git in PATH
- Python 3 for `claude/scripts/ai_review.py` and `claude/scripts/ai_review_web.py`
- Claude Code Desktop for the review panel; a Claude Code build with mods (function-hook plugins) for `review-bridge`
