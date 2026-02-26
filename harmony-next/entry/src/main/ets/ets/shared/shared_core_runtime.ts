export interface SharedCoreRuntime {
  createInitialStateJson(): string | null
  reduce(stateJson: string, eventJson: string): string | null
  getDebugMode(): string | null
}

export class StubSharedCoreRuntime implements SharedCoreRuntime {
  createInitialStateJson(): string | null {
    return null
  }

  reduce(_stateJson: string, _eventJson: string): string | null {
    return null
  }

  getDebugMode(): string | null {
    return null
  }
}

type RuntimeHost = Record<string, unknown>

export class GlobalFunctionSharedCoreRuntime implements SharedCoreRuntime {
  private readonly host: RuntimeHost
  private readonly createStateFnName: string
  private readonly reduceFnName: string
  private readonly modeFnName: string

  constructor(
    host?: RuntimeHost,
    createStateFnName: string = '__vmCreateSessionDriverStateJson',
    reduceFnName: string = '__vmReduceSessionDriverJson',
    modeFnName: string = '__vmGetSessionDriverHookMode'
  ) {
    this.host = host ?? (globalThis as unknown as RuntimeHost)
    this.createStateFnName = createStateFnName
    this.reduceFnName = reduceFnName
    this.modeFnName = modeFnName
  }

  createInitialStateJson(): string | null {
    const fn = this.host[this.createStateFnName]
    if (typeof fn !== 'function') return null
    try {
      const out = (fn as () => unknown)()
      return typeof out === 'string' ? out : null
    } catch (_err) {
      return null
    }
  }

  reduce(stateJson: string, eventJson: string): string | null {
    const fn = this.host[this.reduceFnName]
    if (typeof fn !== 'function') return null
    try {
      const out = (fn as (stateJson: string, eventJson: string) => unknown)(stateJson, eventJson)
      return typeof out === 'string' ? out : null
    } catch (_err) {
      return null
    }
  }

  getDebugMode(): string | null {
    const fn = this.host[this.modeFnName]
    if (typeof fn !== 'function') return null
    try {
      const out = (fn as () => unknown)()
      return typeof out === 'string' ? out : null
    } catch (_err) {
      return null
    }
  }
}
