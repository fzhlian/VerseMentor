import { HarmonySpeechIO } from '../shared/speech_impl'
import { PreferenceKVStore } from '../shared/storage_impl'
import { SessionShellAction, SessionShellController, SessionShellEvent } from '../shared/session_controller'
import {
  disableDemoSharedCoreDelegate,
  enableDemoSharedCoreDelegate,
  enableDemoSharedCoreDelegateInvalidActions,
  enableDemoSharedCoreDelegateInvalidJson,
  enableDemoSharedCoreDelegateInvalidState,
  getDemoSharedCoreDelegateMode,
  isDemoSharedCoreDelegateEnabled
} from '../shared/shared_core_delegate_demo'
import type { IMockSpeechIO, MockAsrScriptStep } from '../shared/speech'

const SETTINGS_KEY = 'vm.shell.settings'
type DelegateMode = 'off' | 'normal' | 'invalid-json' | 'invalid-actions' | 'invalid-state'

@Component
struct HomePage {
  @Prop onStart: () => void
  @Prop onSettings: () => void

  build() {
    Column({ space: 12 }) {
      Text('VerseMentor').fontSize(28)
      Button('Start').onClick(() => this.onStart())
      Button('Settings').onClick(() => this.onSettings())
    }.padding(24)
  }
}

@Component
struct SessionPage {
  @Prop onBack: () => void
  @State status: string = 'Idle'
  @State sessionStage: string = 'IDLE'
  @State runtimeEnabled: boolean = true
  @State reducerPath: string = 'local'
  @State runtimeMode: string = 'local'
  @State runtimeFallbackReason: string = '-'
  @State lastEventType: string = '-'
  @State lastActionTypes: string = '-'
  @State dispatchCount: number = 0
  @State runtimeDispatchCount: number = 0
  @State localDispatchCount: number = 0
  @State traceSummary: string = '-'
  @State delegateEnabled: boolean = false
  @State delegateMode: string = 'off'
  @State faultCheckResult: string = '-'
  @State autoScriptRunning: boolean = false
  @State autoScriptPendingTimers: number = 0
  @State autoScenario: string = '-'
  @State selectedPoem: string = '-'
  @State reciteProgress: string = '0/0'
  @State expectedLine: string = '-'
  @State listening: boolean = false
  @State lastHeard: string = '-'
  @State lastSpoken: string = '-'
  @State lastReply: string = '-'
  @State voices: string = 'loading...'

  private speech: IMockSpeechIO = new HarmonySpeechIO()
  private controller: SessionShellController = new SessionShellController()

  aboutToAppear() {
    this.speech.onAsrResult((result) => {
      this.dispatchEvent({
        type: 'USER_ASR',
        text: result.text,
        isFinal: result.isFinal === true,
        confidence: result.confidence
      })
    })
    this.speech.listVoices().then((list) => {
      this.voices = list.map((v) => v.name).join(', ')
    })
    this.speech.onMockScriptStateChange((running, pendingTimers) => {
      this.onAutoScriptStateChanged(running, pendingTimers)
    })
    this.refreshSpeechDebug()
    this.syncDelegateState()
    this.controller.onStateChange((snapshot) => {
      this.sessionStage = snapshot.stage
      this.runtimeEnabled = snapshot.runtimeEnabled
      this.reducerPath = snapshot.reducerPath
      this.runtimeMode = snapshot.runtimeMode
      this.runtimeFallbackReason = snapshot.runtimeFallbackReason
      this.lastEventType = snapshot.lastEventType
      this.lastActionTypes = snapshot.lastActionTypes
      this.dispatchCount = snapshot.dispatchCount
      this.runtimeDispatchCount = snapshot.runtimeDispatchCount
      this.localDispatchCount = snapshot.localDispatchCount
      this.traceSummary = snapshot.traceSummary
      this.selectedPoem = snapshot.selectedPoemTitle ?? '-'
      this.reciteProgress = `${snapshot.currentLineIdx}/${snapshot.totalLines}`
      this.expectedLine = snapshot.expectedLine ?? '-'
      this.lastHeard = snapshot.lastAsr
      this.lastReply = snapshot.lastReply
    })
  }

  aboutToDisappear() {
    this.speech.stopMockScript()
    this.speech.stopListening()
    this.speech.stopSpeak()
  }

