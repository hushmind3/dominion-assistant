# 도미니온 — Android native client

This is a **standalone Android SDK + Kotlin + Jetpack Compose** application, not an Expo prebuild or a WebView. It has its own application ID (`com.dominion.assistant`) and can be installed alongside the existing `com.dominion.voice` React Native client.

## Toolchain (2026-11-25)

- Android Studio with Android SDK 37, JDK 21
- Android Gradle Plugin 9.5.2; Gradle 9.6.0
- Kotlin Compose compiler plugin 2.4.21; Compose BOM 2026.09.00
- AndroidX Activity Compose 1.13.0; Lifecycle 2.11.0

Import `android-native/` directly in Android Studio. Alternatively install Gradle 9.6.0, set ANDROID_HOME and run `gradle :app:assembleDebug :app:testDebugUnitTest` inside `android-native/`. The CI-only workflow `Native Android debug build` has a manual trigger and does not publish to production. This source tree currently includes a Gradle distribution pin but **does not contain the binary Gradle wrapper JAR**; run `gradle wrapper --gradle-version 9.6.0` to generate a local standard wrapper if desired. Never download random wrapper binaries from third-party repositories.

## Implemented in source

| Layer | Native implementation |
| --- | --- |
| Android UI | Compose chat, model picker, work center, settings and account screen |
| Authentication | Same existing DOMINION Cloud app-pairing flow, verified Google account, Android browser approval |
| Cloud chat | Existing `/v1/models` and `/v1/chat/completions` with no provider keys on device |
| Local mirror | Account-scoped SQLite journal (max 200 turns) |
| Token storage | Android Keystore AES-GCM, no plaintext token preferences, Android backup disabled |
| Google work | Read-only Gmail, Calendar and Drive queued jobs, OAuth consent in external browser |
| Browser work | Explicit start, per-action approval, handoff, resume, cancel, Android document picker; 2 MB PDF/JPEG/PNG checks |
| Work compatibility | New `/v1/assistant/work/*` dispatcher when deployed; fallback to existing Google/browser endpoints |
| Voice | Android SpeechRecognizer/TTS for tap-to-talk; LiveKit Android SDK 2.31.0 for full-duplex WebRTC voice |
| Android call service | Microphone foreground service owns the LiveKit room; ongoing notification supports mute/stop, app background does not force disconnect |
| PC pairing | Existing authenticated Cloud WebSocket RPC starts/stops the PC bot, retrieves short-lived LiveKit room credentials |
| PC task control | PC selection, submit, durable task-history refresh, confirmed queued-task cancellation and owner inbox |
| Native push | Firebase Messaging SDK (BoM 35.2.0), explicit opt-in/out, token refresh via WorkManager 2.12.0, notifications open work tab |
| Server-defined UI | Versioned schema of allowlisted Google buttons and browser forms, rendered by compiled Compose components; fallback to original static panels |
| Android assistant role | VoiceInteractionService + SessionService, launch entry point; registration depends on system setting |

## Not yet ported / verified

The existing Expo messenger's **general PC relay commands beyond task/history/cancel/voice**, rich attachments within ordinary chat, camera, profiles, full shared conversation journal synchronization, standalone on-device conversation engine and arbitrary layout components are **not yet ported**. The native FCM and bounded server-driven work menu are implemented in source, but **production credentials and physical-device verification are pending**. PC voice calls require a reachable live DOMINION PC voice service configured with LiveKit, STT and TTS; no standalone Cloud-owned voice agent is implemented. The new native LiveKit room and microphone foreground service are **implemented in source but not compiled or tested on a physical Android device**. OS assistant registration is only a launch/session baseline: it **does not** implement hotword listening or background AI conversation. No real Android build, physical-device UX validation, Cloud deployment, or binary signature check has been claimed at the time of this commit.

The client does not evaluate server-supplied JavaScript. A future server-driven UI layer should implement a tightly constrained, versioned schema of Compose components and explicit user actions, not unrestricted QuickJS remote code. Do not copy Pickle assets or decompiled sources.

## Security and compatibility

- Fixed HTTPS Cloud origin; redirects disabled. No custom URLs, host LAN permissions, or model API keys.
- Every Google and browser operation goes through the Cloud's already-scoped app session and approvals.
- Browser file names, MIME, signatures, and maximum length are validated on the device and validated again by the server.
- Android tap-to-talk speech recognition requires foreground UI. Separately, an **active user-started LiveKit call** uses a microphone-type foreground service and may continue when the screen is locked. Starting a microphone foreground service from background is not attempted.
- Android 13+ notification permission is requested at call start; denial does not authorize background service creation. Platform battery controls and OEM policies may still terminate calls.
- Incoming remote PC voice-start/stop commands never automatically retry after an uncertain RPC outcome. Call-end attempts best-effort PC stop once.
- The old `mobile/` app and its APK workflow remain untouched. The `main` branch and production deployment remain unchanged.

References: https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler ; https://developer.android.com/develop/background-work/services/fgs/service-types ; https://docs.livekit.io/transport/sdk-platforms/android/ ; https://github.com/livekit/client-sdk-android/releases/tag/v2.31.0


## Native FCM setup (operator action required)

