# VerseMentor

VerseMentor is a cross-platform poem recitation app organized as a monorepo.

## Repo layout

- `shared-core/` TypeScript session reducer, matching, intent parsing, and bridge contract logic (ArkTS compatible)
- `android/` Android app shell (Kotlin + Compose)
- `harmonyos/` HarmonyOS shell (ArkTS)
- `harmony-next/` HarmonyOS NEXT shell (ArkTS)
- `docs/` platform build/run guides and contracts

## Quick local verification (Windows)

From repo root:

```powershell
.\scripts\verify-local.cmd
```

This runs:
- Android JDK 17 auto-resolve
- Android environment preflight
- Android `:app:compileDebugKotlin`
- Android `:app:testDebugUnitTest`
- `shared-core` build (`npm.cmd run build`)
- `shared-core` bridge build (`npm.cmd run build:bridge`)
- `shared-core` unit tests (`npm.cmd test`)

Optional flags:

```powershell
.\scripts\verify-local.cmd -SkipAndroid
.\scripts\verify-local.cmd -SkipSharedCore
.\scripts\verify-local.cmd -SkipSharedCoreBuild
.\scripts\verify-local.cmd -SkipSharedCoreBridgeBuild
.\scripts\verify-local.cmd -SkipSharedCoreTests
.\scripts\verify-local.cmd -SkipUnitTests
.\scripts\verify-local.cmd -DryRun
```

## Platform entry points

- Shared core: [docs/README.md](docs/README.md#shared-core-typescript--arkts-compatible)
- Android: [docs/android.md](docs/android.md)
- HarmonyOS: [docs/README.md](docs/README.md#harmonyos)
- HarmonyOS NEXT: [docs/README.md](docs/README.md#harmonyos-next)
- Variants API contract: [docs/variants_api.md](docs/variants_api.md)