  private startListening() {
    const dbg = this.speech.getDebugState()
    if (!dbg.listening) {
      this.speech.startListening({ locale: 'zh-CN', partialResults: true })
    }
    this.listening = true
    this.status = 'Listening'
    this.refreshSpeechDebug()
  }

  private stopListening() {
    const dbg = this.speech.getDebugState()
    if (dbg.listening) {
      this.speech.stopListening()
    }
    this.listening = false
    this.status = 'Idle'
    this.refreshSpeechDebug()
  }

  private simulatePartialAsr() {
    const preview = this.expectedLine === '-' ? 'jing' : this.expectedLine.slice(0, 5)
    this.speech.mockRecognize(preview, false, 0.7)
  }

  private simulateFinalAsr() {
    this.speech.mockRecognize(this.expectedLine === '-' ? 'Jing Ye Si' : this.expectedLine, true, 0.95)
  }

  private simulateMetaAsr() {
    this.speech.mockRecognize('Tang Li Bai', true, 0.95)
  }

  private simulateHintAsr() {
    this.speech.mockRecognize('hint', true, 0.9)
  }

  private stopSpeak() {
    this.speech.stopSpeak()
    this.refreshSpeechDebug()
    this.status = this.listening ? 'Listening' : 'Idle'
  }

  private startSession() {
    this.dispatchEvent({ type: 'USER_UI_START' })
  }

  private stopSession() {
    this.stopAutoScript()
    this.dispatchEvent({ type: 'USER_UI_STOP' })
  }

  private simulateTick() {
    this.dispatchEvent({ type: 'TICK', now: Date.now() })
  }

  private resetDebugStats() {
    this.controller.resetDebugStats()
    this.faultCheckResult = '-'
    this.refreshSpeechDebug()
  }

  private enableDelegate() {
    this.activateDelegateMode('normal')
  }

  private enableDelegateInvalidJson() {
    this.activateDelegateMode('invalid-json')
  }

  private enableDelegateInvalidActions() {
    this.activateDelegateMode('invalid-actions')
  }

  private enableDelegateInvalidState() {
    this.activateDelegateMode('invalid-state')
  }

  private disableDelegate() {
    this.activateDelegateMode('off')
  }

  private useRuntimePath() {
    this.controller.setRuntimeEnabled(true)
    this.simulateTick()
  }

  private useLocalPath() {
    this.controller.setRuntimeEnabled(false)
    this.simulateTick()
  }

  private runAutoScript(scenario: string, steps: MockAsrScriptStep[]) {
    this.startSession()
    this.autoScenario = scenario
    this.speech.playMockScript(steps, {
      startDelayMs: 900,
      autoStartListening: true,
      locale: 'zh-CN',
      partialResults: true
    })
    this.status = `AutoScript:${scenario}`
    this.refreshSpeechDebug()
  }

  private runAutoScriptHappy() {
    this.runAutoScript('Happy', [
      { text: 'Jing', isFinal: false, confidence: 0.7, delayMs: 900 },
      { text: 'Jing Ye Si', isFinal: true, confidence: 0.95, delayMs: 1000 },
      { text: 'Tang Li Bai', isFinal: true, confidence: 0.95, delayMs: 1000 },
      { text: 'chuang qian ming yue guang', isFinal: true, confidence: 0.95, delayMs: 900 },
      { text: 'yi shi di shang shuang', isFinal: true, confidence: 0.95, delayMs: 900 },
      { text: 'ju tou wang ming yue', isFinal: true, confidence: 0.95, delayMs: 900 },
      { text: 'di tou si gu xiang', isFinal: true, confidence: 0.95, delayMs: 900 }
    ])
  }

  private runAutoScriptHint() {
    this.runAutoScript('Hint', [
      { text: 'Jing', isFinal: false, confidence: 0.7, delayMs: 900 },
      { text: 'Jing Ye Si', isFinal: true, confidence: 0.95, delayMs: 1000 },
      { text: 'Tang Li Bai', isFinal: true, confidence: 0.95, delayMs: 1000 },
      { text: 'chuang qian', isFinal: true, confidence: 0.72, delayMs: 900 },
      { text: 'hint', isFinal: true, confidence: 0.9, delayMs: 1100 },
      { text: 'chuang qian ming yue guang', isFinal: true, confidence: 0.95, delayMs: 900 },
      { text: 'yi shi di shang shuang', isFinal: true, confidence: 0.95, delayMs: 900 },
      { text: 'ju tou wang ming yue', isFinal: true, confidence: 0.95, delayMs: 900 },
      { text: 'di tou si gu xiang', isFinal: true, confidence: 0.95, delayMs: 900 }
    ])
  }

