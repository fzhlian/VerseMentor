import {
  dispatchSessionDriverEvent,
  SerializableSessionState,
  SessionDriverAction,
  SessionDriverEvent,
  SessionDriverOutput
} from './session_driver'

export interface SessionDriverReduceRequest {
  state: SerializableSessionState
  event: SessionDriverEvent
}

export type SessionDriverReduceResponse = SessionDriverOutput

const SESSION_STATE_TYPES = new Set([
  'IDLE',
  'SESSION_START',
  'WAIT_POEM_NAME',
  'CONFIRM_POEM_CANDIDATE',
  'WAIT_DYNASTY_AUTHOR',
  'RECITE_READY',
  'RECITING',
  'HINT_OFFER',
  'HINT_GIVEN',
  'FINISHED',
  'EXIT'
])

function parseJson(raw: string): unknown {
  return JSON.parse(raw) as unknown
}

function isObjectRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function isStringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((item) => typeof item === 'string')
}

function isBoolean(value: unknown): value is boolean {
  return typeof value === 'boolean'
}

function isNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value)
}

function isPoem(value: unknown): boolean {
  if (!isObjectRecord(value)) return false
  if (typeof value.id !== 'string' || typeof value.title !== 'string') return false
  if (typeof value.author !== 'string' || typeof value.dynasty !== 'string') return false
  if (!Array.isArray(value.lines)) return false
  return value.lines.every((line) => isObjectRecord(line) && typeof line.text === 'string')
}

function isPoemLineVariant(value: unknown): boolean {
  return (
    isObjectRecord(value) &&
    isNumber(value.lineIndex) &&
    Array.isArray(value.variants) &&
    value.variants.every((item) => typeof item === 'string')
  )
}

function isPoemVariants(value: unknown): boolean {
  return (
    isObjectRecord(value) &&
    typeof value.poemId === 'string' &&
    Array.isArray(value.lines) &&
    value.lines.every((line) => isPoemLineVariant(line)) &&
    isStringArray(value.sourceTags)
  )
}

function isPoemVariantsCacheEntry(value: unknown): boolean {
  return (
    isObjectRecord(value) &&
    typeof value.poemId === 'string' &&
    isPoemVariants(value.variants) &&
    isNumber(value.cachedAt) &&
    isNumber(value.expiresAt)
  )
}

function isSourceTag(value: unknown): boolean {
  return value === 'auto' || value === 'user' || value === 'import'
}

function isDynastyCanonical(value: unknown): boolean {
  return (
    isObjectRecord(value) &&
    typeof value.id === 'string' &&
    typeof value.name === 'string' &&
    isSourceTag(value.source) &&
    isNumber(value.createdAt) &&
    isNumber(value.updatedAt) &&
    isNumber(value.freq)
  )
}

function isDynastyAlias(value: unknown): boolean {
  return (
    isObjectRecord(value) &&
    typeof value.id === 'string' &&
    typeof value.alias === 'string' &&
    typeof value.canonicalId === 'string' &&
    isSourceTag(value.source) &&
    isNumber(value.createdAt) &&
    isNumber(value.updatedAt) &&
    isNumber(value.freq)
  )
}

function isDynastyGroup(value: unknown): boolean {
  return (
    isObjectRecord(value) &&
    typeof value.id === 'string' &&
    typeof value.name === 'string' &&
    Array.isArray(value.canonicalIds) &&
    value.canonicalIds.every((id) => typeof id === 'string') &&
    isSourceTag(value.source) &&
    isNumber(value.createdAt) &&
    isNumber(value.updatedAt) &&
    isNumber(value.freq)
  )
}

function isDynastyKB(value: unknown): boolean {
  return (
    isObjectRecord(value) &&
    Array.isArray(value.canonicals) &&
    value.canonicals.every((item) => isDynastyCanonical(item)) &&
    Array.isArray(value.aliases) &&
    value.aliases.every((item) => isDynastyAlias(item)) &&
    Array.isArray(value.groups) &&
    value.groups.every((item) => isDynastyGroup(item))
  )
}

function isDynastyResolveResult(value: unknown): boolean {
  if (!isObjectRecord(value)) return false
  if (
    value.matchedBy !== 'canonical' &&
    value.matchedBy !== 'alias' &&
    value.matchedBy !== 'group' &&
    value.matchedBy !== 'fuzzy' &&
    value.matchedBy !== 'none'
  ) {
    return false
  }
  if (value.canonical !== undefined && !isDynastyCanonical(value.canonical)) return false
  if (
    value.candidates !== undefined &&
    (!Array.isArray(value.candidates) || !value.candidates.every((item) => isDynastyCanonical(item)))
  ) {
    return false
  }
  if (value.score !== undefined && !isNumber(value.score)) return false
  return true
}

