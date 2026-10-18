export type ReviewSummary = { text: string }

declare module 'claude-code' {
  interface PluginState {
    'review-bridge': { summary: ReviewSummary | null; isOpening: boolean }
  }
}
