# HarmonyOS NEXT Build & Run

This is a minimal ArkTS shell.

Steps:
1. Open `harmony-next/` in DevEco Studio (NEXT).
2. Configure signing and device/emulator.
3. Run the entry module.

Notes:
- Speech and storage adapters are stubs in `entry/src/main/ets/shared/` and need platform service wiring.
- Hook the shared-core FSM by dispatching ASR events and handling actions to TTS and UI.
