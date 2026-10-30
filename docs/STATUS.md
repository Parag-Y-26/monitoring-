# Status

As of 2026-10-31. Scope includes user-selected inventory items and photo-assisted setup; gardening remains removed. The overriding BUILD_CONTRACT defines five phases; the original attached v5 document is historical where it differs.

Current package: Drishti v0.6.4 (version code 10), with repaired crop/scroll/save, three-tab navigation and shared native button motion; see [UI evidence](UI_POLISH.md) and [phase evidence](DRISHTI_PHASE_UPDATES.md). Build/lint, 68 JVM tests (97.04% line coverage), 14 Python tests, three exporter interface tests and pip-audit pass. Latest API 36 emulator run: 31/32 pass, no skips; the bundled baseline still misses the pinned cow image at threshold 0.25. All five focused physical crop/motion tests passed. Test cleanup then removed the personal installation and reset its saved setup; the exact APK was restored and its launcher/cold launch/digest verified, but old settings/history/identity continuity is not claimed recovered. The user was informed to reconnect their key and redo setup. A supported AGP cleanup-retention option is now enabled, validated with a dry run; no further personal-device instrumentation ran after restoration. Physical field/performance and live API analysis remain unverified.

| Phase | State | Evidence / remaining work |
| --- | --- | --- |
| Bootstrap | DONE | Pinned dependencies, locks, native Kotlin/Compose build. |
| 1 Security/storage/benchmark | IN PROGRESS | Current APK builds/lints; 68 JVM and 14 Python tests pass. API 36 emulator: 31/32 pass with one pinned cow miss. Storage/migration tests pass, but personal-device test cleanup reset saved setup; no current-phone history/key retention claim. No completion tag. |
| 2 Stationary pipeline | IN PROGRESS | User requested working Start/Stop before remaining field gates. Camera service, motion gate/raw pre-roll, telemetry, heartbeat/thermal adapters and Home controls implemented. User confirmed live preview; foreground camera ownership and normal Stop independently observed. Sustained/alarm acceptance remains unverified. |
| 3 Detector | IN PROGRESS | Tiny XNNPACK/YUV with objectness proposals, hybrid category/reference matching and padding exclusion. Current emulator airplane/bottle/dog/chair probes pass; pinned cow probe fails at 0.25. FLOAT32-output interface supported; offline re-export showed identical peak scores to INT8 output and was not imported. Field training and accuracy gates remain open. |
| 4 Counter | IN PROGRESS | Four-state Kalman tracks, minHits=3, <=1.5 s occlusion retention and <=450 ms visible coasting. Finite center-gate IN/OUT counting and serialized signed encrypted event wiring implemented; customizable gate placement, automatic physical crossing evidence and field accuracy remain open. |
| 5 Privacy/cloud | IN PROGRESS | Photo crop/mask/preview, best-effort face masking, digest-bound Confirm & Upload and encrypted actual-usage ledger. Live photo API verification and full audited event-report workflow remain pending. |

## Implemented foundation

- Canonical ECDSA event/gap chain, independent local/Python verification, outgoing-key-authorized rotation, external-tip suffix checks and protocol-version wire tests.
- Keystore-wrapped Tink, encrypted DataStore, separate Gemini/OpenRouter/NVIDIA NIM credentials, explicit one-shot consent, cached offline unlock and monitoring/network interlock. A saved Gemini catalog containing 46 returned models was observed on the phone after relaunch. OpenRouter/NVIDIA live calls and cloud image analysis remain UNVERIFIED.
- Room/SQLCipher v2: monitoring_sessions, inventory_events, monitoring_gaps and archive_index. The v1 migration removes retired spatial storage/indexes while preserving construction sessions, signed records, counts and keys. Orphaned retired stills are removed by archive recovery.
- StreamingAead encrypted event WebP stills, decimal 200 MB FIFO, recovery/purge metadata and tamper rejection. No video writer.
- Persisted signed event/gap/rotation history, local SAF identity/checkpoint exports and a best-effort four-hour checkpoint request. Full-history SAF round-trip, actual scheduled delivery and cross-device transfer remain UNVERIFIED. Shift-end export awaits Phase 2. No attestation validation claim.
- Tiny-only NPU/GPU-FP16/XNNPACK-INT8 benchmark, device/model/runtime cache, raw timing report and thermal snapshots. Old bundle caches cannot be exported as current results. Construction gate is <=40 ms; Tier C target <=35 ms at 5 FPS.

## Physical-device evidence

- 2026-10-31 v0.6.4/code 10: three real-phone crop/scroll/mask/apply/offline-save tests and two button spring/reduced-motion tests pass. The test runner subsequently uninstalled the app; the user reported a missing icon. Reinstalled the exact APK, SHA-256 `120dc5a158b0982d26fb262eabdb2181457ee596e57c411a92705e35834c7679`; independently verified installed digest, cold MainActivity launch, live process/resumed Activity and MAIN/LAUNCHER resolution. Inspected the real Home render: it shows fresh setup, not the old saved target/key. This reset was disclosed; encrypted setup cannot be reconstructed from the APK. See `artifacts/v064-phone-evidence` and UI_POLISH.md. Earlier retention observations below are historical and do not describe the post-test phone state.

- 2026-10-30 delivery: updated Samsung SM-A346E from v0.6.2/code 8 to v0.6.3/code 9 using `adb install -r` (Success). Independently confirmed installed package path/version and APK SHA-256 `01df94c49dde5a3e471cc94e5dab70f03ae0f75b62f4169306601237fb90f4b6`, matching the delivered artifact. `am start -W` returned Status ok / COLD; a subsequent PID and resumed MainActivity were observed. Inspected `artifacts/phone-update-20261030/launch.png`: Items screen rendered with the saved person target and 0.30 threshold retained. No reset/uninstall, provider calls, new monitoring session or physical test-suite run was performed.

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

v0.6 adds the Figma-based four-tab layout and latest user-directed full-screen camera with a floating Stop/Rescan/More bar and shorter copy; v0.6.1 corrects the public app name to Drishti. Final device installation/render checks were deferred by the user. Figma's Starter MCP limit blocked synchronizing the later full-screen revision; see FIGMA_DESIGN.md. Custom reference matching remains experimental; descriptions do not train YOLOX, and scores are not calibrated probabilities. Cumulative crossing accuracy remains unfinished/human-gated.