FCM is intentionally **opt-in**. The source builds without a Firebase project and the app shows “Firebase 프로젝트 설정이 필요합니다.”. In that mode, in-app refresh of durable work and inbox continues to work, but push notifications cannot be delivered with the app closed.

1. Register an Android Firebase app with package `com.dominion.assistant`; download its **project-specific** `google-services.json` to `android-native/app/google-services.json` (gitignored). The Gradle Google Services plugin applies only when the file exists.
2. Enable **Firebase Cloud Messaging API (HTTP v1)** in the Google Cloud project. Grant a dedicated Google Cloud service account the **Firebase Cloud Messaging API Admin** role for that project.
3. On the companion Cloud Worker feature branch `assistant-native-fcm`, set `ASSISTANT_PUSH_ENABLED=1` and `ASSISTANT_FCM_PROJECT_ID`, `ASSISTANT_FCM_SERVICE_EMAIL` variables. Store the **PKCS8 PEM private key only in a Cloudflare secret** `ASSISTANT_FCM_PRIVATE_KEY`. Do not put it in the Android project or repository. Deploy the reviewed companion Cloud branch separately.
4. Log into DOMINION Cloud on the phone, go to Settings → 알림 → 알림 켜기, grant Android notification permission and confirm that registration returns successfully. The app stores its native FCM token under the existing owner-scoped push API as `fcm:<token>`.
5. Check delivery of a generic PC message or browser-job notification on a real device. Verify notification click opens the work tab and that opt-out unregisters the old token. FCM delivery is best-effort; the durable inbox remains authoritative.

Expo clients keep their existing ExpoPushToken registration and notification path, without requiring Firebase Android configuration.

The Cloud menu endpoint `GET /v1/assistant/work/ui` returns versioned JSON for **known** Google read actions and a web-task URL/goal form. Native client discards unknown action codes, scripts, oversized descriptions and unsupported schema versions. It never evaluates JavaScript downloaded from the server.

## Native app parity work (2026-11-25)

- Assistant profile: local name, voice/style instructions, language, 20 editable memory notes, 6 avatars and a compressed local JPEG photo; optional explicit PC profile sync.
- Conversation-first home keeps the ordinary AI chat and the in-call expanded chat on the **same messages and same account-scoped saved draft**. The in-call sheet expands through 0% → 50% → 100% without ending the call. The compact `＋` menu opens errands, PC conversation/attachments, voice and settings; a secondary drawer keeps only chat/call/settings.
- PC conversation remains available from `＋` → `PC와 대화·파일`. It retrieves PC task status and mirrors up to 200 turns **per account and PC** to the local SQLite database, with offline cached viewing. It is still a distinct PC journal, not synchronized with the general AI chat.
- PC conversation accepts an approved document or camera photo. Cloud WebSocket RPC uses an 850KB application-enforced body limit; native attachment content is capped at 512KiB after optional on-device JPEG compression. Larger documents require PC-local transfer.
- Local reminders use Android WorkManager, not Firebase: schedule, list and cancel, with on-device notifications and Android 13+ consent. Delivery is **inexact** under power management.
- **Legacy Expo chat remains stored in Expo's private SQLite**, not automatically shared with the native app. PC task history is synchronized from the PC; this is not full Expo chat sync.
- Native APK build workflow is **manual dispatch only**. Source-only UI changes have not triggered a build. Device-level microphone, camera, FCM or PC network operation remains unverified.

## Conversation-driven errands, email and PDF delivery (2026-11-25)

- The existing Cloud chat completion now yields validated proposals for read-only Google queries, HTTPS browser tasks, web search, mail-watch subscriptions and missing-information questions. All visible requests originate from normal chat or the expanded in-call chat. Unsupported actions are rejected rather than executed.
- Browser, Google and mail-watch proposals are saved per-account in SQLite (database version 5) and require an explicit user tap before submission. The generated request ID remains stable; unclear network outcomes are checked by ID/status rather than replaying actions blindly. Existing conversation and profile tables are upgraded without dropping rows.
- Cloud browser/Google status polling populates concise in-thread errand updates and records terminal results **once** into the same account's conversation. Cloud mail-watch alerts are mirrored into the same thread with account-scoped duplicate suppression.
- Browser jobs' PDF metadata can be shown directly in chat and the work sheet. Downloads reuse the existing Cloud browser/document API and validate returned document ID, MIME, size, PDF signature and SHA-256 digest before Android FileProvider sharing. Site PDF (original) and printed page PDF (page) remain clearly distinguished.
- The plus action menu and contextual work sheets remain reachable while an active voice call is connected, with the existing 0/50/100% swipe-up chat interaction intact.
- Existing Cloud browser limits, per-site approval/handoff behavior, incomplete full-conversation sync, device-dependent voice/FCM behavior and missing general-purpose autonomous multi-tool workflow still apply. **Hometax end-to-end registration and receipt/PDF issuance have NOT been proven.**
- This is a **source-only prototype** of the native integration. Android Gradle compilation, unit tests, real device testing, real Google/Firebase/Cloud integration, APK packaging and deployment were **not executed in this change**. Native build workflow remains manual-only; no branch was merged to main.
