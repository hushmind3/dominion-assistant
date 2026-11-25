# DOMINION Assistant — development state (2026-11-25)

## Canonical branch and mobile target
- Develop on **`hushmind3/dominion-assistant:main`** directly; do not create per-feature branches by default.
- **`android-native/`** (Kotlin + Jetpack Compose) is the Android deliverable.
- **`mobile/`** (Expo) is the original UI/UX specification, not the shipping Android client. Keep it read-only until visual and interaction parity is tested on a device.
- Use the original Expo tokens and interaction behavior. Do not redesign navigation, colors, profile, gestures or chat UX without an explicit design decision.
- Do not merge old unverified PRs wholesale or claim that written source is a working release.

## Code now in main
- Native chat and local account conversation, DOMINION Cloud login/models.
- Native LiveKit voice session and on-device speech recognizer/TTS code.
- Existing PC task RPC, Gmail/Calendar/Drive read jobs, cloud browser jobs and PDF sharing.
- Local reminders, opt-in FCM notification implementation, Android assistant role scaffolding.
- Explicit chat-to-device Android handoffs: phone dialer, SMS composer, contacts, calendar, camera, settings, map search, HTTPS page and text sharing. These open system UI and **do not autonomously call or send**.
- Composer mic/call placement, editable assistant identity, reminders navigation, primary dark colors aligned with the existing Expo UI.
- Android native build workflow: triggers on `main` Android changes and runs source UI contract checks, debug compilation and unit tests. A successful job is needed before calling these verified.

## Still incomplete, not verified
1. **Debug APK compiled and CI source/unit checks passed** at commit `b2f5089` ([Actions run](https://github.com/hushmind3/dominion-assistant/actions/runs/45590078287)); actual Android-device behavior, exact UI screenshot parity and provider/account integration still need hands-on checks.
2. Generic third-party Android app inspection/navigation via AccessibilityService is **not implemented**; basic system intent handoffs are not cross-app automation.
3. Autonomous multi-step tool planning, tool-result feedback/retries and a reusable durable server-side playbook executor are **not complete**.
4. Google OAuth, Browser Run, FCM, LiveKit, PC connection and Cloud migrations require actual operator configuration and end-to-end tests, not source-only claims.
5. Full cross-device/Expo conversation migration, hotword/background assistant and unrestricted native app control are **not implemented**.
6. The existing Expo reference has not yet been removed. Remove legacy code only after parity and data migration, never before.
7. UI preview HTML/PNG is an illustrative rendering, **not a screenshot of a running Kotlin APK**.

## Operational development rule
Choose one functionality, implement through execution, test and commit on `main`; record failures and remaining dependencies here for session continuity. Preserve existing UX. No automatic app installations, approvals, payments, calls or form submission unless implemented and authorized.

## CI
- [Android native debug build](https://github.com/hushmind3/dominion-assistant/actions/workflows/android-native-debug.yml)
- [Main branch](https://github.com/hushmind3/dominion-assistant/tree/main)
