# User build contract

The user approved the attached v5.0 guide on 2026-10-29 because the requested v4 file is absent. This contract overrides conflicting statements in that guide.

## Amendments

1. Keep the last two idle raw YUV_420_888 frames in two reusable direct buffers. Copy planes with row/pixel-stride handling; convert, rotate and letterbox only on burst wakeup/active burst.
2. `minHits=3`: a bag first visible at t0 confirms at t+2. Pre-roll can recover prior sightings only. Keep the walk-in-from-edge acceptance test; claim no zero warmup lag.
3. Watchdog is a 15-minute periodic WorkManager job. Detection may take 15–30+ minutes under Doze/OEM limits. An explicit user stop clears the active flag. Gaps begin at the last heartbeat, not detection.
4. The old signing key must sign a transition naming the new public key. Unannounced changes raise `PUBKEY_ROTATED_TAMPER_ALERT`. Attestation challenges come from the verifier/supervisor.
5. Construction is blocked when YOLOX-Tiny p95 >40 ms. <=35 ms at 5 FPS is the Tier C target, not a second eligibility gate.
6. Log burst duty cycle and 30-second background-freeze timeouts; display both in the debug HUD.
7. 2026-10-30 scope change: construction inventory monitoring only. Remove the garden feature, spatial storage, ARCore and YOLOX-Nano. The active plan has five phases; privacy/cloud is Phase 5. The unmodified attached v5 document remains a historical reference where this contract supersedes it.

## Non-negotiable implementation boundaries

- Resolve stable dependencies against Google Maven/Maven Central, excluding releases younger than 14 days. The user approved DataStore/datastore-tink 1.3.0 as the sole cooling-period exception. CameraX stays at the explicitly requested 1.6.1.
- Pin versions in `gradle/libs.versions.toml`; record metadata and POM timestamps in `dependency-resolution.json`.
- No monitoring network traffic. Only `CloudAnalysisService` may own an OkHttpClient, instantiated after an explicit Confirm & Upload tap. Never bundle API keys or service endpoints.
- Tink + encrypted DataStore with a Keystore wrapping key; no EncryptedSharedPreferences/security-crypto. Room + SQLCipher for monitoring_sessions, inventory_events, monitoring_gaps and archive_index; StreamingAead for encrypted WebP stills. Backups and device transfer exclude all private state. Schema upgrades retain construction audit history and keys.
- Construction CameraX runs inside a camera LifecycleService started while the Activity is visible.
- Apache-2.0 YOLOX only. No Ultralytics/YOLOv8 and no video files. All camera Images/ImageProxies close in `finally`; no allocations in the monitoring frame hot path.
- Application-managed key buffers use ByteArray and are zero-filled after use where the API allows. Keystore private keys stay non-exportable. Tink/DataStore/protobuf may retain internal managed copies; do not claim complete managed-heap erasure.
- Write math/signature tests first. Work in phase order. Record deviations in DECISIONS and current evidence in STATUS. Never create `phase-N-done` until that phase's actual required checks pass.

## Phase sequence

1. Security, encrypted storage, deterministic audit and delegate benchmark.
2. Stationary camera, gate/pre-roll/cadence, thermal/watchdog/blackout/HUD.
3. Upright YOLOX detection and exact letterbox inverse; training/export tooling.
4. Kalman tracker, line counter/setup and signed encrypted crossing evidence.
5. Face-redaction preview, explicit cloud confirmation and usage ledger.

Field data, actual training, N=500 crossings/site, 8-hour battery tests and physical-device benchmarking are HUMAN-GATED. Tools/checklists do not constitute passed field evidence.