  private runAutoScriptStop() {
    this.runAutoScript('Stop', [
      { text: 'Jing', isFinal: false, confidence: 0.7, delayMs: 900 },
      { text: 'Jing Ye Si', isFinal: true, confidence: 0.95, delayMs: 1000 },
      { text: 'stop', isFinal: true, confidence: 0.95, delayMs: 1000 }
    ])
  }

  private runAutoScriptDelegate() {
    this.enableDelegate()
    this.runAutoScript('Delegate', [
      { text: 'Jing', isFinal: false, confidence: 0.7, delayMs: 900 },
      { text: 'Jing Ye Si', isFinal: true, confidence: 0.95, delayMs: 1000 },
      { text: 'Tang Li Bai', isFinal: true, confidence: 0.95, delayMs: 1000 },
      { text: 'chuang qian ming yue guang', isFinal: true, confidence: 0.95, delayMs: 900 },
      { text: 'yi shi di shang shuang', isFinal: true, confidence: 0.95, delayMs: 900 },
      { text: 'ju tou wang ming yue', isFinal: true, confidence: 0.95, delayMs: 900 },
      { text: 'di tou si gu xiang', isFinal: true, confidence: 0.95, delayMs: 900 }
    ])
  }

  private stopAutoScript() {
    this.speech.stopMockScript()
    this.autoScenario = '-'
    this.refreshSpeechDebug()
    this.status = this.listening ? 'Listening' : 'Idle'
  }

  private onAutoScriptStateChanged(running: boolean, pendingTimers: number) {
    this.autoScriptRunning = running
    this.autoScriptPendingTimers = pendingTimers
    this.refreshSpeechDebug()
    if (!running && pendingTimers === 0 && this.status.startsWith('AutoScript:')) {
      this.autoScenario = '-'
      this.status = this.listening ? 'Listening' : 'Idle'
    }
  }

  private refreshSpeechDebug() {
    const dbg = this.speech.getDebugState()
    this.listening = dbg.listening
    this.autoScriptRunning = dbg.mockScriptRunning
    this.autoScriptPendingTimers = dbg.pendingScriptTimers
    if (this.status === 'Idle' || this.status === 'Listening') {
      this.status = this.listening ? 'Listening' : 'Idle'
    }
  }

  private syncDelegateState() {
    this.delegateEnabled = isDemoSharedCoreDelegateEnabled()
    this.delegateMode = getDemoSharedCoreDelegateMode()
  }

  private activateDelegateMode(
    mode: DelegateMode,
    runTick: boolean = true
  ) {
    switch (mode) {
      case 'normal':
        enableDemoSharedCoreDelegate()
        break
      case 'invalid-json':
        enableDemoSharedCoreDelegateInvalidJson()
        break
      case 'invalid-actions':
        enableDemoSharedCoreDelegateInvalidActions()
        break
      case 'invalid-state':
        enableDemoSharedCoreDelegateInvalidState()
        break
      case 'off':
      default:
        disableDemoSharedCoreDelegate()
        break
    }
    this.syncDelegateState()
    this.controller.resetRuntimeState()
    if (runTick) {
      this.simulateTick()
    }
  }

  private toDelegateMode(mode: string): DelegateMode {
    switch (mode) {
      case 'normal':
      case 'invalid-json':
      case 'invalid-actions':
      case 'invalid-state':
      case 'off':
        return mode
      default:
        return 'off'
    }
  }

  private restoreCheckContext(runtimeEnabled: boolean, mode: DelegateMode) {
    this.activateDelegateMode(mode, false)
    this.controller.setRuntimeEnabled(runtimeEnabled)
    this.syncDelegateState()
  }

