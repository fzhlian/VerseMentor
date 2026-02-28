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
import { normalizeZh } from '../utils/zh_normalize'

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
  hintOfferSince?: number
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
  | { type: 'USER_ASR'; text: string; isFinal: boolean; confidence?: number; now?: number }
  | { type: 'USER_ASR_ERROR'; code: number; message: string }
  | { type: 'TICK'; now: number }
  | { type: 'EV_VARIANTS_FETCH_DONE'; entry: PoemVariantsCacheEntry | null }
  | { type: 'USER_UI_START'; now?: number }
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

function buildRepeatReply(stateType: SessionStateType, ctx: SessionContext): string | undefined {
  switch (stateType) {
    case 'WAIT_POEM_NAME':
      return '你好，欢迎背诵诗词。请说出诗词题目。'
    case 'CONFIRM_POEM_CANDIDATE':
      return ctx.selectedPoem ? `你说的是《${ctx.selectedPoem.title}》吗？` : '请再说一次题目。'
    case 'WAIT_DYNASTY_AUTHOR':
      return ctx.selectedPoem
        ? `已选择《${ctx.selectedPoem.title}》。请说出朝代和作者。`
        : '请再说一次朝代和作者。'
    case 'RECITE_READY':
      return '请背诵第一句。'
    case 'RECITING':
    case 'HINT_GIVEN':
      return '请继续背诵当前句。'
    case 'HINT_OFFER':
      return '需要提示吗？'
    case 'FINISHED':
      return '还要再来一首吗？'
    default:
      return undefined
  }
}

const REJECT_POEM_KEYWORDS = ['不是', '不对', '不要', '错了', '换一个']
const QUESTION_MARKERS = ['吗', '嘛', '么', '呢']
const CONFIRM_POEM_EXACT = new Set([
  '是',
  '是的',
  '对',
  '对的',
  '好的',
  '好',
  '确认',
  '就是它',
  '没错',
  '就是这首',
  '是这首',
  '就这首'
])

function normalizeIntentText(raw: string): string {
  return normalizeZh(raw).replace(/[\p{P}\p{S}\s]+/gu, '')
}

function isRejectPoemUtterance(raw: string): boolean {
  const normalized = normalizeIntentText(raw)
  if (!normalized) return false
  return REJECT_POEM_KEYWORDS.some((keyword) => normalized.includes(keyword))
}

function isConfirmPoemUtterance(raw: string): boolean {
  const trimmedRaw = raw.trim()
  const normalized = normalizeIntentText(raw)
  if (!normalized) return false
  if (isRejectPoemUtterance(normalized)) return false
  if (trimmedRaw.includes('?') || trimmedRaw.includes('？')) return false
  if (CONFIRM_POEM_EXACT.has(normalized)) return true
  if (QUESTION_MARKERS.some((marker) => normalized.includes(marker))) return false
  return normalized.includes('就是这首') ||
    normalized.includes('确认这首') ||
    normalized.includes('是这首') ||
    normalized.includes('就这首')
}

function isSelectedPoemDynastyAuthorMatch(
  spokenText: string,
  poem: Poem,
  dynastyResolved: DynastyResolveResult,
  authorResolved: AuthorResolveResult
): boolean {
  const normalizedSpoken = normalizeZh(spokenText).replace(/[\p{P}\p{S}\s]+/gu, '')
  const normalizedDynasty = normalizeZh(poem.dynasty).replace(/[\p{P}\p{S}\s]+/gu, '')
  const normalizedAuthor = normalizeZh(poem.author).replace(/[\p{P}\p{S}\s]+/gu, '')
  if (
    normalizedSpoken &&
    normalizedDynasty &&
    normalizedAuthor &&
    normalizedSpoken.includes(normalizedDynasty) &&
    normalizedSpoken.includes(normalizedAuthor)
  ) {
    return true
  }

  const resolvedDynasty = dynastyResolved.canonical?.name
  const resolvedAuthor = authorResolved.author?.name
  if (!resolvedDynasty || !resolvedAuthor) return false
  return (
    normalizeZh(resolvedDynasty) === normalizeZh(poem.dynasty) &&
    normalizeZh(resolvedAuthor) === normalizeZh(poem.author)
  )
}