function isAuthorEntry(value: unknown): boolean {
  return (
    isObjectRecord(value) &&
    typeof value.id === 'string' &&
    typeof value.name === 'string' &&
    Array.isArray(value.aliases) &&
    value.aliases.every((alias) => typeof alias === 'string') &&
    (value.dynastyId === undefined || typeof value.dynastyId === 'string') &&
    isSourceTag(value.source) &&
    isNumber(value.createdAt) &&
    isNumber(value.updatedAt) &&
    isNumber(value.freq)
  )
}

function isAuthorResolveResult(value: unknown): boolean {
  if (!isObjectRecord(value)) return false
  if (
    value.matchedBy !== 'primary' &&
    value.matchedBy !== 'alias' &&
    value.matchedBy !== 'fuzzy' &&
    value.matchedBy !== 'none'
  ) {
    return false
  }
  if (value.author !== undefined && !isAuthorEntry(value.author)) return false
  if (
    value.candidates !== undefined &&
    (!Array.isArray(value.candidates) || !value.candidates.every((item) => isAuthorEntry(item)))
  ) {
    return false
  }
  if (value.score !== undefined && !isNumber(value.score)) return false
  if (value.hint !== undefined && typeof value.hint !== 'string') return false
  return true
}

function isAuthorKB(value: unknown): boolean {
  return (
    isObjectRecord(value) &&
    Array.isArray(value.authors) &&
    value.authors.every((item) => isAuthorEntry(item))
  )
}

function isSessionStateType(value: unknown): boolean {
  return typeof value === 'string' && SESSION_STATE_TYPES.has(value)
}

function isAppConfig(value: unknown): boolean {
  if (!isObjectRecord(value)) return false
  if (typeof value.tonePolicy !== 'string') return false
  if (!isBoolean(value.toneRemind)) return false

  if (!isObjectRecord(value.accentTolerance)) return false
  if (
    !isBoolean(value.accentTolerance.an_ang) ||
    !isBoolean(value.accentTolerance.en_eng) ||
    !isBoolean(value.accentTolerance.in_ing) ||
    !isBoolean(value.accentTolerance.ian_iang)
  ) {
    return false
  }

  if (!isObjectRecord(value.variants)) return false
  if (
    !isBoolean(value.variants.enableOnline) ||
    !isNumber(value.variants.ttlDays) ||
    !isObjectRecord(value.variants.providerWeights) ||
    !Object.values(value.variants.providerWeights).every((v) => typeof v === 'number') ||
    !isNumber(value.variants.maxVariantsPerLine)
  ) {
    return false
  }

  if (!isObjectRecord(value.timeouts)) return false
  if (
    !isNumber(value.timeouts.noPoemIntentExitSec) ||
    !isNumber(value.timeouts.reciteSilenceAskHintSec) ||
    !isNumber(value.timeouts.hintOfferWaitSec)
  ) {
    return false
  }

  if (!isObjectRecord(value.recite)) return false
  if (
    !isNumber(value.recite.passScore) ||
    !isNumber(value.recite.partialScore) ||
    !isNumber(value.recite.minCoverage)
  ) {
    return false
  }

  if (!isObjectRecord(value.ui)) return false
  if (!isBoolean(value.ui.followSystem) || typeof value.ui.uiLang !== 'string') return false

  return true
}

function isSessionDriverEvent(value: unknown): value is SessionDriverEvent {
  if (!isObjectRecord(value) || typeof value.type !== 'string') return false
  switch (value.type) {
    case 'USER_UI_START':
      return value.now === undefined || isNumber(value.now)
    case 'USER_UI_STOP':
      return true
    case 'TICK':
      return isNumber(value.now)
    case 'USER_ASR':
      return (
        typeof value.text === 'string' &&
        typeof value.isFinal === 'boolean' &&
        (value.confidence === undefined || isNumber(value.confidence)) &&
        (value.now === undefined || isNumber(value.now))
      )
    case 'USER_ASR_ERROR':
      return isNumber(value.code) && typeof value.message === 'string'
    case 'EV_VARIANTS_FETCH_DONE':
      return value.entry === null || isPoemVariantsCacheEntry(value.entry)
    default:
      return false
  }
}

function isSessionDriverAction(value: unknown): value is SessionDriverAction {
  if (!isObjectRecord(value) || typeof value.type !== 'string') return false
  switch (value.type) {
    case 'START_LISTENING':
    case 'STOP_LISTENING':
      return true
    case 'SPEAK':
      return typeof value.text === 'string'
    case 'UPDATE_SCREEN_HINT':
      return typeof value.key === 'string'
    case 'FETCH_VARIANTS':
      if (!isObjectRecord(value.poem)) return false
      return (
        typeof value.poem.title === 'string' &&
        (value.poem.author === undefined || typeof value.poem.author === 'string') &&
        (value.poem.dynasty === undefined || typeof value.poem.dynasty === 'string')
      )
    default:
      return false
  }
}

