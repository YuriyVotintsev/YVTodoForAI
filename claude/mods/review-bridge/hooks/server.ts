import type { EngineInterface } from 'claude-code'

const READY = 'AI review web:'
export const READY_MS = 15000

type Child = { port: number; key: string; ready: Promise<string | undefined>; done: Promise<void>; stop: () => void }

export type ReviewServer = {
  port: () => number | undefined
  ensure: (port: number, argv: string[], cwd: string) => Promise<string | undefined>
  stop: () => Promise<void>
}

const tail = (text: string) => text.trim().split(/\r?\n/).slice(-3).join(' ').slice(-400)
const because = (err: string) => (err.trim() ? `: ${tail(err)}` : '')

export function createReviewServer($: EngineInterface): ReviewServer {
  let child: Child | undefined

  function start(port: number, argv: string[], cwd: string): Child {
    const stream = $.process.spawn({ argv, cwd })
    let out = ''
    let err = ''
    let isReady = false
    let settle: (failure: string | undefined) => void = () => undefined
    const ready = new Promise<string | undefined>(resolve => { settle = resolve })
    const stop = () => void stream.return({ code: null, signal: null }).catch(() => undefined)
    const timer = $.clock.after(READY_MS, () => {
      if (isReady) return
      settle(`сервер панели не ответил за ${READY_MS / 1000} с${because(err)}`)
      stop()
    })
    const self: Child = { port, key: argv.join('\0'), ready, done: Promise.resolve(), stop }
    self.done = (async () => {
      try {
        for await (const piece of stream) {
          if (isReady) continue
          if (piece.stream === 'stderr') err = (err + piece.text).slice(-4000)
          else out += piece.text
          if (out.includes(READY)) {
            isReady = true
            settle(undefined)
          }
        }
        const end = await stream.result
        settle(`сервер панели завершился (код ${end.code ?? end.signal})${because(err)}`)
      } catch (error) {
        settle(`сервер панели не запустился: ${error instanceof Error ? error.message : String(error)}`)
      } finally {
        timer.cancel()
        if (child === self) child = undefined
      }
    })()
    return self
  }

  return {
    port: () => child?.port,
    async ensure(port, argv, cwd) {
      const old = child
      if (old && old.key !== argv.join('\0')) {
        old.stop()
        await old.done
      }
      child ??= start(port, argv, cwd)
      return child.ready
    },
    async stop() {
      const old = child
      old?.stop()
      await old?.done
    },
  }
}
