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

function parseJson(raw: string): unknown {
  return JSON.parse(raw) as unknown
}

function isObjectRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function isStringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((item) => typeof item === 'string')
}

function isPoem(value: unknown): boolean {
  if (!isObjectRecord(value)) return false
  if (typeof value.id !== 'string' || typeof value.title !== 'string') return false
  if (typeof value.author !== 'string' || typeof value.dynasty !== 'string') return false
  if (!Array.isArray(value.lines)) return false
  return value.lines.every((line) => isObjectRecord(line) && typeof line.text === 'string')
}

function isSessionDriverEvent(value: unknown): value is SessionDriverEvent {
  if (!isObjectRecord(value) || typeof value.type !== 'string') return false
  switch (value.type) {
    case 'USER_UI_START':
    case 'USER_UI_STOP':
      return true
    case 'TICK':
      return typeof value.now === 'number'
    case 'USER_ASR':
      return (
        typeof value.text === 'string' &&
        typeof value.isFinal === 'boolean' &&
        (value.confidence === undefined || typeof value.confidence === 'number')
      )
    case 'USER_ASR_ERROR':
      return typeof value.code === 'number' && typeof value.message === 'string'
    case 'EV_VARIANTS_FETCH_DONE':
      return value.entry === null || isObjectRecord(value.entry)
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
  if (!isObjectRecord(value) || typeof value.type !== 'string') return false
  if (!isObjectRecord(value.ctx)) return false
  const ctx = value.ctx
  if (!isObjectRecord(ctx.config)) return false
  if (!Array.isArray(ctx.poems) || !ctx.poems.every((poem) => isPoem(poem))) return false
  if (!isObjectRecord(ctx.dynastyKB) || !Array.isArray(ctx.dynastyKB.canonicals)) return false
  if (!isObjectRecord(ctx.authorKB) || !Array.isArray(ctx.authorKB.authors)) return false
  if (typeof ctx.currentLineIdx !== 'number') return false
  if (!Array.isArray(ctx.reciteProgress)) return false
  if (
    !ctx.reciteProgress.every(
      (item) =>
        isObjectRecord(item) &&
        typeof item.idx === 'number' &&
        typeof item.passed === 'boolean' &&
        typeof item.score === 'number'
    )
  ) {
    return false
  }
  if (ctx.lastUserActiveAt !== undefined && typeof ctx.lastUserActiveAt !== 'number') return false
  if (!isObjectRecord(ctx.timers)) return false
  if (ctx.timers.noPoemIntentSince !== undefined && typeof ctx.timers.noPoemIntentSince !== 'number') return false
  if (!isStringArray(ctx.praiseHistory)) return false
  if (ctx.selectedPoem !== undefined && !isPoem(ctx.selectedPoem)) return false
  if (ctx.variantsCacheEntry !== undefined && ctx.variantsCacheEntry !== null && !isObjectRecord(ctx.variantsCacheEntry)) return false
  if (ctx.dynastyResolved !== undefined && !isObjectRecord(ctx.dynastyResolved)) return false
  if (ctx.authorResolved !== undefined && !isObjectRecord(ctx.authorResolved)) return false
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
