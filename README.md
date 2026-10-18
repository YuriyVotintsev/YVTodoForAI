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
- `skills/review-panel/SKILL.md` – `/review-panel`: starts the server through the project's `.claude/launch.json`
  (created and excluded from git on the first run) and opens the panel.
- `mods/review-bridge/` – a Claude Code mod: watches `.ai-review-comments.json` and hands every new comment, reply,
  edit or answer to Claude in the running session within seconds (no watcher to restart), lights "Claude live" in the
  panel and counts open comments in the status line. `/review-auto on|off` switches it per project;
  `claude plugin test claude/mods/review-bridge` runs its tests.

Install:

- copy `claude/commands/*.md` to `~/.claude/commands/`, `claude/scripts/*` to `~/.claude/scripts/` and
  `claude/skills/review-panel/` to `~/.claude/skills/review-panel/`;
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
