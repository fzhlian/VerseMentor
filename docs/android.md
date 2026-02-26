# Android Build & Run

Prereqs (clean machine):
- Install Android Studio (Giraffe or newer).
- Install JDK 17.
- In Android Studio SDK Manager, install Android SDK 34 + build-tools.

Environment setup (Windows):
1. Set `JAVA_HOME` to your JDK 17 path.
2. Set `ANDROID_HOME` to your SDK path (optional for Android Studio, needed for CLI).
3. Add `%JAVA_HOME%\bin` to PATH.

Build from CLI:
```
cd android
.\gradlew.bat assembleDebug
```

Run:
- Open `android/` in Android Studio and run the `app` configuration.

Notes:
- Microphone permission is required for ASR.
- TTS requires a Chinese voice installed on the device.
