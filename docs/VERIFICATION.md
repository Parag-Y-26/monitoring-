# Verification, 2026-10-30

Current scope is construction inventory only. The attached original v5 and the immutable Room v1 schema are retained for history/migration tests; they do not enable retired features.

- Core: 21 JVM tests and the >=80% line-coverage gate. Signed history, rotations, totals/gaps, clocks, external suffix anchors, wire format, latency gates, provider parsing and consent interlock are covered.
- Python: 14 tests pass, including actual verifier CLI runs on JVM wire bundles, bounded real-COCO acquisition and Tiny-only import acceptance/rejection.
- Android: all nine current tests pass on Samsung SM-A346E/API 36, including the encrypted v1 migration, preservation of construction records/counts, removal of retired tables/columns and foreign-key integrity. Both Tiny assets invoke with their verified tensor contracts; CompiledModel CPU invocation also passes. The earlier eight-test v0.1 suite passed on the API 37 emulator as historical evidence.
- Current build and full Android lint pass. The APK installed and cold-launched on the phone, opening its existing encrypted storage and audit identity. The construction label was checked in the native UI; the setup screen was visually reviewed.
- The retained Tiny FP16/INT8 bytes are unchanged from the completed COCO conversion. The active manifest/import/export tooling accepts only those two variants. Original conversion math/parity evidence is in MODEL_PREPARATION and the bundled provenance; no field accuracy is claimed.
- Physical v0.1 timing observed on the phone UI: Tiny XNNPACK 32.57 ms p95, GPU FP16 35.82 ms, NPU unavailable. USB connected, thermal LIGHT (1) before/after. Raw report was not collected before interruption, so these rounded UI values are historical observations rather than a full controlled benchmark artifact.
- The Python verifier dependency audit on 2026-10-30 reports no known vulnerabilities. This does not certify every Android/native/converter dependency.

Local logs/screenshots are in ignored artifacts/; generated reports/APKs are in build outputs. No CI success, field accuracy, battery target, physical attestation, real provider validation, monitoring network capture or scheduled checkpoint delivery is claimed. Phases 2–5 have not started.

Current APK: `app/build/outputs/apk/debug/app-debug.apk`, 67,882,890 bytes, SHA-256 `bf0c8c65f03a01445c74353916006599114c091e632c133570d712dd38d6caa3`. ZIP integrity and both model checksums pass; no Nano asset is packaged. Logs: `construction-only-final-build.log`, `construction-only-python.log`, `construction-only-device-tests.log` under artifacts/.
