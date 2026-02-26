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

function parseJson<T>(raw: string): T {
  return JSON.parse(raw) as T
}

export function encodeSessionDriverState(state: SerializableSessionState): string {
  return JSON.stringify(state)
}

export function decodeSessionDriverState(raw: string): SerializableSessionState {
  return parseJson<SerializableSessionState>(raw)
}

export function encodeSessionDriverEvent(event: SessionDriverEvent): string {
  return JSON.stringify(event)
}

export function decodeSessionDriverEvent(raw: string): SessionDriverEvent {
  return parseJson<SessionDriverEvent>(raw)
}

export function encodeSessionDriverActions(actions: SessionDriverAction[]): string {
  return JSON.stringify(actions)
}

export function decodeSessionDriverActions(raw: string): SessionDriverAction[] {
  return parseJson<SessionDriverAction[]>(raw)
}

export function encodeSessionDriverReduceRequest(req: SessionDriverReduceRequest): string {
  return JSON.stringify(req)
}

export function decodeSessionDriverReduceRequest(raw: string): SessionDriverReduceRequest {
  return parseJson<SessionDriverReduceRequest>(raw)
}

export function encodeSessionDriverReduceResponse(res: SessionDriverReduceResponse): string {
  return JSON.stringify(res)
}

export function decodeSessionDriverReduceResponse(raw: string): SessionDriverReduceResponse {
  return parseJson<SessionDriverReduceResponse>(raw)
}

export function reduceSessionDriverJson(stateJson: string, eventJson: string): string {
  const state = decodeSessionDriverState(stateJson)
  const event = decodeSessionDriverEvent(eventJson)
  const output = dispatchSessionDriverEvent(state, event)
  return encodeSessionDriverReduceResponse(output)
}
