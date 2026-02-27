# HarmonyOS NEXT Build & Run

This is a minimal ArkTS shell.

Steps:
1. Open `harmony-next/` in DevEco Studio (NEXT).
2. Configure signing and device/emulator.
3. Run the entry module.

Notes:
- Speech adapter in `entry/src/main/ets/ets/shared/speech_impl.ts` now provides a local mock implementation for development.
- Session page (`entry/src/main/ets/ets/pages/Pages.ets`) now includes mock controls:
  - start/stop session driven by local `SessionShellController` (`entry/src/main/ets/ets/shared/session_controller.ts`)
  - simulate partial/final/meta/hint ASR events
  - execute controller actions to TTS/listening state
  - use `dispatch(event)` with shared-core-compatible event/action names for bridge-ready wiring
  - reducer call path now supports `SharedCoreRuntime` abstraction (`entry/src/main/ets/ets/shared/shared_core_runtime.ts`) with local reducer fallback
  - `GlobalFunctionSharedCoreRuntime` looks for host hooks: `__vmCreateSessionDriverStateJson()` and `__vmReduceSessionDriverJson(stateJson, eventJson)`
  - optional delegate hooks for real runtime: `__vmSharedCoreDelegateCreateSessionDriverStateJson()` and `__vmSharedCoreDelegateReduceSessionDriverJson(stateJson, eventJson)`
  - hook mode probe: `__vmGetSessionDriverHookMode()` returns `mock` or `delegate`
  - bridge host API is exported for ArkTS integration:
    - `registerSharedCoreDelegateHooks(...)` (returns a registration token)
    - `clearSharedCoreDelegateHooks(token?)` (when token is passed, only clears matching owner)
    - `getSharedCoreHookMode()`
  - demo delegate helper is available in `entry/src/main/ets/ets/shared/shared_core_delegate_demo.ts`
  - demo delegate now stores registration token and clears by token to avoid accidentally clearing newer delegate owners
  - host hook installer is auto-called from `entry/src/main/ets/ets/app/MainApp.ets` via `entry/src/main/ets/ets/shared/shared_core_bridge_host.ts`
  - current hook implementation is a local mock driver; replace hook bodies with real shared-core bridge calls when runtime is ready
  - UI shows reducer path (`runtime` or `local`), runtime mode, last event/actions, runtime fallback reason, dispatch counters (`dispatch`, `runtime`, `local`), recent trace lines, and has `Use Runtime` / `Use Local` / `Enable Delegate` / `Disable Delegate` / `Bad JSON` / `Bad Actions` / `Bad State` / `Check Runtime On` / `Check ASR Error` / `Check Bad JSON` / `Check Bad Actions` / `Check Bad State` / `Check Runtime Off` / `Check All Faults` / `Reset Debug` controls for quick verification (`Check Runtime On` and `Check ASR Error` should pass runtime path; `Bad JSON` / `Bad Actions` / `Bad State` / `Check Runtime Off` should produce `runtime-invalid-json` / `runtime-actions-invalid` / `runtime-state-invalid` / `runtime-disabled` and local fallback path)
  - `HarmonySpeechIO` supports timed ASR/error scripts (`playMockScript` / `stopMockScript`), script-state callback (`onMockScriptStateChange`), mock ASR error callback path (`onAsrError` + `mockError`), and speaking-state callback (`onSpeakingStateChange`) with auto-complete behavior
  - ASR errors are routed into session controller as `USER_ASR_ERROR`; runtime and local reducer paths both support this event for recovery prompts
  - Session events now forward optional `now` on `USER_ASR` / `USER_UI_START` and preserve optional ASR `confidence` through bridge host parsing for better shared-core contract parity
  - Session page exposes multi-scenario auto scripts (`Auto Happy` / `Auto Hint` / `Auto Stop` / `Auto Delegate` / `Auto Error` / `Auto ErrDelegate` + `Stop Auto Script`) and shows real-time running/pending timer status (`Auto Delegate` / `Auto ErrDelegate` force runtime+delegate before playback)
- Settings page now reads/writes local mock preferences via `entry/src/main/ets/ets/shared/storage_impl.ts`.
- Replace the mock with HarmonyOS NEXT speech recognizer and TTS services for production.
- Hook the shared-core FSM by dispatching ASR events and handling actions to TTS and UI.
