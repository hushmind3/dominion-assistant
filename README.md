# DOMINION Assistant

Independent personal AI assistant development repository. Migrated from dominion-rust / dominion-sub3 at commit `a05d47cc07ccc9ef3f0086e9078d305ef7e22b4a`.

- Root: existing Python voice service, web interface, LiveKit/Pipecat and SIP integration, Dockerfile, protocol tests.
- mobile/: existing Android/iOS Expo client, personal Wi-Fi mode and optional live voice mode.
- host-reference/: existing Rust and React host integration sources for reference, not an independently compilable server.
- docs/plugin-package-v1.md: shared package discovery and installation contract.
- .github/workflows/: independent voice checks and Android APK build.

## Migration status
The old assistant implementation is preserved, not a finished redesign. The personal LAN mobile mode still requires a compatible host adapter. The legacy voice image requires a bridge token supplied by a compatible host. Full tool/task capability, persistent pairing, mobile UX redesign and cross-branch runtime verification remain development work. No install manifest is advertised until a compatible image and host handshake are verified. Successful APK compilation does not establish end-to-end operation.

Existing migrated source retains its original Apache-2.0 license (LICENSE-UPSTREAM); the repository's existing LICENSE does not supersede upstream licensing.

## Development
Run `python3 -m unittest discover -s . -p 'test_*.py'`. In mobile/, run `npm install`, `npm run typecheck`, and `npm run apk`. The Dockerfile builds the voice service; a compatible DOMINION host supplies DOMINION_BRIDGE_TOKEN and polls the request/result protocol. No credentials are committed.

## Common plugin SDK v2
The assistant now implements the language-neutral host-pull capability, request and event endpoints, with persistent event acknowledgements in /data. It sends ordinary utterances as task requests; the host chooses the permitted tools, model and approvals. The web interface displays actual host progress. This is not a new mobile pairing implementation; the old LAN client still requires its prior host adapter. Container updates and installation use the independent package marketplace.

## Published plugin package
The `Publish OCI plugin` workflow tests the real service, pushes a commit-tagged GHCR image, verifies that the exact digest can be pulled without credentials, and only then writes a digest-pinned `dominion-plugin.json`. If the package is private, the workflow stops instead of advertising a broken installation. Make the GHCR package public and rerun the workflow. Search by `hushmind3/dominion-assistant` in the DOMINION marketplace; add GitHub topic `dominion-plugin` to participate in general topic search. The v2 bridge is independent of any assistant feature implementation.

## 0.4 mobile assistant
The new default mobile client uses the independent Assistant service, not the legacy Rust personal_voice adapter. It supports a single-owner 1:1 timeline, chat/task intent, a durable FIFO queue (up to 20 unfinished requests, 200 retained turns), host event status, and cancellation of undispatched turns. Host-pull v2 still dispatches one job at a time; queued messages are not parallel execution. Chat and task permissions remain controlled by the host. Voice dictation and optional TTS use the phone; live-room voice and the legacy LAN client remain available separately.

For a local integration test:
1. Install/start the updated Assistant image on a host supporting SDK v2, grant the required scope, and note its loopback service port.
2. On the PC run `python3 lan_relay.py --bind <PC-private-IPv4> --target-port <assistant-loopback-port>`. This explicit relay forwards only the allowlisted native mobile routes. Do not expose it on the public internet or forward router ports; this is trusted Wi-Fi HTTP, not encrypted remote access.
3. In the Assistant web UI create a mobile pairing code, then enter the relay address and code in the mobile app. Pair codes expire after five minutes, tokens after 24 hours or service restart. The mobile app keeps its token in OS secure storage. Revocation does not cancel already queued/host work.
4. Send a chat and a task; verify real host events and answers on the phone. Check that a second queued message survives service restart. Do not infer runtime success from an APK build alone.

Photo/file transfer, remote TLS transport, persistent device credentials across server restart, parallel execution and streaming remain separate development work. No host branches are modified by this release.

### Mobile delegated calls
In the local web UI fill the existing LiveKit/Deepgram/Cartesia settings and SIP outbound trunk, then enable mobile phone calling. The provider credentials stay in server memory, not on the phone. On the phone open the call panel, enter the E.164 number and purpose, and explicitly confirm the real call. The voice bridge uses the existing SIP transport, discloses that it is AI, submits caller utterances as tool-free chat, and records bounded transcripts and call status locally. Lost responses use the same call ID; uncertain call attempts are not automatically retried after service restart. The PC operator must verify uncertain calls in LiveKit before clearing the review state. Real provider audio, call quality, and billing need device testing. Calls currently require the single shared host lane to be idle.

