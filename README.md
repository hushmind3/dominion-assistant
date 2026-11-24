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
