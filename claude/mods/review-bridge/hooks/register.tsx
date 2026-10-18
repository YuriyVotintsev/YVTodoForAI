import { atom, read, update } from 'claude-code'
import type { Register } from 'claude-code'

import type { ReviewSummary } from '../types'
import { createBridge } from './bridge'
import { NOT_STARTED, openReviewPanel, ownPanelVerdict } from './panel'
import { createReviewServer } from './server'
import type { ReviewServer } from './server'
import { ownToolVerdict, runReviewTool, TOOL_SPECS } from './tools'

const POLL_MS = 1000
const summary = atom({ plugin: 'review-bridge', key: 'summary' } as const, null)
const isOpening = atom({ plugin: 'review-bridge', key: 'isOpening' } as const, false)

export const register: Register = on => {
  let bridge: ReturnType<typeof createBridge> | undefined
  let server: ReviewServer | undefined
  let root = ''
  let shown: string | undefined | null = null

  on('session.start', async ($, e, next) => {
    root = await $.session.root()
    server = createReviewServer($)
    const isEnabled = (await $.store.get(`enabled:${root}`)) !== false
    $.ui.status(undefined)
    bridge = createBridge({
      stat: path => $.fs.stat(path).then(s => s.mtimeMs, () => undefined),
      read: async path => String(await $.fs.read(path)),
      write: (path, text) => $.fs.write(path, text),
      now: () => $.clock.now(),
      after: (ms, fn) => $.clock.after(ms, fn),
      submit: text => $.prompt.submit({ text, asUser: true }),
      session: () => $.session.id(),
      status: text => {
        if (text === shown) return
        shown = text
        const value: ReviewSummary | null = text === undefined ? null : { text }
        void update($, summary, () => value)
      },
    }, root, isEnabled)
    await $.command.register({
      name: 'review-auto',
      description: 'Hand new review comments from .ai-review-comments.json to Claude automatically',
      argumentHint: '[on|off]',
      immediate: true,
    })
    await $.command.register({
      name: 'review-panel',
      description: 'Open the branch review panel in the Claude Desktop built-in browser',
      argumentHint: '[--base <branch>]',
      immediate: true,
    })
    for (const spec of TOOL_SPECS) await $.tool.register({ ...spec, isDeferred: false })
    await bridge.start().catch(() => undefined)
    $.clock.every(POLL_MS, () => {
      void bridge?.tick().catch(() => undefined)
    })
    return next(e)
  })

  on('ui.render', { component: 'SessionMode' }, async ($, e, next) => {
    const value = await read($, summary)
    if (!value) return next(e)
    const opening = await read($, isOpening)
    const { Box, Button, Text } = $.ui.resolve(e)
    return (
      <Box>
        {e.props.modes.length > 0 && <Text dimColor>{e.props.modes.join(' & ')} </Text>}
        <Button key="review-open-panel" plain dimColor={opening} onPress={() => undefined}>
          {opening ? 'Review: открываю панель…' : value.text}
        </Button>
      </Box>
    )
  })

  on('ui.press', { plugin: 'review-bridge', element: 'review-open-panel' }, async ($, e) => {
    await update($, isOpening, () => true)
    try {
      const result = await openReviewPanel($, '', server)
      if (result.text) $.ui.toast(result.text)
    } finally {
      await update($, isOpening, () => false)
    }
    return { element: e.element }
  })

  for (const tool of ['mcp__Claude_Browser__preview_start', 'mcp__ccd_view__show_pane'] as const) {
    on('tool.check', { tool }, async ($, e, next) =>
      ownPanelVerdict(e.tool, next.origin?.plugin, $.plugin.name, e.input, await next(e)))
  }

  for (const tool of ['mcp__review-bridge__open', 'mcp__review-bridge__show', 'mcp__review-bridge__status', 'mcp__review-bridge__reply'] as const) {
    on('tool.call', { tool }, async ($, e) => runReviewTool($, tool, e as unknown as Record<string, unknown>))
    on('tool.check', { tool }, async ($, e, next) => ownToolVerdict(await next(e)))
  }

  on('command.run', { command: 'review-panel' }, async ($, e) => {
    const result = await openReviewPanel($, e.args, server)
    return result.text ? { text: result.text } : {}
  })

  on('command.run', { command: 'review-auto' }, async ($, e) => {
    if (!bridge) return { text: NOT_STARTED }
    const arg = e.args.trim().toLowerCase()
    if (arg === 'on' || arg === 'off') {
      await bridge.setEnabled(arg === 'on')
      await $.store.set(`enabled:${root}`, arg === 'on')
    } else if (arg) {
      return { text: `Неизвестный аргумент «${arg}». Используй /review-auto on или /review-auto off.` }
    }
    const state = bridge.isEnabled()
      ? 'включена: новые комментарии и ответы из панели ревью Claude берёт в работу сам'
      : 'выключена: комментарии копятся, обработай их /review_comments'
    return { text: `Авто-обработка review-комментариев ${state}. Открытых сейчас: ${bridge.openCount()}.` }
  })

  on('session.end', async ($, e, next) => {
    await bridge?.stop().catch(() => undefined)
    await server?.stop().catch(() => undefined)
    return next(e)
  })
}
