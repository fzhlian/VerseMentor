import { AppConfig, DEFAULTS } from '../config/defaults'
import { Poem } from '../data/models'
import { PoemIndex } from '../data/poem_index'
import { samplePoems } from '../data/sample_poems'
import { buildOrUpdateAuthorKB, AuthorKB } from '../kb/author_kb'
import { buildOrUpdateDynastyKB, DynastyKB } from '../kb/dynasty_kb'
import {
  createInitialSession,
  ReciteProgressEntry,
  sessionReducer,
  SessionAction,
  SessionContext,
  SessionEvent,
  SessionOutput,
  SessionState,
  SessionStateType
} from '../fsm/session_fsm'

export type SessionDriverEvent = SessionEvent
export type SessionDriverAction = SessionAction

export type SerializableSessionContext = Omit<SessionContext, 'poemIndex'>

export interface SerializableSessionState {
  type: SessionStateType
  ctx: SerializableSessionContext
}

export interface SessionDriverOutput {
  state: SerializableSessionState
  actions: SessionDriverAction[]
}

export interface CreateSessionDriverInput {
  config?: AppConfig
  poems?: Poem[]
  dynastyKB?: DynastyKB
  authorKB?: AuthorKB
  now?: number
  lastUserActiveAt?: number
}

export interface SessionDriverUiProjection {
  stateType: SessionStateType
  currentLineIdx: number
  selectedPoemTitle?: string
  reciteProgress: ReciteProgressEntry[]
}

function toSerializableSessionState(state: SessionState): SerializableSessionState {
  const { poemIndex: _poemIndex, ...ctx } = state.ctx
  return {
    type: state.type,
    ctx
  }
}

function toRuntimeState(state: SerializableSessionState): SessionState {
  return {
    type: state.type,
    ctx: {
      ...state.ctx,
      poemIndex: new PoemIndex(state.ctx.poems)
    }
  }
}

export function createSessionDriverState(input: CreateSessionDriverInput = {}): SerializableSessionState {
  const now = input.now ?? Date.now()
  const poems = input.poems ?? samplePoems
  const config = input.config ?? DEFAULTS

  const dynastyKB =
    input.dynastyKB ??
    buildOrUpdateDynastyKB({
      observedDynasties: poems.map((p) => p.dynasty),
      now
    })

  const authorKB =
    input.authorKB ??
    buildOrUpdateAuthorKB({
      poems: poems.map((p) => ({
        title: p.title,
        dynastyRaw: p.dynasty,
        authorPrimary: p.author
      })),
      dynastyKB,
      now
    })

  const runtimeState = createInitialSession({
    config,
    poemIndex: new PoemIndex(poems),
    poems,
    dynastyKB,
    authorKB,
    lastUserActiveAt: input.lastUserActiveAt
  })

  return toSerializableSessionState(runtimeState)
}

export function dispatchSessionDriverEvent(
  current: SerializableSessionState,
  event: SessionDriverEvent
): SessionDriverOutput {
  const runtimeState = toRuntimeState(current)
  const output: SessionOutput = sessionReducer(runtimeState, event)
  return {
    state: toSerializableSessionState(output.state),
    actions: output.actions
  }
}

export function projectSessionDriverUi(state: SerializableSessionState): SessionDriverUiProjection {
  return {
    stateType: state.type,
    currentLineIdx: state.ctx.currentLineIdx,
    selectedPoemTitle: state.ctx.selectedPoem?.title,
    reciteProgress: state.ctx.reciteProgress
  }
}