  private runFaultCheck(
    label: string,
    mode: 'invalid-json' | 'invalid-actions' | 'invalid-state',
    expected: string
  ) {
    const result = this.executeFaultCheck(label, mode, expected)
    this.faultCheckResult = result
    this.status = `FaultCheck:${label}`
  }

  private executeFaultCheck(
    label: string,
    mode: 'invalid-json' | 'invalid-actions' | 'invalid-state',
    expected: string
  ): string {
    const previousRuntimeEnabled = this.runtimeEnabled
    const previousDelegateMode = this.toDelegateMode(this.delegateMode)
    this.controller.resetDebugStats()
    this.controller.setRuntimeEnabled(true)
    this.activateDelegateMode(mode, false)
    this.dispatchEvent({ type: 'TICK', now: Date.now() })
    const snapshot = this.controller.getSnapshot()
    const reasonOk = snapshot.runtimeFallbackReason === expected
    const pathOk = snapshot.reducerPath === 'local' && snapshot.runtimeMode === 'local'
    const pass = reasonOk && pathOk
    const result = `${label}:${pass ? 'PASS' : 'FAIL'}(${snapshot.runtimeFallbackReason},${snapshot.reducerPath}/${snapshot.runtimeMode})`
    this.restoreCheckContext(previousRuntimeEnabled, previousDelegateMode)
    return result
  }

  private runFaultCheckBadJson() {
    this.runFaultCheck('BadJson', 'invalid-json', 'runtime-invalid-json')
  }

  private runFaultCheckBadActions() {
    this.runFaultCheck('BadActions', 'invalid-actions', 'runtime-actions-invalid')
  }

  private runFaultCheckBadState() {
    this.runFaultCheck('BadState', 'invalid-state', 'runtime-state-invalid')
  }

  private executeRuntimeOnCheck(): string {
    const previousRuntimeEnabled = this.runtimeEnabled
    const previousDelegateMode = this.toDelegateMode(this.delegateMode)
    this.controller.resetDebugStats()
    this.controller.setRuntimeEnabled(true)
    this.activateDelegateMode('normal', false)
    this.dispatchEvent({ type: 'TICK', now: Date.now() })
    const snapshot = this.controller.getSnapshot()
    const reasonOk = snapshot.runtimeFallbackReason === '-'
    const pathOk = snapshot.reducerPath === 'runtime' && snapshot.runtimeMode !== 'local'
    const result =
      `RuntimeOn:${reasonOk && pathOk ? 'PASS' : 'FAIL'}(` +
      `${snapshot.runtimeFallbackReason},${snapshot.reducerPath}/${snapshot.runtimeMode})`
    this.restoreCheckContext(previousRuntimeEnabled, previousDelegateMode)
    return result
  }

  private runFaultCheckRuntimeOn() {
    this.faultCheckResult = this.executeRuntimeOnCheck()
    this.status = 'FaultCheck:RuntimeOn'
  }

  private executeRuntimeOffCheck(): string {
    const previousRuntimeEnabled = this.runtimeEnabled
    const previousDelegateMode = this.toDelegateMode(this.delegateMode)
    this.controller.resetDebugStats()
    this.activateDelegateMode('off', false)
    this.controller.setRuntimeEnabled(false)
    this.dispatchEvent({ type: 'TICK', now: Date.now() })
    const snapshot = this.controller.getSnapshot()
    const reasonOk = snapshot.runtimeFallbackReason === 'runtime-disabled'
    const pathOk = snapshot.reducerPath === 'local' && snapshot.runtimeMode === 'local'
    const result =
      `RuntimeOff:${reasonOk && pathOk ? 'PASS' : 'FAIL'}(` +
      `${snapshot.runtimeFallbackReason},${snapshot.reducerPath}/${snapshot.runtimeMode})`
    this.restoreCheckContext(previousRuntimeEnabled, previousDelegateMode)
    return result
  }

  private runFaultCheckRuntimeOff() {
    this.faultCheckResult = this.executeRuntimeOffCheck()
    this.status = 'FaultCheck:RuntimeOff'
  }

  private runFaultCheckAll() {
    const lines: string[] = [
      this.executeRuntimeOnCheck(),
      this.executeFaultCheck('BadJson', 'invalid-json', 'runtime-invalid-json'),
      this.executeFaultCheck('BadActions', 'invalid-actions', 'runtime-actions-invalid'),
      this.executeFaultCheck('BadState', 'invalid-state', 'runtime-state-invalid'),
      this.executeRuntimeOffCheck()
    ]
    this.faultCheckResult = lines.join(' | ')
    this.status = 'FaultCheck:All'
  }