Closing an audio connection does not discard a host job already in progress: its terminal event is still accepted. Call transcripts retain the latest 20 generated exchanges per call; text generation is not proof that audio reached the other party.

### Mobile attachments
The mobile composer accepts camera/gallery images and UTF-8 text, PDF and DOCX (4 MiB per file). The assistant stores bounded originals and extracted evidence under its private /data directory. For PDF only the first 12 pages are read; scanned PDFs are not OCRed. The existing host SDK is text-only: document excerpts and actual vision-model output are passed as untrusted reference material, not raw multimodal SDK messages. Enable a vision endpoint explicitly in the local web UI or via ASSISTANT_VISION_URL, ASSISTANT_VISION_MODEL and optional ASSISTANT_VISION_KEY. No external provider is selected automatically. The endpoint must be OpenAI-compatible; local HTTP is for trusted private networks, remote endpoints require HTTPS. Vision credentials never go to mobile. The photo can leave the PC if an external endpoint is configured. Storage is limited to 100 files / 64 MiB; management UI and end-to-end attachment/provider validation remain work.

### Personalization and private remote access
The profile is owner-editable (name, response style, language and up to 20 memory notes). Context retrieval combines relevant earlier completed turns with recent turns and bounded owner notes; no claim of unlimited memory. Pairing stores only a bearer hash server-side and the actual token in mobile OS secure storage, valid for 30 days or until revoked. Service restart no longer requires re-pairing when /data is preserved.
For private access away from home, install Tailscale on the PC and mobile device and configure a private tailnet. Start the relay on the PC with `python3 lan_relay.py --bind 127.0.0.1 --proxy-only --target-port <assistant-loopback-port>` and run `tailscale serve --bg http://127.0.0.1:8765` (see https://tailscale.com/docs/reference/tailscale-cli/serve). Enter the issued HTTPS .ts.net URL in the app. Use Serve, not Funnel. The assistant does not configure the user's network or TLS automatically. Native client auth remains required, and the relay exposes only the scoped mobile routes. Verify connectivity and authorization on actual devices before use.

### In-app live voice
Configure the PC LiveKit and speech keys once per service start with the web UI's mobile voice button. On the app, open Call → voice call and approve the connection. The server mints a scoped token; no manual copying is needed. Confirm connection and enable the microphone. The app closes the scoped session when disconnected or backgrounded. Personal voice turns share the mobile timeline, while delegated calls stay separate. Interruption frames invalidate stale speech and retain the latest utterance for the next available host turn. This does not cancel running host jobs, provide token streaming, or create parallel execution. Audio and interruption behavior still need device/provider testing.

### Device planner
The mobile planner schedules and cancels native local notifications and opens the OS calendar's prefilled event form. Saving a calendar event remains an explicit system UI action. The phone panel can select a number from the system contact picker; the user must verify the international number and confirm any actual call. No entire address book is sent to the PC. Optional task notifications are emitted only for terminal transitions observed during the app's polling session, not guaranteed background push delivery. Full remote push, AI-generated calendar actions and device testing remain open.

### Optional parallel conversation lane
The PC administrator may explicitly configure an OpenAI-compatible conversation model in the web UI or via ASSISTANT_CHAT_URL/MODEL/KEY. The same existing model endpoint can be used; this is not a second agent harness. With this setting enabled, personal text chat and personal LiveKit voice turns go through a bounded tool-free model lane while executable tasks continue through the original DOMINION SDK host lane. The lanes have separate admission and completion state. Model-server concurrency and end-to-end latency still need measurement. No external provider is selected automatically. Model failures are shown and never silently executed as tasks. Without configuration all chat remains on the existing host path. Delegated phone calls still use the scoped host path and require an idle host. In-flight direct calls are marked interrupted after restart rather than automatically repeated.

### Default connection: shared DOMINION Cloud account (staged)
The mobile app now defaults to Google sign-in and a list of online PCs. The PC assistant web UI has the matching Google sign-in and explicit device registration; it uses the same account identity as DOMINION desktop but currently requires this one-time sign-in in the assistant UI. A scoped persistent PC token authorizes an outbound HTTPS polling relay; no Tailscale or port forwarding is needed. Existing personal Wi-Fi remains optional. Cloud Worker changes are isolated on `hushmind3/dominion-rust:assistant-cloud-link` and must be reviewed, migrated and deployed before the new default works. No cloud deployment, APK or container packaging has been triggered. Relay supports chat, tasks, profile, phone, voice and bounded attachment upload; cloud attachments are approximately 600 KB or less, historical images currently show metadata only and history snapshots may be truncated to fit D1. Cloud messages are temporarily stored by the Worker, not end-to-end encrypted. Live audio continues over LiveKit. The PC must be running for remote execution. Device revocation and user-specific model/billing reuse are distinct from model routing configuration; the app does not yet automatically configure a Cloud model.

Cloud image history supports on-demand image preview for originals up to 600KB. The app can revoke registered PCs and sign out its Cloud session. Local source checks remain the only automatic workflow.

### Event-driven Cloud connection
The PC now opens an outbound authenticated WebSocket and blocks waiting for events. The phone receives device presence and committed state changes through the same account-scoped hibernating Durable Object. No recurring cloud device, history or phone polling remains; legacy manual LAN mode retains its original local polling. WebSocket reconnect uses exponential backoff and the PC sends protocol keepalive every 60 seconds. In-flight actions are not replayed after disconnect. Cloud login still briefly checks OAuth completion. Deployment requires the staged cloud binding and SQL migration; no APK/image/production deployment here.

### Current shared Cloud contract
Authentication now uses the current DOMINION main `/v1/apps/start`, `/connect`, `/v1/apps/status`, `/v1/me` and `dapp_` credentials. The PC requests `assistant_pc`, the mobile app requests `assistant_remote`; both refer to the same member record. No separate OAuth secret or assistant member database. The staged `assistant-cloud-link` branch adds these explicit permissions and the WebSocket transport to the current main gateway. Old experimental assistant account sessions require re-login. This code still requires the branch's migration and deployment before device registration works against production; no APK or deployment triggered.

## Messenger-first mobile app
The default entry is Messenger. Shared Cloud login opens chat without a PC. Free model catalog and completions use the existing Cloud API, with device-local, account-partitioned message history. Optional PC tasks, attachments and SIP calls are selected explicitly; ordinary chats never require the PC. The previous connection UI remains under advanced settings. Call button opens the LiveKit voice session. PC-independent realtime audio, native background incoming calls and local STT/TTS are separate follow-up work; no deployment or APK build.

The in-app VoIP call screen now auto-connects after user confirmation, requests microphone permission, enables the mic, displays peer-connected elapsed time, mute/audio routing and hangup. The messenger remains mounted during calls. Backgrounding still ends a call intentionally until native call lifecycle integration is validated. No native incoming ring or PSTN-free claim for external phone numbers.

### Proactive task messages
Committed task outcomes are queued by the PC, published to the shared Cloud inbox and delivered over socket invalidation and optional Expo push. There is no idle message polling. In-app clients fetch the inbox after reconnect/foreground/push. Push registration requires EXPO_PUBLIC_EAS_PROJECT_ID and the operator's native push credentials; no invented project ID. The PC outbox is bounded and currently memory-resident (unpublished notices can be lost on PC restart); Cloud inbox persists for 30 days. Background native incoming calls remain unimplemented.

### Account-managed models and voice preferences
The mobile messenger uses only the shared DOMINION Cloud model catalog and completions API. Mobile provider endpoint/key entry and direct external inference are removed. Cloud provider configuration stays on the server; existing PC-side model configuration remains separate. Previously stored experimental mobile BYOK credentials are no longer read or used. Optional OS on-device speech recognition requests the platform's installed recognizer; availability varies, and this is not a bundled Sherpa engine. Local TTS uses the platform speech service. Personal profile/notes are included in the chosen model prompt. Model-proposed PC work or third-party phone requests only prepare UI after confirmation, never execute silently. The SIP panel still requires a configured PC and explicit number/purpose approval.

### Incoming AI call preferences
Account-local mobile settings now store an opt-in incoming-call preference (default off), separately enabled approval, scheduled-call and user-defined condition categories, plus a proposed 23:00–08:00 quiet interval. These are explicitly labeled preconfiguration; they do not initiate or authorize a real call today. Future native call delivery must enforce server-side opt-in, trigger permission and quiet hours before ringing. The existing user-initiated calls and message pushes are unchanged. Realtime speech currently uses Deepgram/Cartesia; local realtime speech providers are not implemented by this settings change.
