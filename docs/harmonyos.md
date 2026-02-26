# HarmonyOS Build & Run

This is a minimal ArkTS shell.

Steps:
1. Open `harmonyos/` in DevEco Studio.
2. Configure signing and device/emulator.
3. Run the entry module.

Notes:
- Speech adapter in `entry/src/main/ets/shared/speech_impl.ts` now provides a local mock implementation for development.
- Replace the mock with HarmonyOS speech recognizer and TTS services for production.
- Hook the shared-core FSM by dispatching ASR events and handling actions to TTS and UI.
