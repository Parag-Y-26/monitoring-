# EdgePulse repository guidance

- Read `docs/BUILD_CONTRACT.md`, `docs/DECISIONS.md` and `docs/STATUS.md` before editing. The user's amendments override the attached v5.0 guide.
- Keep work in the five construction-only phases in BUILD_CONTRACT; the user removed the garden feature on 2026-10-30. No `phase-N-done` tag until that phase's actual acceptance checks pass. Never relabel human-gated checks as passed.
- Keep math and deterministic verification in the pure Kotlin core module. Write tests before changing those behaviors.
- All production HTTP client creation stays in CloudAnalysisService, after a fresh matching Confirm & Upload tap. Phase 2 monitoring must share the singleton MonitoringNetworkInterlock and persist its active flag under that lock.
- All credentials/endpoints are entered by the user and encrypted. Never put credentials or runtime service endpoints in source, resources, logs, fixtures or Git history.
- Pin dependencies in the version catalog, preserve dependency locks and document age/provenance checks. The only cooling-period exception currently approved is DataStore 1.3.0; CameraX is explicitly pinned to 1.6.1.
- Preserve the four construction tables, authenticated encrypted event stills, private-key continuity and raw-YUV idle pre-roll. Migrate legacy databases without resetting construction sessions, audit history or keys.
- Validation: `./gradlew :core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`; then `python -m unittest discover -s tests -v`. Device tests use `:app:connectedDebugAndroidTest` and remain unverified until a real run succeeds.
- Run `pip-audit -r tools/requirements.txt` before committing; review the diff before pushing. Keep status and decisions current. No external publication or deployment is implied by a local build.