function isSerializableSessionState(value: unknown): value is SerializableSessionState {
  if (!isObjectRecord(value) || !isSessionStateType(value.type)) return false
  if (!isObjectRecord(value.ctx)) return false
  const ctx = value.ctx
  if (!isAppConfig(ctx.config)) return false
  if (!Array.isArray(ctx.poems) || !ctx.poems.every((poem) => isPoem(poem))) return false
  if (!isDynastyKB(ctx.dynastyKB)) return false
  if (!isAuthorKB(ctx.authorKB)) return false
  if (!isNumber(ctx.currentLineIdx)) return false
  if (!Array.isArray(ctx.reciteProgress)) return false
  if (
    !ctx.reciteProgress.every(
      (item) =>
        isObjectRecord(item) &&
        isNumber(item.idx) &&
        typeof item.passed === 'boolean' &&
        isNumber(item.score)
    )
  ) {
    return false
  }
  if (ctx.lastUserActiveAt !== undefined && !isNumber(ctx.lastUserActiveAt)) return false
  if (!isObjectRecord(ctx.timers)) return false
  if (ctx.timers.noPoemIntentSince !== undefined && !isNumber(ctx.timers.noPoemIntentSince)) return false
  if (ctx.timers.hintOfferSince !== undefined && !isNumber(ctx.timers.hintOfferSince)) return false
  if (!isStringArray(ctx.praiseHistory)) return false
  if (ctx.selectedPoem !== undefined && !isPoem(ctx.selectedPoem)) return false
  if (
    ctx.variantsCacheEntry !== undefined &&
    ctx.variantsCacheEntry !== null &&
    !isPoemVariantsCacheEntry(ctx.variantsCacheEntry)
  ) {
    return false
  }
  if (ctx.dynastyResolved !== undefined && !isDynastyResolveResult(ctx.dynastyResolved)) return false
  if (ctx.authorResolved !== undefined && !isAuthorResolveResult(ctx.authorResolved)) return false
  return true
}

function assertSessionDriverEvent(value: unknown): asserts value is SessionDriverEvent {
  if (!isSessionDriverEvent(value)) {
    throw new Error('invalid-session-driver-event')
  }
}

function assertSessionDriverActionArray(value: unknown): asserts value is SessionDriverAction[] {
  if (!Array.isArray(value) || !value.every((item) => isSessionDriverAction(item))) {
    throw new Error('invalid-session-driver-actions')
  }
}

function assertSerializableSessionState(value: unknown): asserts value is SerializableSessionState {
  if (!isSerializableSessionState(value)) {
    throw new Error('invalid-session-driver-state')
  }
}

function assertReduceRequest(value: unknown): asserts value is SessionDriverReduceRequest {
  if (!isObjectRecord(value)) {
    throw new Error('invalid-session-driver-reduce-request')
  }
  assertSerializableSessionState(value.state)
  assertSessionDriverEvent(value.event)
}

function assertReduceResponse(value: unknown): asserts value is SessionDriverReduceResponse {
  if (!isObjectRecord(value)) {
    throw new Error('invalid-session-driver-reduce-response')
  }
  assertSerializableSessionState(value.state)
  assertSessionDriverActionArray(value.actions)
}

export function encodeSessionDriverState(state: SerializableSessionState): string {
  return JSON.stringify(state)
}

export function decodeSessionDriverState(raw: string): SerializableSessionState {
  const parsed = parseJson(raw)
  assertSerializableSessionState(parsed)
  return parsed
}

export function encodeSessionDriverEvent(event: SessionDriverEvent): string {
  return JSON.stringify(event)
}

export function decodeSessionDriverEvent(raw: string): SessionDriverEvent {
  const parsed = parseJson(raw)
  assertSessionDriverEvent(parsed)
  return parsed
}

export function encodeSessionDriverActions(actions: SessionDriverAction[]): string {
  return JSON.stringify(actions)
}

export function decodeSessionDriverActions(raw: string): SessionDriverAction[] {
  const parsed = parseJson(raw)
  assertSessionDriverActionArray(parsed)
  return parsed
}

export function encodeSessionDriverReduceRequest(req: SessionDriverReduceRequest): string {
  return JSON.stringify(req)
}

export function decodeSessionDriverReduceRequest(raw: string): SessionDriverReduceRequest {
  const parsed = parseJson(raw)
  assertReduceRequest(parsed)
  return parsed
}

export function encodeSessionDriverReduceResponse(res: SessionDriverReduceResponse): string {
  return JSON.stringify(res)
}

export function decodeSessionDriverReduceResponse(raw: string): SessionDriverReduceResponse {
  const parsed = parseJson(raw)
  assertReduceResponse(parsed)
  return parsed
}

export function reduceSessionDriverJson(stateJson: string, eventJson: string): string {
  const state = decodeSessionDriverState(stateJson)
  const event = decodeSessionDriverEvent(eventJson)
  const output = dispatchSessionDriverEvent(state, event)
  return encodeSessionDriverReduceResponse(output)
}
