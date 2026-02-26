import { AppConfig, DEFAULTS } from '../config/defaults'
import { PoemIndex } from '../data/poem_index'
import { Poem } from '../data/models'
import { parseIntent, IntentType } from '../nlu/intent'
import { matchPoemTitle } from '../nlu/poem_title_matcher'
import { PoemVariantsCacheEntry } from '../ports/storage'
import { judgeLine } from '../recite/matcher'
import { AuthorKB, AuthorResolveResult, resolveAuthorSpoken } from '../kb/author_kb'
import { DynastyKB, DynastyResolveResult, resolveDynastySpoken } from '../kb/dynasty_kb'
import { PRAISE_TEMPLATES, PraisePicker } from '../persona/praise'

export type SessionStateType =
  | 'IDLE'
  | 'SESSION_START'
  | 'WAIT_POEM_NAME'
  | 'CONFIRM_POEM_CANDIDATE'
  | 'WAIT_DYNASTY_AUTHOR'
  | 'RECITE_READY'
  | 'RECITING'
  | 'HINT_OFFER'
  | 'HINT_GIVEN'
  | 'FINISHED'
  | 'EXIT'

export interface SessionTimers {
  noPoemIntentSince?: number
}

export interface ReciteProgressEntry {
  idx: number
  passed: boolean
  score: number
}

export interface SessionContext {
  config: AppConfig
  poemIndex: PoemIndex
  poems: Poem[]
  dynastyKB: DynastyKB
  authorKB: AuthorKB
  selectedPoem?: Poem
  dynastyResolved?: DynastyResolveResult
  authorResolved?: AuthorResolveResult
  variantsCacheEntry?: PoemVariantsCacheEntry | null
  currentLineIdx: number
  reciteProgress: ReciteProgressEntry[]
  lastUserActiveAt?: number
  timers: SessionTimers
  praiseHistory: string[]
}

export interface SessionState {
  type: SessionStateType
  ctx: SessionContext
}

export type SessionEvent =
  | { type: 'USER_ASR'; text: string; isFinal: boolean; confidence?: number }
  | { type: 'TICK'; now: number }
  | { type: 'EV_VARIANTS_FETCH_DONE'; entry: PoemVariantsCacheEntry | null }
  | { type: 'USER_UI_START' }
  | { type: 'USER_UI_STOP' }

export type SessionAction =
  | { type: 'SPEAK'; text: string }
  | { type: 'START_LISTENING' }
  | { type: 'STOP_LISTENING' }
  | { type: 'UPDATE_SCREEN_HINT'; key: string }
  | { type: 'FETCH_VARIANTS'; poem: { title: string; author?: string; dynasty?: string } }

export interface SessionOutput {
  state: SessionState
  actions: SessionAction[]
}

export function createInitialSession(ctx: Omit<SessionContext, 'currentLineIdx' | 'reciteProgress' | 'timers' | 'praiseHistory'>): SessionState {
  return {
    type: 'IDLE',
    ctx: {
      ...ctx,
      currentLineIdx: 0,
      reciteProgress: [],
      timers: {},
      praiseHistory: []
    }
  }
}

function updatePraiseHistory(ctx: SessionContext): { praise: string; nextHistory: string[] } {
  const picker = new PraisePicker(PRAISE_TEMPLATES)
  picker.restore(ctx.praiseHistory)
  const praise = picker.next()
  return { praise, nextHistory: picker.snapshot() }
}

function buildLinePack(poem: Poem, idx: number, variants?: PoemVariantsCacheEntry | null) {
  const base = poem.lines[idx]?.text ?? ''
  const online = variants?.variants.lines.find((l) => l.lineIndex === idx)?.variants
  return {
    idx,
    baseText: base,
    onlineVariants: online
  }
}

function makeHint(poem: Poem, idx: number): string {
  const line = poem.lines[idx]
  if (!line) return '我没听清，请再试一次。'
  if (line.meaning) {
    return `提示：${line.meaning}`
  }
  const first = line.text.slice(0, 1)
  const next = line.text.slice(0, 2)
  return `提示：${first}… 或 ${next}…`
}

