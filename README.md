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

`verify-local` now retries `shared-core` tests once when process exits with `134` (observed intermittent Node/V8 teardown crash), so transient runtime exits do not fail the full validation immediately.

Optional flags:

```powershell
.\scripts\verify-local.cmd -SkipAndroid
.\scripts\verify-local.cmd -SkipSharedCore
.\scripts\verify-local.cmd -SkipSharedCoreBuild
.\scripts\verify-local.cmd -SkipSharedCoreBridgeBuild
.\scripts\verify-local.cmd -SkipSharedCoreTests
.\scripts\verify-local.cmd -SkipHarmonySync
.\scripts\verify-local.cmd -SkipUnitTests
.\scripts\verify-local.cmd -DryRun
```

Harmony shared file utilities:

```powershell
.\scripts\check-harmony-shared-sync.ps1
.\scripts\sync-harmony-shared.ps1 -DryRun
.\scripts\sync-harmony-shared.ps1
.\scripts\check-harmony-shared-sync.cmd
.\scripts\sync-harmony-shared.cmd -DryRun
```

Android one-click release (build + GitHub Release upload):

```powershell
.\scripts\release-android.cmd
```

Optional flags:

```powershell
.\scripts\release-android.cmd -Tag v0.4.9
.\scripts\release-android.cmd -SkipBuild
.\scripts\release-android.cmd -SkipPush
.\scripts\release-android.cmd -Draft
.\scripts\release-android.cmd -Prerelease
.\scripts\release-android.cmd -AllowDirty
.\scripts\release-android.cmd -DryRun
```

## Platform entry points

- Shared core: [docs/README.md](docs/README.md#shared-core-typescript--arkts-compatible)
- Android: [docs/android.md](docs/android.md)
- HarmonyOS: [docs/README.md](docs/README.md#harmonyos)
- HarmonyOS NEXT: [docs/README.md](docs/README.md#harmonyos-next)
- Variants API contract: [docs/variants_api.md](docs/variants_api.md)

## Recent Development Notes

- Shared-core poem title matching now follows: exact -> title-contained utterance -> fuzzy with a minimum score filter.
- Shared-core and Android title matching now strip common spoken fillers and collapse repeated title tails (for example `静夜思静夜思`).
- Android local reducer now aligns with shared-core for `CONFIRM_POEM_CANDIDATE`, `WAIT_DYNASTY_AUTHOR`, `RECITE_READY`, `HINT_OFFER`, and title-timeout baseline handling.
- Android UI now maps `RECITE_READY` to localized status text (`status_recite_ready`) instead of falling back to raw enum names.
- Android home background drawable (`android/app/src/main/res/drawable/home_background.png`) is now synced from repo image `Background-no.png`.
- Android ASR flow now includes expected client-error suppression for app-triggered stop, transient-error delayed retry with configurable threshold/delay, and permission/infrastructure error pause handling.
- Android listening start path now ignores duplicate start triggers (ViewModel + SpeechIO guards) to reduce recognizer-busy churn.
- HarmonyOS and HarmonyOS NEXT local/runtime mock/delegate title matching now use a shared `title_matcher.ts` utility to avoid path drift.
- `scripts/verify-local.ps1` now includes Harmony/Harmony NEXT shared-file parity checks (`-SkipHarmonySync` to opt out).
