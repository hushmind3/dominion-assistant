# DOMINION Assistant — official Android native client

This is a **standalone Android SDK + Kotlin + Jetpack Compose** application, not an Expo prebuild or a WebView. It has its own application ID (\`com.dominion.assistant\`) and can be installed alongside the existing \`com.dominion.voice\` React Native client.

## Toolchain (2026-11-25)

- Android Studio with Android SDK 37, JDK 21
- Android Gradle Plugin 9.5.2; Gradle 9.6.0
- Kotlin Compose compiler plugin 2.4.10; Compose BOM 2026.09.00
- AndroidX Activity Compose 1.13.0; Lifecycle 2.11.0

Import \`android-native/\` directly in Android Studio. Alternatively install Gradle 9.6.0, set ANDROID_HOME and run \`gradle :app:assembleDebug :app:testDebugUnitTest\` inside \`android-native/\`. The CI-only workflow \`Native Android debug build\` has a manual trigger and does not publish to production. This source tree currently includes a Gradle distribution pin but **does not contain the binary Gradle wrapper JAR**; run \`gradle wrapper --gradle-version 9.6.0\` to generate a local standard wrapper if desired. Never download random wrapper binaries from third-party repositories.

## Implemented in source

| Layer | Native implementation |
| --- | --- |
| Android UI | Compose chat, model picker, work center, settings and account screen |
| Authentication | Same existing DOMINION Cloud app-pairing flow, verified Google account, Android browser approval |
| Cloud chat | Existing \`/v1/models\` and \`/v1/chat/completions\` with no provider keys on device |
| Local mirror | Account-scoped SQLite journal (max 200 turns) |
| Token storage | Android Keystore AES-GCM, no plaintext token preferences, Android backup disabled |
| Google work | Read-only Gmail, Calendar and Drive queued jobs, OAuth consent in external browser |
| Browser work | Explicit start, per-action approval, handoff, resume, cancel, Android document picker; 2 MB PDF/JPEG/PNG checks |
| Work compatibility | New \`/v1/assistant/work/*\` dispatcher when deployed; fallback to existing Google/browser endpoints |
| Voice | Android SpeechRecognizer (tap to talk) and TextToSpeech; app-visible foreground interaction only |
| Android assistant role | VoiceInteractionService + SessionService, launch entry point; registration depends on system setting |

## Not yet ported / verified

The existing Expo messenger, PC relay WebSocket protocol, push updates, notification actions, attachments within ordinary chat, camera, profiles, live WebRTC voice room, truly continuous duplex talk, microphone foreground service, and dynamic server-provided Compose component definitions are **not yet ported**. OS assistant registration is only a launch/session baseline: it **does not** implement hotword listening or background AI conversation. No real Android build, physical-device UX validation, Cloud deployment, or binary signature check has been claimed at the time of this commit.

The client does not evaluate server-supplied JavaScript. A future server-driven UI layer should implement a tightly constrained, versioned schema of Compose components and explicit user actions, not unrestricted QuickJS remote code. Do not copy Pickle assets or decompiled sources.

## Security and compatibility

- Fixed HTTPS Cloud origin; redirects disabled. No custom URLs, host LAN permissions, or model API keys.
- Every Google and browser operation goes through the Cloud's already-scoped app session and approvals.
- Browser file names, MIME, signatures, and maximum length are validated on the device and validated again by the server.
- No background microphone access. Speech recognizer availability depends on Android's installed provider and runtime microphone permission.
- The old \`mobile/\` app and its APK workflow remain untouched. The \`main\` branch and production deployment remain unchanged.

Reference: https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler and https://developer.android.com/reference/android/service/voice/VoiceInteractionService
