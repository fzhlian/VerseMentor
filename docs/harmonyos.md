# HarmonyOS Build & Run

This is a minimal ArkTS shell.

Steps:
1. Open `harmonyos/` in DevEco Studio.
2. Configure signing and device/emulator.
3. Run the entry module.

Notes:
- Speech adapter in `entry/src/main/ets/shared/speech_impl.ts` now provides a local mock implementation for development.
- Session page (`entry/src/main/ets/pages/Pages.ets`) now includes mock controls:
  - start/stop session driven by local `SessionShellController` (`entry/src/main/ets/shared/session_controller.ts`)
  - simulate partial/final/meta/hint ASR events
  - execute controller actions to TTS/listening state
  - use `dispatch(event)` with shared-core-compatible event/action names for bridge-ready wiring
  - reducer call path now supports `SharedCoreRuntime` abstraction (`entry/src/main/ets/shared/shared_core_runtime.ts`) with local reducer fallback
  - `GlobalFunctionSharedCoreRuntime` looks for host hooks: `__vmCreateSessionDriverStateJson()` and `__vmReduceSessionDriverJson(stateJson, eventJson)`
  - optional delegate hooks for real runtime: `__vmSharedCoreDelegateCreateSessionDriverStateJson()` and `__vmSharedCoreDelegateReduceSessionDriverJson(stateJson, eventJson)`
  - hook mode probe: `__vmGetSessionDriverHookMode()` returns `mock` or `delegate`
  - bridge host API is exported for ArkTS integration:
    - `registerSharedCoreDelegateHooks(...)`
    - `clearSharedCoreDelegateHooks()`
    - `getSharedCoreHookMode()`
  - demo delegate helper is available in `entry/src/main/ets/shared/shared_core_delegate_demo.ts`
  - host hook installer is auto-called from `entry/src/main/ets/app/MainApp.ets` via `entry/src/main/ets/shared/shared_core_bridge_host.ts`
  - current hook implementation is a local mock driver; replace hook bodies with real shared-core bridge calls when runtime is ready
  - UI shows reducer path (`runtime` or `local`), runtime mode, last event/actions, runtime fallback reason, dispatch counters (`dispatch`, `runtime`, `local`), recent trace lines, and has `Use Runtime` / `Use Local` / `Enable Delegate` / `Disable Delegate` / `Bad JSON` / `Bad Actions` / `Bad State` / `Check Runtime On` / `Check Bad JSON` / `Check Bad Actions` / `Check Bad State` / `Check Runtime Off` / `Check All Faults` / `Reset Debug` controls for quick verification (`Check Runtime On` should pass runtime path; `Bad JSON` / `Bad Actions` / `Bad State` / `Check Runtime Off` should produce `runtime-invalid-json` / `runtime-actions-invalid` / `runtime-state-invalid` / `runtime-disabled` and local fallback path)
  - `HarmonySpeechIO` supports timed ASR scripts (`playMockScript` / `stopMockScript`) and script-state callback (`onMockScriptStateChange`)
  - Session page exposes multi-scenario auto scripts (`Auto Happy` / `Auto Hint` / `Auto Stop` / `Auto Delegate` + `Stop Auto Script`) and shows real-time running/pending timer status
- Settings page now reads/writes local mock preferences via `entry/src/main/ets/shared/storage_impl.ts`.
- Replace the mock with HarmonyOS speech recognizer and TTS services for production.
- Hook the shared-core FSM by dispatching ASR events and handling actions to TTS and UI.
