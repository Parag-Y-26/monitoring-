# Status

As of 2026-10-29. Source: v5.0 guide plus user amendments and approved DataStore exception.

| Phase | State | Evidence / remaining work |
| --- | --- | --- |
| Bootstrap | DONE | Approved v5 source copied; version catalog and dependency locks resolved; APK builds and Android lint passes. |
| 1 Security/storage/benchmark | IN PROGRESS | 21 JVM, 11 Python and 8 Android emulator tests pass. Four COCO TFLite assets are converted, bundled and invoke on Android. Physical acceptance remains pending; no phase completion tag. |
| 2 Stationary pipeline | NOT STARTED | Requires Phase 1 acceptance. |
| 3 Detector | NOT STARTED | Requires Phase 2 acceptance. |
| 4 Counter | NOT STARTED | Requires Phase 3 acceptance. |
| 5 Garden | NOT STARTED | Requires Phase 4 acceptance. |
| 6 Privacy/cloud | NOT STARTED | Requires Phase 5 acceptance. |

## Human gates

- HUMAN-GATED: Physical-device benchmark and delegate profiling. The user reports connecting a phone, but ADB currently lists only emulator-5554; USB/debug authorization is pending resolution.
- HUMAN-GATED: Four-site collection, annotation, hard-negative validation and hold-out splits.
- HUMAN-GATED: Training and calibrated quantization using real site data.
- HUMAN-GATED: N=500 crossings/site against an external reference camera.
- HUMAN-GATED: Eight-hour hot-site battery test, <= 6%/h on a 5,000 mAh OLED phone.
- HUMAN-GATED: Two-hour GC profiling, thermal/process-kill alarm delivery and monitoring network capture.

No human gate has passed. See `FIELD_TESTS.md` for the evidence protocol.

## Phase 1 implementation and evidence

1. DONE: Canonical ECDSA event/gap chain, externally supplied trust anchor, deterministic JVM/Python verification, signed outgoing-key rotation, tamper detection and external-tip suffix checks. JVM-generated signed fixtures are independently verified in Python.
2. DONE (live provider calls UNVERIFIED): Keystore-wrapped Tink keysets, encrypted Proto DataStore, separate Gemini/OpenRouter/NVIDIA NIM credentials, explicit one-shot Confirm & Upload, cached offline unlock, differentiated HTTP errors and a shared monitoring/network interlock. No real credential was supplied or sent during development.
3. DONE on emulator: All seven Room/SQLCipher tables and DAOs; encrypted DB/settings contain no test marker, native SQLite cannot read the encrypted DB, and data survives close/reopen. SQLCipher's pooled-reader key-lifetime bug was reproduced and fixed; caller buffers clear after opening, driver buffers after close.
4. DONE on emulator: StreamingAead encrypted WebP stills, indexed FIFO quota, recovery/purge metadata and tamper rejection. No video writer is present.
5. IMPLEMENTED, qualified evidence: Android Keystore P-256 signing, persisted event/gap history and outgoing-key-authorized rotation pass emulator tests, including continuation under the replacement key. Native SAF public-identity export and empty-history refusal were exercised. A four-hour WorkManager checkpoint request is registered; actual scheduled delivery, full-history SAF round-trip and cross-device transfer remain UNVERIFIED. Shift-end export awaits Phase 2's stop workflow. External certificate/root/revocation verification is not implemented and no attestation claim is made.
6. IMPLEMENTED, physical gate pending: Four official COCO Tiny/Nano FP16/real-calibrated INT8 exports are bundled, checksum-verified and invoked on Android. The CompiledModel CPU path also passes. NPU/GPU-FP16/XNNPACK-INT8 harness, device/model/runtime cache, raw timing report, thermal snapshots and the 40 ms Tiny gate exist. Emulator runs explicitly remain Construction-blocked; no physical hardware result is claimed. See `MODEL_PREPARATION.md`.
7. IN PROGRESS: Local checks/visual QA complete. Physical benchmarking/acceptance and real provider validation remain unverified; `phase-1-done` is withheld. Progress commits do not imply phase completion.

## Verification boundary

- Debug APK builds, ZIP integrity is checked, and installation plus cold launch were independently verified on an API 37 emulator. The foundation screen was visually inspected and its text contrast corrected.
- Core line coverage is enforced at >=80%; Android and full-app coverage is not claimed.
- Python verifier dependency audit reports no known vulnerabilities. This is not a blanket security certification of all Android/native/conversion dependencies.
- Linux conversion completed successfully with runtime networking disabled. Docker is only build tooling; the native app requires no server or Docker installation. Two converter equivalence tests and FP32 parity passed before producing derivatives.
- Python also tests the actual verifier CLI against JVM-serialized wire bundles, preventing missing envelope-version regressions. See VERIFICATION for the latest full test counts.
- Phases 2–6 have not started. Monitoring, cement counting, garden mapping, field accuracy, battery targets and cloud image analysis are not implemented acceptance results in this foundation build.
