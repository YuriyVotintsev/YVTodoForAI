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
  as it appears.
- `scripts/ai_review.py` – the helper both commands use (`open`, `show`, `status`, `say`, `watch`).

Install: copy `claude/commands/*.md` to `~/.claude/commands/` and `claude/scripts/ai_review.py` to
`~/.claude/scripts/`. The plugin never starts Claude itself; the commands are run by hand in a Claude Code session.

## Build

```
build.bat
```

Uses the locally installed Rider (`ideLocalPath` in `gradle.properties`), otherwise downloads Rider 2025.3.1. Install
the zip from `build/distributions` via Settings → Plugins → ⚙ → Install Plugin from Disk.

## Requirements

- JetBrains Rider 2025.3+
- git in PATH
- Python 3 for `claude/scripts/ai_review.py`
