# Local verification, 2026-10-29

Passed against the current security/storage implementation:

- `:core:check`: 21 JVM tests; >=80% core line-coverage gate. Audit integrity, old-key-authorized versus unsigned key changes, running totals, signed gaps, monotonic clocks, suffix anchors, required checkpoint wire-version encoding, the 40 ms gate, provider-response parsing and consent/monitoring exclusion are covered.
- `python -m unittest discover -s tests -v`: 11 Python tests. Eight test the independent verifier, including JVM-signed fixtures and actual CLI execution against JVM wire bundles; three test bounded/reproducible COCO source selection and rejection of modified cache entries or ignored HTTP ranges.
- `:app:connectedDebugAndroidTest`: eight tests on API 37 emulator, zero failures/errors/skips. These cover Keystore P-256 rotation/verification, local-only SAF validation, standalone LiteRT initialization, all four packaged models' digests/tensor contracts/invocations, CompiledModel CPU invocation, SQLCipher/DataStore confidentiality/offline-cache persistence, authenticated keyframe round-trip/tamper/FIFO behavior, and persisted signed events/gaps/rotation followed by replacement-key signing.
- `:app:assembleDebug` and `:app:lintDebug`: pass. Lint's remaining dependency-update warnings correspond to the documented cooling-period/user-pin policy; they are not ignored compiler or security errors.
- `pip-audit -r tools/requirements.txt`: no known vulnerabilities in the Python verifier's resolved dependencies.
- Debug APK ZIP integrity checked; all packaged native ABIs inspected. ADB install returned Success, `pm path` returned the installed base APK, and cold `am start -W` returned Status ok on the emulator.
- Actual foundation UI was captured/reviewed. A dark-theme content-color issue was fixed. Native offline key enrollment, local SAF folder selection/identity export, empty-history checkpoint refusal, complete benchmark execution and report export were exercised. The report marks EMULATOR, includes 100 samples for each usable CPU backend, records GPU/NPU unavailability, and keeps Construction blocked. Emulator timings do not establish physical eligibility.
- Linux model conversion completed: two Focus/raw-head math tests, original-PyTorch versus converted FP32 parity, and finite/shape checks for four real-COCO-calibrated INT8/FP16 derivatives. Full provenance and numerical errors are bundled; these are not cement-trained or accuracy-evaluated models.
- A four-hour checkpoint job appears in Android JobScheduler. An attempted early forced run was deferred by WorkManager's own timing check; actual scheduled execution has not been claimed.

Local logs/screenshots reside under ignored `artifacts/`; XML reports and APKs reside under Gradle build outputs. They are local evidence, not published CI results. The APK is `app/build/outputs/apk/debug/app-debug.apk`.

Verified APK: 77,084,291 bytes; SHA-256 `e52750acb7d601597afa16abf923fc462bade35c7ff98acc1fc35e07fad70527`. ZIP integrity and the four packaged model digests pass. Native ABIs: arm64-v8a, armeabi-v7a, x86, x86_64. The physical phone is not currently visible to ADB, so no physical installation or performance result is claimed.

Not passed: physical-device model inference/latency/acceleration, actual provider-key validation, field training/quantization/accuracy/battery tests, long-running monitoring allocations/network capture/alarms, full-history SAF round-trip/migration/scheduled delivery, external attestation validation, and Phases 2–6.
