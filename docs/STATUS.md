# Status

As of 2026-10-30. Scope now includes user-selected inventory items and photo-assisted setup; gardening remains removed. The overriding BUILD_CONTRACT defines five phases; the original attached v5 document is historical where it differs.

| Phase | State | Evidence / remaining work |
| --- | --- | --- |
| Bootstrap | DONE | Pinned dependencies, locks, native Kotlin/Compose build. |
| 1 Security/storage/benchmark | IN PROGRESS | v0.6 builds/lints; 48 JVM and 14 Python tests pass. Last device run: 22 tests before the latest full-screen revision; rerun pending USB reconnection. Encrypted catalogs/items/usage; existing history/keys retained. No completion tag. |
| 2 Stationary pipeline | IN PROGRESS | User requested working Start/Stop before remaining field gates. Camera service, motion gate/raw pre-roll, telemetry, heartbeat/thermal adapters and Home controls implemented. User confirmed live preview; foreground camera ownership and normal Stop independently observed. Sustained/alarm acceptance remains unverified. |
| 3 Detector | IN PROGRESS | v0.5 invokes Tiny XNNPACK from YUV, decodes/NMS, draws live boxes, supports category/reference targets. Real COCO probe: five airplane/truck boxes on phone/emulator. Field training/export and accuracy gates remain open. |
| 4 Counter | IN PROGRESS | Four-state Kalman tracks, minHits=3, <=1.5 s occlusion retention and confirmed visible counts. Line crossing/setup and signed crossing events remain pending. |
| 5 Privacy/cloud | IN PROGRESS | Photo crop/mask/preview, best-effort face masking, digest-bound Confirm & Upload and encrypted actual-usage ledger. Live photo API verification and full audited event-report workflow remain pending. |

## Implemented foundation

- Canonical ECDSA event/gap chain, independent local/Python verification, outgoing-key-authorized rotation, external-tip suffix checks and protocol-version wire tests.
- Keystore-wrapped Tink, encrypted DataStore, separate Gemini/OpenRouter/NVIDIA NIM credentials, explicit one-shot consent, cached offline unlock and monitoring/network interlock. A saved Gemini catalog containing 46 returned models was observed on the phone after relaunch. OpenRouter/NVIDIA live calls and cloud image analysis remain UNVERIFIED.
- Room/SQLCipher v2: monitoring_sessions, inventory_events, monitoring_gaps and archive_index. The v1 migration removes retired spatial storage/indexes while preserving construction sessions, signed records, counts and keys. Orphaned retired stills are removed by archive recovery.
- StreamingAead encrypted event WebP stills, decimal 200 MB FIFO, recovery/purge metadata and tamper rejection. No video writer.
- Persisted signed event/gap/rotation history, local SAF identity/checkpoint exports and a best-effort four-hour checkpoint request. Full-history SAF round-trip, actual scheduled delivery and cross-device transfer remain UNVERIFIED. Shift-end export awaits Phase 2. No attestation validation claim.
- Tiny-only NPU/GPU-FP16/XNNPACK-INT8 benchmark, device/model/runtime cache, raw timing report and thermal snapshots. Old bundle caches cannot be exported as current results. Construction gate is <=40 ms; Tier C target <=35 ms at 5 FPS.

## Physical-device evidence

- Samsung SM-A346E, Android 16/API 36, MT6877: v0.1 APK installed and cold-launched; all eight earlier Android tests passed on the phone.
- Before the scope change, the phone UI reported Tiny XNNPACK p95 32.57 ms and GPU FP16 35.82 ms; NPU unavailable. This is an observed baseline, not a current-v0.2 benchmark or a field result. USB power was connected and thermal status was LIGHT (1) before/after. Ambient temperature was not recorded; raw report collection was interrupted.
- Construction-only v0.2 installed and cold-launched successfully on the same phone; all nine Android tests passed. The encrypted v1-to-v2 migration retained construction rows/audit bytes/counts, removed spatial tables/columns and passed foreign-key checks. The app reopened its existing encrypted storage and local audit identity.
- APK ZIP/digest checks confirm two Tiny assets and no Nano asset; all four active tables are present in the exported v2 schema. Build and Android lint pass. The native screen explicitly identifies construction inventory monitoring.
- v0.4 installed/cold-launched; 17 Android tests pass, including three model-setup UI tests, two rendered-design/navigation tests, two Home Start/Stop callback tests and encrypted model-catalog persistence. 37 JVM tests and the >=80% core coverage gate pass. These UI fixtures do not establish detector accuracy or actual service alarm delivery.
- The user confirmed live preview/Stop. ADB independently observed camera service foreground type 0x40 and camera-device ownership. Internal Stop closed the service; Home returned to Start with no interrupted-session state. Longer frame-rate/GC/network/alarm runs remain unverified.

## Human gates / not passed

- HUMAN-GATED: Independent physical accelerator profiling and controlled sustained/hot-site performance. A baseline device timing does not pass this gate.
- HUMAN-GATED: Four-site data/annotation/hard negatives, real-data model training and calibration.
- HUMAN-GATED: N=500 crossings/site against an external reference camera; eight-hour battery test <=6%/h on a 5,000 mAh OLED phone.
- HUMAN-GATED: Two-hour GC profiling, thermal/process-kill alarms and monitoring network capture in later phases.

The user confirmed the live person label and replacement Gemini connection/model selection in v0.5. Photo analysis returned an error; exact message/HTTP status is still needed and it is not marked passed.

v0.6 adds the Figma-based four-tab layout and latest user-directed full-screen camera with a floating Stop/Rescan/More bar and shorter copy. Final device installation/render checks await USB reconnection. Figma's Starter MCP limit blocked synchronizing the later full-screen revision; see FIGMA_DESIGN.md. Custom reference matching remains experimental; descriptions do not train YOLOX, and scores are not calibrated probabilities. Cumulative crossing accuracy remains unfinished/human-gated.
