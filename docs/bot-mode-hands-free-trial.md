# Hermex Bot Mode and hands-free voice trial

This trial stays on the existing direct Hermes WebUI/API connection. No cloud speech SDK, API key, relay daemon, or server configuration is added.

Bot Mode

The Relay reference's standard Gateway contract is an authenticated JSON-RPC/WebSocket connection using `profiles.list` with `include_sessions=true`. Canonical Bot Chat routing is server-owned: exact hidden `session.list` lookup by profile/title, then `session.create` followed by `session.title` when absent. The pure Android projection and payload builder live in `BotModeProtocol.kt` and are covered by `BotModeProtocolTest`.

Hermex's usable first slice is an explicit **Bot Mode** entry beside Tasks, Kanban, and Skills on the sessions screen. It lists the same installation's existing Hermes profiles and opens a pending chat scoped to the selected profile, reusing the normal ChatRoute and `/api/chat/start` path. This is deliberately a profile-scoped fallback, not a fabricated Gateway WebSocket client. A server that exposes the Relay Gateway roster can use the documented payload parser, but canonical hidden Bot Chat resolution is not yet sent by Hermex because the app has no Gateway JSON-RPC transport.

Hands-free voice

The explicit Hands-free voice button is opt-in. Each server-transcribed turn records an eight-second speech window, automatically stops and submits it to `/api/transcribe`, sends the transcript through the normal chat path, reads the completed response through `/api/tts` with Android TTS fallback, and then starts the next listening turn after playback completes. Stop cancels capture/playback and increments a session generation so late transcription, completion, or playback callbacks cannot revive a stopped session. Existing one-shot dictation and voice-note behavior remain available and unchanged when the toggle is off.

The pure lifecycle is `HandsFreeVoiceMachine`; tests cover normal sequencing, stop fencing, and stale-generation rejection. Playback is not fed back into the microphone by starting recognition only after the playback controller returns to Idle.

Prerequisites and limits

- Existing Hermes server endpoints `/api/transcribe`, `/api/chat/start` + SSE completion, and `/api/tts` are required for the complete hands-free loop.
- Android microphone permission and a speech recognizer are required. Server transcription remains local/private to the configured Hermes installation; Android's existing on-device/provider policy still applies.
- Gateway Bot Mode requires a Relay-compatible Gateway transport/server. Until that transport is added, the profile picker cannot guarantee the hidden canonical Bot Chat registry.
- No Gradle compile/test was run on the LLM box by request; CI must run the Android unit-test, lint, debug-build, and connected-test jobs after approval.
- Text-only pinch scaling remains in the existing transcript settings path; the new controls do not apply a UI-wide scale or magnification.
