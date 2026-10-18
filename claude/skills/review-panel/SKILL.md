---
name: review-panel
description: Открыть панель ревью ветки во встроенном браузере Claude Desktop — diff текущей ветки против main по одному файлу, Unified/Split, пресеты «что показывать» (только код / все файлы), комментарии к строкам старой или новой версии для Claude. Использовать, когда пользователь вызывает /review-panel или просит открыть панель ревью / diff ветки.
allowed-tools: Bash, Read, Write, Edit, mcp__Claude_Browser__preview_start, mcp__Claude_Browser__preview_stop, mcp__Claude_Browser__preview_list
---

# Панель ревью ветки

Сервер — `~/.claude/scripts/ai_review_web.py`, рядом страница `ai_review_web.html` и пресеты
`ai_review_web.presets.json` (общие для всех проектов, правятся в самой панели). Сравнивается merge-base с
`main`/`master` и рабочая копия; слева можно выбрать отдельный коммит или только незакоммиченное.

Комментарии пишутся в `.ai-review-comments.json` проекта в формате плагина YVTodoForAI (`diffRange`,
`diffSnippet`, `revision`: `null` — новая версия в рабочей копии, база — старая). Их обрабатывают
`/review_comments` (пачкой) и `/review_live` (сразу, в панели горит «Claude live»).

Аргументы `$ARGUMENTS` (необязательно) передай серверу как есть, например `--base develop`.

## Шаги

1. Порт: 7790. Проверь `curl -s -m 1 http://127.0.0.1:7790/api/info`: ответ с другим `project` — порт занят панелью
   другого проекта, бери следующий (7791, 7792…), пока порт свободен или отвечает этот проект.
2. `.claude/launch.json` в корне проекта должен содержать конфигурацию:
   ```json
   {
     "name": "code-review",
     "runtimeExecutable": "python",
     "runtimeArgs": ["<абсолютный путь к ~/.claude/scripts/ai_review_web.py, прямые слэши>", "--port", "<порт>"],
     "port": <порт>
   }
   ```
   плюс аргументы из `$ARGUMENTS` в конце `runtimeArgs`. Нет файла — создай (`"version": "0.0.1"`,
   `"configurations": [...]`). Есть файл — добавь или обнови только `code-review`, остальное не трогай.
3. Чтобы `launch.json` не попадал в git: если `git ls-files --error-unmatch .claude/launch.json` и
   `git check-ignore -q .claude/launch.json` оба завершаются ошибкой — допиши строку `**/.claude/launch.json` в
   `$(git rev-parse --git-path info/exclude)`.
4. `preview_start` с `name: "code-review"`. Если сервер уже запущен в этой сессии, а `launch.json` менялся —
   сначала `preview_stop`.
5. Скажи пользователю одной-двумя фразами, что панель открыта и что комментарии подхватывают `/review_live` или
   `/review_comments`.

## Правила

- Никогда не коммить и не пушь.
- Не правь `.ai-review-comments.json` руками — только через панель или `~/.claude/scripts/ai_review.py`.
