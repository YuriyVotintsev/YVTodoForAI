# YVTodoForAI

A JetBrains Rider/IntelliJ plugin for creating code review comments that AI assistants can read and process.

## Features

- **Add comments to code** - Select code and press `Ctrl+Shift+R` to add a review comment
- **General comments** - Add comments not attached to specific code (with optional context like error logs/stack traces)
- **Tool Window** - View, filter, edit, and manage all comments
- **Code highlighting** - Comments are highlighted in the editor with tooltips
- **AI-friendly JSON format** - Comments are stored in `.ai-review-comments.json` in your project root

## How It Works

1. Select code in the editor and press `Ctrl+Shift+R` (or use the action "Add AI Review Comment")
2. Enter your comment describing what needs to be done
3. The plugin saves the comment to `.ai-review-comments.json`
4. AI assistants (like Claude Code) can read this file and process the comments
5. When AI completes a task, it marks the comment as DONE

## Comment Statuses

- **PENDING** - Waiting for AI to process
- **HAS_QUESTIONS** - AI needs clarification (has questions in the JSON)
- **DONE** - Task completed
- **CANCELED** - Task canceled/postponed

## AI Workflow

The JSON file contains instructions for AI assistants:

1. Read PENDING comments and try to fix the code
2. If unclear - add questions and set status to "HAS_QUESTIONS"
3. When questions are answered - fix the code and set status to "DONE"

## Installation

1. Build the plugin: `./gradlew build`
2. Install from disk: Settings → Plugins → Install Plugin from Disk
3. Select `build/distributions/YVTodoForAI-*.zip`

## Requirements

- IntelliJ IDEA 2024.2+ or JetBrains Rider 2024.2+
- Java 21+

## License

MIT
