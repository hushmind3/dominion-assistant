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
