# Status

As of 2026-10-30. Scope: construction inventory only, per the user's removal of gardening. The overriding BUILD_CONTRACT defines five phases; the original attached v5 document is historical where it differs.

| Phase | State | Evidence / remaining work |
| --- | --- | --- |
| Bootstrap | DONE | Pinned dependencies, locks, native Kotlin/Compose build. |
| 1 Security/storage/benchmark | IN PROGRESS | 21 JVM, 14 Python and nine physical Android tests pass. Tiny FP16/INT8 assets retained; four construction tables and the v1 upgrade verified. No completion tag. |
| 2 Stationary pipeline | NOT STARTED | Requires Phase 1 acceptance. |
| 3 Detector | NOT STARTED | Requires Phase 2 acceptance. |
| 4 Counter | NOT STARTED | Requires Phase 3 acceptance. |
| 5 Privacy/cloud | NOT STARTED | Requires Phase 4 acceptance. |

## Implemented foundation

- Canonical ECDSA event/gap chain, independent local/Python verification, outgoing-key-authorized rotation, external-tip suffix checks and protocol-version wire tests.
- Keystore-wrapped Tink, encrypted DataStore, separate Gemini/OpenRouter/NVIDIA NIM credentials, explicit one-shot upload consent, cached offline unlock and monitoring/network interlock. Real provider calls remain UNVERIFIED.
- Room/SQLCipher v2: monitoring_sessions, inventory_events, monitoring_gaps and archive_index. The v1 migration removes retired spatial storage/indexes while preserving construction sessions, signed records, counts and keys. Orphaned retired stills are removed by archive recovery.
- StreamingAead encrypted event WebP stills, decimal 200 MB FIFO, recovery/purge metadata and tamper rejection. No video writer.
- Persisted signed event/gap/rotation history, local SAF identity/checkpoint exports and a best-effort four-hour checkpoint request. Full-history SAF round-trip, actual scheduled delivery and cross-device transfer remain UNVERIFIED. Shift-end export awaits Phase 2. No attestation validation claim.
- Tiny-only NPU/GPU-FP16/XNNPACK-INT8 benchmark, device/model/runtime cache, raw timing report and thermal snapshots. Old bundle caches cannot be exported as current results. Construction gate is <=40 ms; Tier C target <=35 ms at 5 FPS.

## Physical-device evidence

- Samsung SM-A346E, Android 16/API 36, MT6877: v0.1 APK installed and cold-launched; all eight earlier Android tests passed on the phone.
- Before the scope change, the phone UI reported Tiny XNNPACK p95 32.57 ms and GPU FP16 35.82 ms; NPU unavailable. This is an observed baseline, not a current-v0.2 benchmark or a field result. USB power was connected and thermal status was LIGHT (1) before/after. Ambient temperature was not recorded; raw report collection was interrupted.
- Construction-only v0.2 installed and cold-launched successfully on the same phone; all nine Android tests passed. The encrypted v1-to-v2 migration retained construction rows/audit bytes/counts, removed spatial tables/columns and passed foreign-key checks. The app reopened its existing encrypted storage and local audit identity.
- APK ZIP/digest checks confirm two Tiny assets and no Nano asset; all four active tables are present in the exported v2 schema. Build and Android lint pass. The native screen explicitly identifies construction inventory monitoring.

## Human gates / not passed

- HUMAN-GATED: Independent physical accelerator profiling and controlled sustained/hot-site performance. A baseline device timing does not pass this gate.
- HUMAN-GATED: Four-site data/annotation/hard negatives, real-data model training and calibration.
- HUMAN-GATED: N=500 crossings/site against an external reference camera; eight-hour battery test <=6%/h on a 5,000 mAh OLED phone.
- HUMAN-GATED: Two-hour GC profiling, thermal/process-kill alarms and monitoring network capture in later phases.

The app is a secure foundation. Live counting, monitoring, field accuracy/battery acceptance and cloud image analysis are not implemented acceptance results. See FIELD_TESTS and VERIFICATION.
