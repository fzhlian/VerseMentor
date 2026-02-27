# Android Build & Run

Prereqs (clean machine):
- Install Android Studio (Giraffe or newer).
- Install JDK 17.
- In Android Studio SDK Manager, install Android SDK 34 + build-tools.

Environment setup (Windows):
1. Set `JAVA_HOME` to your JDK 17 path.
2. Set `ANDROID_HOME` to your SDK path (optional for Android Studio, needed for CLI).
3. Add `%JAVA_HOME%\bin` to PATH.
4. If CLI build cannot find SDK, create `android/local.properties` from `android/local.properties.example` and set `sdk.dir`.

Build from CLI:
```
cd android
.\gradlew.bat assembleDebug
```

Preflight check:
```
cd android
powershell -ExecutionPolicy Bypass -File .\scripts\check-env.ps1
```
The preflight now validates both JDK file layout and command invocation (`java -version` / `javac -version`), so broken JDK installs fail fast with actionable hints.
When Java validation fails, the script also scans common install paths and prints complete JDK candidates (`bin/java.exe` + `lib/modules`) to help you repoint `JAVA_HOME`.
You can auto-resolve a local JDK 17 via `.\scripts\ensure-jdk17.ps1 -AutoDownload`.
You can run Gradle with auto-managed JDK via `.\scripts\gradlew-with-jdk.ps1 :app:compileDebugKotlin` (or any other Gradle task list).
If PowerShell execution policy blocks `.ps1`, use wrapper commands: `.\scripts\ensure-jdk17.cmd -AutoDownload` and `.\scripts\gradlew-with-jdk.cmd :app:testDebugUnitTest`.
For preflight in the same environment, use `.\scripts\check-env.cmd`.
From repo root, you can run one command for end-to-end local verification: `.\scripts\verify-local.cmd`.
By default it runs Android preflight/compile/unit tests plus `shared-core` build (`npm.cmd run build`), bridge build (`npm.cmd run build:bridge`), and tests (`npm.cmd test`).
If you only want shared-core tests and want to skip build steps, pass `-SkipSharedCoreBuild`.
If you want to keep `shared-core` main build but skip bridge build, pass `-SkipSharedCoreBridgeBuild`.
If you only want shared-core builds and want to skip shared-core tests, pass `-SkipSharedCoreTests`.
Use `-DryRun` to print planned steps without executing commands.

Run:
- Open `android/` in Android Studio and run the `app` configuration.

Notes:
- Microphone permission is required for ASR.
- Internet permission is included for optional online variant fetching.
- TTS requires a Chinese voice installed on the device.
- `FETCH_VARIANTS` now resolves via local cache (`SharedPreferences`) with TTL and falls back to local poem lines when no online source is configured.
- Reciting score now uses both standard line text and cached variants for the current line (best score wins).
- Shared-core reducer bridge wiring exists and is controlled by Gradle property `-PuseSharedCoreReducer=true`.
- Session control intents now include voice exit (`退出` / `结束` / `停止` / `不背了`) and voice repeat (`再说一遍` / `重复`) on active states.
- `USER_UI_STOP` now exits from any active session state with deterministic `STOP_LISTENING`, consistent with shared-core reducer behavior.
- `USER_UI_START` now restarts from active/exit states and resets current poem context, consistent with shared-core reducer behavior.
- Android event bridge now forwards optional `now` for `USER_UI_START` and `USER_ASR`, improving deterministic timeout baselines across reducer paths.
- `Check ASR Error` debug output now includes bridge reduce trace (`runtime` or `local/<fallback-reason>`) so runtime-vs-fallback path is visible during validation.
- In `USE_SHARED_CORE_REDUCER=true` mode, `Check ASR Error` is marked `PASS` only when reducer actions are correct and bridge trace path is `runtime`.
- Settings debug panel now includes `Check Bridge Event`, validating event round-trip encode/decode for `USER_UI_START.now` and `USER_ASR(confidence/now)`.
- Settings debug panel now includes `Check All Bridge`, aggregating `Check Bridge Event` + `Check Bridge Codec` + `Check Runtime Path` + `Check ASR Error` into one PASS/FAIL summary.
- Settings debug panel now includes `Check Bridge Codec`, validating JSON encode/decode and `LocalBridgeSharedCoreRuntime` reduce output contract; decode failures now expose `output-invalid-json` / `output-actions-invalid` / `output-state-invalid`, including malformed or field-missing payloads.
- Settings debug panel now includes `Check Runtime Path`, which validates `runtime/delegate-hook` path plus expected start actions/state before reporting `PASS`.
- Shared-core runtime now defaults to `DelegateSharedCoreRuntime`: it first tries `SharedCoreRuntimeHooks.registerReduceHook { stateJson, eventJson -> ... }`, and falls back when no hook is registered.
- App bootstrap now registers `LocalBridgeSharedCoreRuntime` as default reduce hook when `USE_SHARED_CORE_REDUCER=true` and no hook exists yet; registration returns a token and teardown clears only that token, avoiding accidental cleanup of newer hook owners.
- Built-in helper runtime `LocalBridgeSharedCoreRuntime` is available for contract smoke tests (JSON-in/JSON-out through codec + local reducer) and can be registered as hook.
- Local JVM unit tests now cover `SharedCoreRuntimeHooks` token semantics, `DelegateSharedCoreRuntime` hook/fallback dispatch, `SharedCoreBridge` runtime/fallback trace reasons (`runtime-null` / `runtime-invalid-json` / `runtime-actions-invalid` / `runtime-state-invalid` / `runtime-throw`), `SharedCoreCodec` event/state/output contract round-trip (including `USER_UI_START.now` and `USER_ASR.confidence/now`) plus decode-failure reasons for malformed/field-missing payloads, and `SessionFsm` intent regressions (`USER_UI_STOP`, `USER_UI_START`, voice exit, voice repeat).

Optional online variants endpoint:
- Pass `-PvariantApiEndpoint=https://your-endpoint/path` to Gradle.
- Runtime query format is `?title=<...>&author=<...>&dynasty=<...>`.
- Response contract is documented in `docs/variants_api.md`.

Optional reducer switch:
- Pass `-PuseSharedCoreReducer=true` to build with shared-core reducer bridge enabled.
