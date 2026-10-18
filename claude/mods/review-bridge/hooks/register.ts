import type { Register } from 'claude-code'

import { createBridge } from './bridge'

const POLL_MS = 1000

export const register: Register = on => {
  let bridge: ReturnType<typeof createBridge> | undefined
  let root = ''

  on('session.start', async ($, e, next) => {
    root = await $.session.root()
    const isEnabled = (await $.store.get(`enabled:${root}`)) !== false
    bridge = createBridge({
      stat: path => $.fs.stat(path).then(s => s.mtimeMs, () => undefined),
      read: async path => String(await $.fs.read(path)),
      write: (path, text) => $.fs.write(path, text),
      now: () => $.clock.now(),
      after: (ms, fn) => $.clock.after(ms, fn),
      submit: text => $.prompt.submit({ text }),
      status: text => $.ui.status(text),
    }, root, isEnabled)
    await $.command.register({
      name: 'review-auto',
      description: 'Hand new review comments from .ai-review-comments.json to Claude automatically',
      argumentHint: '[on|off]',
      immediate: true,
    })
    await bridge.start().catch(() => undefined)
    $.clock.every(POLL_MS, () => {
      void bridge?.tick().catch(() => undefined)
    })
    return next(e)
  })

  on('command.run', { command: 'review-auto' }, async ($, e) => {
    if (!bridge) return { text: 'review-bridge ещё не запущен.' }
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
    return next(e)
  })
}
