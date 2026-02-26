import { Poem } from './models'
import { PoemMatcher } from './poems'

export type RecitationState =
  | { type: 'IDLE' }
  | { type: 'LISTENING_FOR_TITLE' }
  | { type: 'CONFIRMING_TITLE'; candidate: string }
  | { type: 'RECITING'; poem: Poem }
  | { type: 'COMPLETED' }
  | { type: 'FAILED'; reason: string }

export type RecitationEvent =
  | { type: 'START_RECITATION' }
  | { type: 'TITLE_RECOGNIZED'; text: string }
  | { type: 'TITLE_CONFIRMED'; confirmed: boolean }
  | { type: 'RECITATION_FINISHED' }
  | { type: 'CANCEL' }
  | { type: 'ERROR'; message: string }

export type RecitationAction =
  | { type: 'SPEAK'; text: string }
  | { type: 'LISTEN_FOR_TITLE' }
  | { type: 'LISTEN_FOR_CONFIRMATION' }
  | { type: 'FETCH_VARIANTS'; poem: Poem }

export interface RecitationOutput {
  state: RecitationState
  actions: RecitationAction[]
}

export class RecitationFsm {
  constructor(private matcher: PoemMatcher) {}

  transition(state: RecitationState, event: RecitationEvent): RecitationOutput {
    switch (state.type) {
      case 'IDLE':
        if (event.type === 'START_RECITATION') {
          return {
            state: { type: 'LISTENING_FOR_TITLE' },
            actions: [
              { type: 'SPEAK', text: '请说出诗词题目。' },
              { type: 'LISTEN_FOR_TITLE' }
            ]
          }
        }
        return { state, actions: [] }
      case 'LISTENING_FOR_TITLE':
        if (event.type === 'TITLE_RECOGNIZED') {
          return {
            state: { type: 'CONFIRMING_TITLE', candidate: event.text },
            actions: [
              { type: 'SPEAK', text: `你说的是《${event.text}》吗？` },
              { type: 'LISTEN_FOR_CONFIRMATION' }
            ]
          }
        }
        if (event.type === 'CANCEL') {
          return { state: { type: 'IDLE' }, actions: [] }
        }
        if (event.type === 'ERROR') {
          return { state: { type: 'FAILED', reason: event.message }, actions: [] }
        }
        return { state, actions: [] }
      case 'CONFIRMING_TITLE':
        if (event.type === 'TITLE_CONFIRMED') {
          if (event.confirmed) {
            const poem = this.matcher.matchByTitle(state.candidate)
            if (!poem) {
              return {
                state: { type: 'LISTENING_FOR_TITLE' },
                actions: [
                  { type: 'SPEAK', text: '未找到该题目，请再说一次。' },
                  { type: 'LISTEN_FOR_TITLE' }
                ]
              }
            }
            return {
              state: { type: 'RECITING', poem },
              actions: [
                { type: 'SPEAK', text: `开始背诵《${poem.title}》。` },
                { type: 'FETCH_VARIANTS', poem }
              ]
            }
          }
          return {
            state: { type: 'LISTENING_FOR_TITLE' },
            actions: [
              { type: 'SPEAK', text: '好的，请再说一次题目。' },
              { type: 'LISTEN_FOR_TITLE' }
            ]
          }
        }
        if (event.type === 'CANCEL') {
          return { state: { type: 'IDLE' }, actions: [] }
        }
        if (event.type === 'ERROR') {
          return { state: { type: 'FAILED', reason: event.message }, actions: [] }
        }
        return { state, actions: [] }
      case 'RECITING':
        if (event.type === 'RECITATION_FINISHED') {
          return { state: { type: 'COMPLETED' }, actions: [{ type: 'SPEAK', text: '已完成。' }] }
        }
        if (event.type === 'CANCEL') {
          return { state: { type: 'IDLE' }, actions: [] }
        }
        if (event.type === 'ERROR') {
          return { state: { type: 'FAILED', reason: event.message }, actions: [] }
        }
        return { state, actions: [] }
      case 'COMPLETED':
        if (event.type === 'START_RECITATION') {
          return {
            state: { type: 'LISTENING_FOR_TITLE' },
            actions: [
              { type: 'SPEAK', text: '请说出诗词题目。' },
              { type: 'LISTEN_FOR_TITLE' }
            ]
          }
        }
        return { state, actions: [] }
      case 'FAILED':
        if (event.type === 'START_RECITATION') {
          return {
            state: { type: 'LISTENING_FOR_TITLE' },
            actions: [
              { type: 'SPEAK', text: '请说出诗词题目。' },
              { type: 'LISTEN_FOR_TITLE' }
            ]
          }
        }
        return { state, actions: [] }
    }
  }
}
