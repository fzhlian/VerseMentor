# VerseMentor Build & Run Guide

This monorepo contains platform shells plus a shared core:

- `shared-core/`: TypeScript core logic
- `android/`: Android shell (Kotlin + Compose)
- `harmonyos/`: HarmonyOS shell (ArkTS)
- `harmony-next/`: HarmonyOS NEXT shell (ArkTS)

## Shared Core (TypeScript / ArkTS Compatible)

Location: `shared-core/`

Prereqs:
- Node.js 18+ (recommended: 20+)
- npm

Install dependencies:
```powershell
cd shared-core
npm install
```

Build core output:
```powershell
npm run build
```

Build bridge output (JSON driver/contract under `dist-bridge/bridge/`):
```powershell
npm run build:bridge
```

Run tests:
```powershell
npm test
```

Windows PowerShell note:
- If execution policy blocks `npm` (`npm.ps1 cannot be loaded`), use `npm.cmd` instead, for example: `npm.cmd test`.

## Android

Location: `android/`

Prereqs:
- Android Studio (Giraffe+ recommended)
- Android SDK 34 and matching build-tools
- JDK 17

Build from CLI:
```powershell
cd android
.\gradlew.bat assembleDebug
```

If Gradle reports SDK not found:
- Copy `android/local.properties.example` to `android/local.properties`.
- Set `sdk.dir` to your Android SDK path.

Environment preflight:
```powershell
cd android
powershell -ExecutionPolicy Bypass -File .\scripts\check-env.ps1
```

Optional (configure online variants API endpoint):
```powershell
.\gradlew.bat assembleDebug -PvariantApiEndpoint=https://your-endpoint/path
```

API contract:
- See `docs/variants_api.md` for request/response schema expected by Android online variant fetcher.

Optional (switch reducer path to shared-core bridge):
```powershell
.\gradlew.bat assembleDebug -PuseSharedCoreReducer=true
```

Compile Kotlin only:
```powershell
.\gradlew.bat :app:compileDebugKotlin
```

Run:
- Open `android/` in Android Studio and run the `app` configuration on device/emulator.

Known environment issue:
- If Java fails with `Failed setting boot class path`, verify the local JDK installation and Java runtime environment first (this blocks Gradle before project code compilation starts).

## HarmonyOS

Location: `harmonyos/`

Build & run:
1. Open `harmonyos/` in DevEco Studio.
2. Configure signing and target device/emulator.
3. Run the entry module.

Current shell status:
- Session page includes a local `SessionShellController` and mock ASR/TTS controls for interaction testing.
- Session flow now uses shared-core-compatible `dispatch(event)` naming to ease future bridge swap-in.
- Session reducer path supports `SharedCoreRuntime` abstraction with local fallback for bridge migration.
- Session page exposes reducer-path status (`runtime`/`local`) plus last event/actions, runtime fallback reason, dispatch/runtime/local counters, recent trace lines, and `Use Runtime` / `Use Local` / `Reset Debug` actions for integration debugging.
- Runtime bridge hooks expected by shell: `__vmCreateSessionDriverStateJson()` and `__vmReduceSessionDriverJson(stateJson, eventJson)`.
- Optional delegate hooks for native/shared-core runtime: `__vmSharedCoreDelegateCreateSessionDriverStateJson()` and `__vmSharedCoreDelegateReduceSessionDriverJson(stateJson, eventJson)`.
- Hook mode probe: `__vmGetSessionDriverHookMode()` (`mock` or `delegate`).
- Bridge host also exports ArkTS helper APIs: `registerSharedCoreDelegateHooks(...)`, `clearSharedCoreDelegateHooks()`, `getSharedCoreHookMode()`.
- Demo delegate helper file: `shared_core_delegate_demo.ts`; Session page has `Enable Delegate` / `Disable Delegate` plus fault-injection controls (`Bad JSON` / `Bad Actions` / `Bad State`) and one-click checks (`Check Runtime On` / `Check Bad JSON` / `Check Bad Actions` / `Check Bad State` / `Check Runtime Off` / `Check All Faults`), expected to validate runtime path (`RuntimeOn`) and fallback reasons `runtime-invalid-json` / `runtime-actions-invalid` / `runtime-state-invalid` / `runtime-disabled`.
- Speech mock now supports timed ASR scripts and script-state callbacks; Session page includes multi-scenario auto scripts (`Auto Happy` / `Auto Hint` / `Auto Stop` / `Auto Delegate`) with real-time running/pending status.
- Hook installer is wired in `MainApp`; current hook body is a mock driver placeholder that can be replaced with real shared-core runtime calls.
- Settings page reads/writes mock preferences through local in-memory storage.

## HarmonyOS NEXT

Location: `harmony-next/`

Build & run:
1. Open `harmony-next/` in DevEco Studio NEXT.
2. Configure signing and target device/emulator.
3. Run the entry module.

Current shell status:
- Session page includes a local `SessionShellController` and mock ASR/TTS controls for interaction testing.
- Session flow now uses shared-core-compatible `dispatch(event)` naming to ease future bridge swap-in.
- Session reducer path supports `SharedCoreRuntime` abstraction with local fallback for bridge migration.
- Session page exposes reducer-path status (`runtime`/`local`) plus last event/actions, runtime fallback reason, dispatch/runtime/local counters, recent trace lines, and `Use Runtime` / `Use Local` / `Reset Debug` actions for integration debugging.
- Runtime bridge hooks expected by shell: `__vmCreateSessionDriverStateJson()` and `__vmReduceSessionDriverJson(stateJson, eventJson)`.
- Optional delegate hooks for native/shared-core runtime: `__vmSharedCoreDelegateCreateSessionDriverStateJson()` and `__vmSharedCoreDelegateReduceSessionDriverJson(stateJson, eventJson)`.
- Hook mode probe: `__vmGetSessionDriverHookMode()` (`mock` or `delegate`).
- Bridge host also exports ArkTS helper APIs: `registerSharedCoreDelegateHooks(...)`, `clearSharedCoreDelegateHooks()`, `getSharedCoreHookMode()`.
- Demo delegate helper file: `shared_core_delegate_demo.ts`; Session page has `Enable Delegate` / `Disable Delegate` plus fault-injection controls (`Bad JSON` / `Bad Actions` / `Bad State`) and one-click checks (`Check Runtime On` / `Check Bad JSON` / `Check Bad Actions` / `Check Bad State` / `Check Runtime Off` / `Check All Faults`), expected to validate runtime path (`RuntimeOn`) and fallback reasons `runtime-invalid-json` / `runtime-actions-invalid` / `runtime-state-invalid` / `runtime-disabled`.
- Speech mock now supports timed ASR scripts and script-state callbacks; Session page includes multi-scenario auto scripts (`Auto Happy` / `Auto Hint` / `Auto Stop` / `Auto Delegate`) with real-time running/pending status.
- Hook installer is wired in `MainApp`; current hook body is a mock driver placeholder that can be replaced with real shared-core runtime calls.
- Settings page reads/writes mock preferences through local in-memory storage.