  private dispatchEvent(event: SessionShellEvent) {
    this.applyActions(this.controller.dispatch(event))
    this.refreshSpeechDebug()
  }

  private applyActions(actions: SessionShellAction[]) {
    actions.forEach((action) => {
      switch (action.type) {
        case 'SPEAK':
          this.speech.speak(action.text, { voiceId: 'zh_female_default' })
          this.lastSpoken = action.text
          this.status = 'Speaking'
          break
        case 'START_LISTENING':
          this.startListening()
          break
        case 'STOP_LISTENING':
          this.stopListening()
          break
        case 'FETCH_VARIANTS':
          this.status = 'FetchingVariants'
          this.dispatchEvent({ type: 'EV_VARIANTS_FETCH_DONE', entry: null })
          break
        case 'UPDATE_SCREEN_HINT':
          this.lastReply = `hint:${action.key}`
          break
        default:
          break
      }
    })
  }

  build() {
    Column({ space: 12 }) {
      Text('Session').fontSize(20)
      Text(`Status: ${this.status}`)
      Text(`FSM: ${this.sessionStage}`)
      Text(`Runtime enabled: ${this.runtimeEnabled}`)
      Text(`Reducer: ${this.reducerPath}`)
      Text(`Runtime mode: ${this.runtimeMode}`)
      Text(`Runtime fallback: ${this.runtimeFallbackReason}`).fontSize(12)
      Text(`Last event: ${this.lastEventType}`).fontSize(12)
      Text(`Last actions: ${this.lastActionTypes}`).fontSize(12)
      Text(`Dispatch count: ${this.dispatchCount}`).fontSize(12)
      Text(`Runtime/local: ${this.runtimeDispatchCount}/${this.localDispatchCount}`).fontSize(12)
      Text(`Trace:\n${this.traceSummary}`).fontSize(11)
      Text(`Delegate enabled: ${this.delegateEnabled}`)
      Text(`Delegate mode: ${this.delegateMode}`).fontSize(12)
      Text(`Fault check: ${this.faultCheckResult}`).fontSize(12)
      Text(`Auto script: ${this.autoScriptRunning} (${this.autoScriptPendingTimers} timers)`)
      Text(`Auto scenario: ${this.autoScenario}`)
      Text(`Poem: ${this.selectedPoem}`).fontSize(12)
      Text(`Progress: ${this.reciteProgress}`).fontSize(12)
      Text(`Expected line: ${this.expectedLine}`).fontSize(12)
      Text(`Voices: ${this.voices}`).fontSize(12)
      Text(`Last heard: ${this.lastHeard}`).fontSize(12)
      Text(`Last reply: ${this.lastReply}`).fontSize(12)
      Text(`Last spoken: ${this.lastSpoken}`).fontSize(12)
      Row({ space: 8 }) {
        Button('Start Session').onClick(() => this.startSession())
        Button('Stop Session').onClick(() => this.stopSession())
        Button('Tick').onClick(() => this.simulateTick())
        Button('Reset Debug').onClick(() => this.resetDebugStats())
      }
      Row({ space: 8 }) {
        Button('Use Runtime').onClick(() => this.useRuntimePath())
        Button('Use Local').onClick(() => this.useLocalPath())
      }
      Row({ space: 8 }) {
        Button('Enable Delegate').onClick(() => this.enableDelegate())
        Button('Disable Delegate').onClick(() => this.disableDelegate())
        Button('Bad JSON').onClick(() => this.enableDelegateInvalidJson())
      }
      Row({ space: 8 }) {
        Button('Bad Actions').onClick(() => this.enableDelegateInvalidActions())
        Button('Bad State').onClick(() => this.enableDelegateInvalidState())
      }
      Row({ space: 8 }) {
        Button('Check Runtime On').onClick(() => this.runFaultCheckRuntimeOn())
        Button('Check Bad JSON').onClick(() => this.runFaultCheckBadJson())
        Button('Check Bad Actions').onClick(() => this.runFaultCheckBadActions())
        Button('Check Bad State').onClick(() => this.runFaultCheckBadState())
        Button('Check Runtime Off').onClick(() => this.runFaultCheckRuntimeOff())
        Button('Check All Faults').onClick(() => this.runFaultCheckAll())
      }
      Row({ space: 8 }) {
        Button('Auto Happy').onClick(() => this.runAutoScriptHappy())
        Button('Auto Hint').onClick(() => this.runAutoScriptHint())
        Button('Auto Stop').onClick(() => this.runAutoScriptStop())
        Button('Auto Delegate').onClick(() => this.runAutoScriptDelegate())
      }
      Row({ space: 8 }) {
        Button('Stop Auto Script').onClick(() => this.stopAutoScript())
      }
      Row({ space: 8 }) {
        Button('ASR Partial').onClick(() => this.simulatePartialAsr())
        Button('ASR Final').onClick(() => this.simulateFinalAsr())
      }
      Row({ space: 8 }) {
        Button('ASR Meta').onClick(() => this.simulateMetaAsr())
        Button('ASR Hint').onClick(() => this.simulateHintAsr())
      }
      Row({ space: 8 }) {
        Button('Start Listening').onClick(() => this.startListening())
        Button('Stop Listening').onClick(() => this.stopListening())
        Button('Stop Speak').onClick(() => this.stopSpeak())
      }
      Button('Back').onClick(() => this.onBack())
    }.padding(24)
  }
}

