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

Run:
- Open `android/` in Android Studio and run the `app` configuration.

Notes:
- Microphone permission is required for ASR.
- Internet permission is included for optional online variant fetching.
- TTS requires a Chinese voice installed on the device.
- `FETCH_VARIANTS` now resolves via local cache (`SharedPreferences`) with TTL and falls back to local poem lines when no online source is configured.
- Reciting score now uses both standard line text and cached variants for the current line (best score wins).
- Shared-core reducer bridge wiring exists and is controlled by Gradle property `-PuseSharedCoreReducer=true`.

Optional online variants endpoint:
- Pass `-PvariantApiEndpoint=https://your-endpoint/path` to Gradle.
- Runtime query format is `?title=<...>&author=<...>&dynasty=<...>`.
- Response contract is documented in `docs/variants_api.md`.

Optional reducer switch:
- Pass `-PuseSharedCoreReducer=true` to build with shared-core reducer bridge enabled.
