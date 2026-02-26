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

## HarmonyOS NEXT

Location: `harmony-next/`

Build & run:
1. Open `harmony-next/` in DevEco Studio NEXT.
2. Configure signing and target device/emulator.
3. Run the entry module.