@Component
struct SettingsPage {
  @Prop onBack: () => void
  @State followSystem: boolean = true
  @State toneRemind: boolean = true
  @State variantsEnable: boolean = true
  @State saveStatus: string = 'Loading'

  private store: PreferenceKVStore = new PreferenceKVStore()

  aboutToAppear() {
    this.store.get(SETTINGS_KEY).then((raw) => {
      if (raw === null) {
        this.saveStatus = 'Using defaults'
        return
      }
      try {
        const parsed = JSON.parse(raw)
        if (typeof parsed.followSystem === 'boolean') {
          this.followSystem = parsed.followSystem
        }
        if (typeof parsed.toneRemind === 'boolean') {
          this.toneRemind = parsed.toneRemind
        }
        if (typeof parsed.variantsEnable === 'boolean') {
          this.variantsEnable = parsed.variantsEnable
        }
        this.saveStatus = 'Loaded'
      } catch (_err) {
        this.saveStatus = 'Invalid saved data'
      }
    })
  }

  private persistSettings() {
    const raw = JSON.stringify({
      followSystem: this.followSystem,
      toneRemind: this.toneRemind,
      variantsEnable: this.variantsEnable
    })
    this.store.set(SETTINGS_KEY, raw).then(() => {
      this.saveStatus = 'Saved'
    }).catch((_err) => {
      this.saveStatus = 'Save failed'
    })
  }

  private setFollowSystem(value: boolean) {
    this.followSystem = value
    this.persistSettings()
  }

  private setToneRemind(value: boolean) {
    this.toneRemind = value
    this.persistSettings()
  }

  private setVariantsEnable(value: boolean) {
    this.variantsEnable = value
    this.persistSettings()
  }

  private resetSettings() {
    this.followSystem = true
    this.toneRemind = true
    this.variantsEnable = true
    this.persistSettings()
  }

  build() {
    Column({ space: 12 }) {
      Text('Settings').fontSize(20)
      Text(`Store: ${this.saveStatus}`).fontSize(12)
      Row({ space: 8 }) {
        Text('Language')
        Button('System').onClick(() => this.setFollowSystem(true))
        Button('CN').onClick(() => this.setFollowSystem(false))
      }
      Row({ space: 8 }) {
        Text('Tone Remind')
        Toggle({ type: ToggleType.Switch, isOn: this.toneRemind }).onChange((v: boolean) => this.setToneRemind(v))
      }
      Row({ space: 8 }) {
        Text('Variants Online')
        Toggle({ type: ToggleType.Switch, isOn: this.variantsEnable }).onChange((v: boolean) => this.setVariantsEnable(v))
      }
      Button('Reset Defaults').onClick(() => this.resetSettings())
      Button('Back').onClick(() => this.onBack())
    }.padding(24)
  }
}