export function sessionReducer(state: SessionState, event: SessionEvent): SessionOutput {
  const ctx = state.ctx
  const actions: SessionAction[] = []

  const withState = (type: SessionStateType, nextCtx: SessionContext = ctx): SessionOutput => ({
    state: { type, ctx: nextCtx },
    actions
  })

  switch (state.type) {
    case 'IDLE':
      if (event.type === 'USER_UI_START') {
        actions.push({ type: 'SPEAK', text: '你好，欢迎背诵诗词。请说出诗词题目。' })
        actions.push({ type: 'START_LISTENING' })
        const baseTime = ctx.lastUserActiveAt ?? Date.now()
        const nextCtx = { ...ctx, timers: { noPoemIntentSince: baseTime } }
        return withState('WAIT_POEM_NAME', nextCtx)
      }
      return withState('IDLE')

    case 'WAIT_POEM_NAME':
      if (event.type === 'USER_UI_STOP') {
        actions.push({ type: 'STOP_LISTENING' })
        return withState('EXIT')
      }
      if (event.type === 'TICK') {
        if (!ctx.timers.noPoemIntentSince) {
          const nextCtx = { ...ctx, timers: { ...ctx.timers, noPoemIntentSince: event.now } }
          return withState('WAIT_POEM_NAME', nextCtx)
        }
        if (ctx.timers.noPoemIntentSince &&
            event.now - ctx.timers.noPoemIntentSince >= ctx.config.timeouts.noPoemIntentExitSec * 1000) {
          actions.push({ type: 'SPEAK', text: '暂时没有识别到诗词题目，先结束会话。' })
          return withState('EXIT')
        }
        return withState('WAIT_POEM_NAME')
      }
      if (event.type === 'USER_ASR' && event.isFinal) {
        const intent = parseIntent(event.text)
        const raw = intent.slots.title ?? event.text
        const match = matchPoemTitle(ctx.poemIndex, raw)
        if (match.candidates.length === 0) {
          actions.push({ type: 'SPEAK', text: '没有找到这首诗，请再说一次题目。' })
          actions.push({ type: 'START_LISTENING' })
          return withState('WAIT_POEM_NAME')
        }
        const top = match.candidates[0]
        const confirmed = top.score >= 0.9
        if (confirmed) {
          const nextCtx = { ...ctx, selectedPoem: top.poem }
          actions.push({ type: 'SPEAK', text: `已选择《${top.poem.title}》。请说出朝代和作者。` })
          actions.push({ type: 'FETCH_VARIANTS', poem: { title: top.poem.title, author: top.poem.author, dynasty: top.poem.dynasty } })
          actions.push({ type: 'START_LISTENING' })
          return withState('WAIT_DYNASTY_AUTHOR', nextCtx)
        }
        const nextCtx = { ...ctx, selectedPoem: top.poem }
        actions.push({ type: 'SPEAK', text: `你说的是《${top.poem.title}》吗？` })
        actions.push({ type: 'START_LISTENING' })
        return withState('CONFIRM_POEM_CANDIDATE', nextCtx)
      }
      return withState('WAIT_POEM_NAME')

    case 'CONFIRM_POEM_CANDIDATE':
      if (event.type === 'USER_ASR' && event.isFinal) {
        const intent = parseIntent(event.text)
        if (intent.type === IntentType.REJECT_POEM) {
          actions.push({ type: 'SPEAK', text: '好的，请再说一次题目。' })
          actions.push({ type: 'START_LISTENING' })
          return withState('WAIT_POEM_NAME')
        }
        if (intent.type === IntentType.SET_POEM || event.text.includes('是')) {
          const poem = ctx.selectedPoem
          if (poem) {
            actions.push({ type: 'SPEAK', text: `已选择《${poem.title}》。请说出朝代和作者。` })
            actions.push({ type: 'FETCH_VARIANTS', poem: { title: poem.title, author: poem.author, dynasty: poem.dynasty } })
            actions.push({ type: 'START_LISTENING' })
            return withState('WAIT_DYNASTY_AUTHOR')
          }
        }
        actions.push({ type: 'SPEAK', text: '没有确认到题目，请再说一次题目。' })
        actions.push({ type: 'START_LISTENING' })
        return withState('WAIT_POEM_NAME')
      }
      return withState('CONFIRM_POEM_CANDIDATE')

    case 'WAIT_DYNASTY_AUTHOR':
      if (event.type === 'EV_VARIANTS_FETCH_DONE') {
        const nextCtx = { ...ctx, variantsCacheEntry: event.entry }
        return withState('WAIT_DYNASTY_AUTHOR', nextCtx)
      }
      if (event.type === 'USER_ASR' && event.isFinal) {
        const dynastyResolved = resolveDynastySpoken(ctx.dynastyKB, event.text)
        const dynastyIds = dynastyResolved.canonical ? [dynastyResolved.canonical.id] : undefined
        const authorResolved = resolveAuthorSpoken(ctx.authorKB, event.text, dynastyIds)
        const nextCtx = { ...ctx, dynastyResolved, authorResolved }
        if (dynastyResolved.canonical && authorResolved.author) {
          actions.push({ type: 'SPEAK', text: '好的，开始背诵。' })
          actions.push({ type: 'START_LISTENING' })
          return withState('RECITE_READY', nextCtx)
        }
        actions.push({ type: 'SPEAK', text: '请再说一次朝代和作者。' })
        actions.push({ type: 'START_LISTENING' })
        return withState('WAIT_DYNASTY_AUTHOR', nextCtx)
      }
      return withState('WAIT_DYNASTY_AUTHOR')

    case 'RECITE_READY': {
      const poem = ctx.selectedPoem
      if (!poem) return withState('EXIT')
      const nextCtx = { ...ctx, currentLineIdx: 0 }
      actions.push({ type: 'SPEAK', text: `请背诵第一句。` })
      actions.push({ type: 'START_LISTENING' })
      return withState('RECITING', nextCtx)
    }

    case 'RECITING': {
      if (event.type === 'TICK' && ctx.lastUserActiveAt !== undefined) {
        const silence = event.now - ctx.lastUserActiveAt
        if (silence >= ctx.config.timeouts.reciteSilenceAskHintSec * 1000) {
          actions.push({ type: 'SPEAK', text: '需要提示吗？' })
          actions.push({ type: 'START_LISTENING' })
          return withState('HINT_OFFER')
        }
      }
      if (event.type === 'USER_ASR' && event.isFinal) {
        ctx.lastUserActiveAt = Date.now()
        const poem = ctx.selectedPoem
        if (!poem) return withState('EXIT')

        const pack = buildLinePack(poem, ctx.currentLineIdx, ctx.variantsCacheEntry)
        const judged = judgeLine(event.text, pack, ctx.config)
        const nextProgress = [...ctx.reciteProgress, { idx: ctx.currentLineIdx, passed: judged.passed, score: judged.score }]
        const nextCtx = { ...ctx, reciteProgress: nextProgress }

        if (judged.passed) {
          const nextIdx = ctx.currentLineIdx + 1
          if (nextIdx >= poem.lines.length) {
            const { praise, nextHistory } = updatePraiseHistory(ctx)
            actions.push({ type: 'SPEAK', text: `${praise} 还要再来一首吗？` })
            return withState('FINISHED', { ...nextCtx, praiseHistory: nextHistory })
          }
          actions.push({ type: 'SPEAK', text: '很好，下一句。' })
          actions.push({ type: 'START_LISTENING' })
          return withState('RECITING', { ...nextCtx, currentLineIdx: nextIdx })
        }

        if (judged.partial) {
          actions.push({ type: 'SPEAK', text: '接近了，再试一次。' })
          actions.push({ type: 'START_LISTENING' })
          return withState('RECITING', nextCtx)
        }

        const hintIntent = parseIntent(event.text)
        if (hintIntent.type === IntentType.ASK_HINT) {
          const hint = makeHint(poem, ctx.currentLineIdx)
          actions.push({ type: 'SPEAK', text: hint })
          actions.push({ type: 'START_LISTENING' })
          return withState('HINT_GIVEN', nextCtx)
        }

        actions.push({ type: 'SPEAK', text: '没关系，再试一次。' })
        actions.push({ type: 'START_LISTENING' })
        return withState('RECITING', nextCtx)
      }
      return withState('RECITING')
    }

    case 'HINT_OFFER':
      if (event.type === 'USER_ASR' && event.isFinal) {
        const intent = parseIntent(event.text)
        if (intent.type === IntentType.ASK_HINT) {
          const poem = ctx.selectedPoem
          if (!poem) return withState('EXIT')
          const hint = makeHint(poem, ctx.currentLineIdx)
          actions.push({ type: 'SPEAK', text: hint })
          actions.push({ type: 'START_LISTENING' })
          return withState('HINT_GIVEN')
        }
        actions.push({ type: 'SPEAK', text: '好的，继续。' })
        actions.push({ type: 'START_LISTENING' })
        return withState('RECITING')
      }
      return withState('HINT_OFFER')

    case 'HINT_GIVEN':
      if (event.type === 'USER_ASR' && event.isFinal) {
        return withState('RECITING')
      }
      return withState('HINT_GIVEN')

    case 'FINISHED':
      if (event.type === 'USER_ASR' && event.isFinal) {
        const intent = parseIntent(event.text)
        if (intent.type === IntentType.NEXT_POEM || intent.type === IntentType.START_RECITE) {
          actions.push({ type: 'SPEAK', text: '好的，请说出下一首诗的题目。' })
          actions.push({ type: 'START_LISTENING' })
          const nextCtx = {
            ...ctx,
            selectedPoem: undefined,
            currentLineIdx: 0,
            reciteProgress: [],
            timers: { noPoemIntentSince: Date.now() }
          }
          return withState('WAIT_POEM_NAME', nextCtx)
        }
        actions.push({ type: 'SPEAK', text: '好的，已结束会话。' })
        return withState('EXIT')
      }
      return withState('FINISHED')

    case 'EXIT':
      return withState('EXIT')

    case 'SESSION_START':
      return withState('WAIT_POEM_NAME')

    default:
      return withState('IDLE')
  }
}