export function sessionReducer(state: SessionState, event: SessionEvent): SessionOutput {
  const ctx = state.ctx
  const actions: SessionAction[] = []

  const withState = (type: SessionStateType, nextCtx: SessionContext = ctx): SessionOutput => ({
    state: { type, ctx: nextCtx },
    actions
  })
  const resetPoemSelection = (baseCtx: SessionContext): SessionContext => ({
    ...baseCtx,
    selectedPoem: undefined,
    dynastyResolved: undefined,
    authorResolved: undefined,
    variantsCacheEntry: null,
    currentLineIdx: 0
  })

  const reduceRecitingAsr = (recitingCtx: SessionContext, text: string, now?: number): SessionOutput => {
    const poem = recitingCtx.selectedPoem
    if (!poem) return withState('EXIT', recitingCtx)

    const activeNow = now ?? Date.now()
    const pack = buildLinePack(poem, recitingCtx.currentLineIdx, recitingCtx.variantsCacheEntry)
    const judged = judgeLine(text, pack, recitingCtx.config)
    const nextProgress = [
      ...recitingCtx.reciteProgress,
      { idx: recitingCtx.currentLineIdx, passed: judged.passed, score: judged.score }
    ]
    const nextCtx = {
      ...recitingCtx,
      reciteProgress: nextProgress,
      lastUserActiveAt: activeNow
    }

    if (judged.passed) {
      const nextIdx = recitingCtx.currentLineIdx + 1
      if (nextIdx >= poem.lines.length) {
        const { praise, nextHistory } = updatePraiseHistory(recitingCtx)
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

    const hintIntent = parseIntent(text)
    if (hintIntent.type === IntentType.ASK_HINT) {
      const hint = makeHint(poem, recitingCtx.currentLineIdx)
      actions.push({ type: 'SPEAK', text: hint })
      actions.push({ type: 'START_LISTENING' })
      return withState('HINT_GIVEN', nextCtx)
    }

    actions.push({ type: 'SPEAK', text: '没关系，再试一次。' })
    actions.push({ type: 'START_LISTENING' })
    return withState('RECITING', nextCtx)
  }

  if (event.type === 'USER_ASR_ERROR') {
    if (state.type === 'IDLE' || state.type === 'EXIT') {
      return withState(state.type)
    }
    actions.push({ type: 'SPEAK', text: `语音识别异常：${event.message}。请再说一次。` })
    actions.push({ type: 'START_LISTENING' })
    return withState(state.type)
  }

  if (event.type === 'USER_UI_STOP' && state.type !== 'IDLE' && state.type !== 'EXIT') {
    actions.push({ type: 'STOP_LISTENING' })
    return withState('EXIT')
  }

  if (event.type === 'USER_UI_START') {
    const baseTime = event.now
      ?? (state.type === 'IDLE' && ctx.lastUserActiveAt !== undefined
        ? ctx.lastUserActiveAt
        : Date.now())
    const nextCtx = {
      ...ctx,
      selectedPoem: undefined,
      dynastyResolved: undefined,
      authorResolved: undefined,
      variantsCacheEntry: null,
      currentLineIdx: 0,
      reciteProgress: [],
      timers: { noPoemIntentSince: baseTime }
    }
    actions.push({ type: 'SPEAK', text: '你好，欢迎背诵诗词。请说出诗词题目。' })
    actions.push({ type: 'START_LISTENING' })
    return withState('WAIT_POEM_NAME', nextCtx)
  }

  if (
    event.type === 'USER_ASR' &&
    event.isFinal &&
    state.type !== 'IDLE' &&
    state.type !== 'EXIT'
  ) {
    const intent = parseIntent(event.text)
    if (intent.type === IntentType.EXIT_SESSION) {
      actions.push({ type: 'SPEAK', text: '好的，已结束会话。' })
      actions.push({ type: 'STOP_LISTENING' })
      return withState('EXIT')
    }
    if (intent.type === IntentType.REPEAT_PROMPT) {
      const reply = buildRepeatReply(state.type, ctx)
      if (reply) {
        actions.push({ type: 'SPEAK', text: reply })
        actions.push({ type: 'START_LISTENING' })
        return withState(state.type)
      }
    }
  }

  switch (state.type) {
    case 'IDLE':
      return withState('IDLE')

    case 'WAIT_POEM_NAME':
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
        if (intent.type === IntentType.REJECT_POEM || isRejectPoemUtterance(event.text)) {
          const nextCtx = resetPoemSelection(ctx)
          actions.push({ type: 'SPEAK', text: '好的，请再说一次题目。' })
          actions.push({ type: 'START_LISTENING' })
          return withState('WAIT_POEM_NAME', nextCtx)
        }
        if (intent.type === IntentType.SET_POEM || isConfirmPoemUtterance(event.text)) {
          const poem = ctx.selectedPoem
          if (poem) {
            actions.push({ type: 'SPEAK', text: `已选择《${poem.title}》。请说出朝代和作者。` })
            actions.push({ type: 'FETCH_VARIANTS', poem: { title: poem.title, author: poem.author, dynasty: poem.dynasty } })
            actions.push({ type: 'START_LISTENING' })
            return withState('WAIT_DYNASTY_AUTHOR')
          }
        }
        const nextCtx = resetPoemSelection(ctx)
        actions.push({ type: 'SPEAK', text: '没有确认到题目，请再说一次题目。' })
        actions.push({ type: 'START_LISTENING' })
        return withState('WAIT_POEM_NAME', nextCtx)
      }
      return withState('CONFIRM_POEM_CANDIDATE')

    case 'WAIT_DYNASTY_AUTHOR':
      if (event.type === 'EV_VARIANTS_FETCH_DONE') {
        const nextCtx = { ...ctx, variantsCacheEntry: event.entry }
        return withState('WAIT_DYNASTY_AUTHOR', nextCtx)
      }
      if (event.type === 'USER_ASR' && event.isFinal) {
        const selectedPoem = ctx.selectedPoem
        const dynastyResolved = resolveDynastySpoken(ctx.dynastyKB, event.text)
        const dynastyIds = dynastyResolved.canonical ? [dynastyResolved.canonical.id] : undefined
        const authorResolved = resolveAuthorSpoken(ctx.authorKB, event.text, dynastyIds)
        const nextCtx = { ...ctx, dynastyResolved, authorResolved }
        if (
          selectedPoem &&
          isSelectedPoemDynastyAuthorMatch(event.text, selectedPoem, dynastyResolved, authorResolved)
        ) {
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
      const nextCtx = { ...ctx, currentLineIdx: 0, lastUserActiveAt: Date.now() }
      if (event.type === 'USER_ASR' && event.isFinal) {
        return reduceRecitingAsr(nextCtx, event.text, event.now)
      }
      actions.push({ type: 'SPEAK', text: `请背诵第一句。` })
      actions.push({ type: 'START_LISTENING' })
      return withState('RECITING', nextCtx)
    }

    case 'RECITING': {
      if (event.type === 'TICK' && ctx.lastUserActiveAt !== undefined) {
        const silence = event.now - ctx.lastUserActiveAt
        if (silence >= ctx.config.timeouts.reciteSilenceAskHintSec * 1000) {
          const nextCtx = {
            ...ctx,
            timers: { ...ctx.timers, hintOfferSince: event.now }
          }
          actions.push({ type: 'SPEAK', text: '需要提示吗？' })
          actions.push({ type: 'START_LISTENING' })
          return withState('HINT_OFFER', nextCtx)
        }
      }
      if (event.type === 'USER_ASR' && event.isFinal) {
        return reduceRecitingAsr(ctx, event.text, event.now)
      }
      return withState('RECITING')
    }

    case 'HINT_OFFER':
      if (event.type === 'TICK') {
        const since = ctx.timers.hintOfferSince ?? event.now
        if (event.now - since >= ctx.config.timeouts.hintOfferWaitSec * 1000) {
          const nextCtx = {
            ...ctx,
            timers: { ...ctx.timers, hintOfferSince: undefined }
          }
          actions.push({ type: 'SPEAK', text: '好的，继续背诵。' })
          actions.push({ type: 'START_LISTENING' })
          return withState('RECITING', nextCtx)
        }
        if (ctx.timers.hintOfferSince === undefined) {
          const nextCtx = {
            ...ctx,
            timers: { ...ctx.timers, hintOfferSince: event.now }
          }
          return withState('HINT_OFFER', nextCtx)
        }
      }
      if (event.type === 'USER_ASR' && event.isFinal) {
        const intent = parseIntent(event.text)
        const nextCtx = {
          ...ctx,
          timers: { ...ctx.timers, hintOfferSince: undefined }
        }
        if (intent.type === IntentType.ASK_HINT) {
          const poem = ctx.selectedPoem
          if (!poem) return withState('EXIT')
          const hint = makeHint(poem, ctx.currentLineIdx)
          actions.push({ type: 'SPEAK', text: hint })
          actions.push({ type: 'START_LISTENING' })
          return withState('HINT_GIVEN', nextCtx)
        }
        actions.push({ type: 'SPEAK', text: '好的，继续。' })
        actions.push({ type: 'START_LISTENING' })
        return withState('RECITING', nextCtx)
      }
      return withState('HINT_OFFER')

    case 'HINT_GIVEN':
      if (event.type === 'USER_ASR' && event.isFinal) {
        return reduceRecitingAsr(ctx, event.text, event.now)
      }
      return withState('HINT_GIVEN')

    case 'FINISHED':
      if (event.type === 'USER_ASR' && event.isFinal) {
        const intent = parseIntent(event.text)
        if (intent.type === IntentType.NEXT_POEM || intent.type === IntentType.START_RECITE) {
          actions.push({ type: 'SPEAK', text: '好的，请说出下一首诗的题目。' })
          actions.push({ type: 'START_LISTENING' })
          const nextCtx = {
            ...resetPoemSelection(ctx),
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
