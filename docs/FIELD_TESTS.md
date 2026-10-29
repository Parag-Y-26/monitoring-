# Human-gated acceptance

All items below are **HUMAN-GATED / NOT PASSED** until their actual evidence is attached to STATUS. Use unique site/session IDs, phone model/OS/build fingerprint, app commit, model digest and timestamps. Do not put site footage or credentials into Git.

## Physical-device setup and benchmarking

1. Review the bundled COCO YOLOX-Tiny/Nano FP16/calibrated INT8 artifacts, upstream Apache-2.0 license, conversion provenance and checksums. These are baseline models; site-trained replacements still require the later field protocol.
2. Connect a physical phone, authorize ADB, and record `adb devices -l`, device build fingerprint, SoC, available RAM, battery capacity and ambient temperature.
3. Run `./tools/Test-AndroidDevice.ps1 -Serial <phone-serial>` to install the exact debug APK and independently check package/cold launch. Inspect the live UI/logcat. Run the native benchmark after the device reaches steady idle temperature, then export its report through the app's local SAF folder.
4. Record all six requested model/delegate outcomes, unavailable-driver reasons, 20 warmups and 100 measured wall-time samples per usable backend. Nearest-rank p95 includes synchronous output readback. Save thermal status before/after. Identify requested versus profiler-confirmed hardware acceleration.
5. Construction uses YOLOX-Tiny only and must block >40 ms. Tier C targets <=35 ms at 5 FPS. Do not silently substitute Nano.

## Encrypted storage, offline setup and audit

- Run `:app:connectedDebugAndroidTest` on the target phone. These tests create isolated test keys/data and check all seven tables, SQLCipher header/marker confidentiality, encrypted keyframe round-trip/tamper rejection, FIFO quota/purge and cached offline unlock with a test validator.
- Separately complete real online API validation through the selected provider, force-stop, disconnect network and relaunch. The test validator is not proof of provider integration.
- Extract only explicit test fixtures via `run-as`; inspect the DB/WAL/keysets/settings/keyframes using a hex viewer and `strings`. Confirm test marker absence, non-SQLite headers and non-WebP ciphertext headers. Never extract production keys.
- Enroll the initial public key externally and export SAF checkpoints. Verify the same history with LocalAuditVerifier and `tools/verify_audit_chain.py`. Test unsigned replacement, old-key-authorized rotation, modified payload, record deletion/reordering, monotonic regression and retained-tip suffix deletion.
- Confirm private-key security level on the actual phone. Validate verifier-supplied attestation certificates, chain roots, revocation and challenge independently before making an attestation claim. Software signing tests do not prove hardware residency.

## Field collection, annotation, training and quantization

- Use >=4 consented construction sites: sun/shade, dim <=30 lux, single/double carries, slow or paused hand-trucks, and sand/lime/fertilizer/trash/toolbox negatives. Reserve two whole unseen sites for hold-out.
- Record only with a separate reference/data-collection camera under the field protocol; the EdgePulse app must never write video.
- Annotate COCO boxes/classes, review labels and site splits, and store data outside Git. Record labeling tools, QA reviewers and class distributions. Targets from the guide: >=8,000 construction and >=6,000 plant instances.
- Training and calibrated INT8 export require actual site data, training logs, calibration-set manifest and digest-pinned model outputs. Training/export scripts belong to Phase 3 and are not yet delivered or run.

## Stationary camera, gate, alarms and network

- Profile a continuous two-hour run with allocation/GC tools, recording per-frame allocation and GC counts. Verify every Image/ImageProxy closes, idle RGB conversion count is zero, and two reusable raw-YUV buffers remain stable.
- Static doorway: >=95% of sensor frames do not reach Tier 2. Sun/cloud exposure shifts must not wake Tier 2. Include 30-second background-freeze timeouts; log/display their count and burst duty cycle.
- Thermal SEVERE: simulate and physically test pause, signed gap and high-priority audible/vibrating notification. Target immediate process-alive notification; record measured latency rather than assuming delivery.
- Process kill: leave the session active, record the last heartbeat, terminate the service/process using a reproducible allowed ADB method and wait through actual WorkManager/Doze scheduling. The 15-minute periodic worker may detect after 15–30+ minutes; there is no hard maximum guarantee. Gap start equals last heartbeat. Repeat after explicit Stop: no alarm is permitted.
- Reboot, force-stop, OEM restrictions and denied notification permissions need separate scenarios. Android force-stop can suppress scheduled work until relaunch; do not advertise guaranteed force-stop detection.
- Capture the app UID's network traffic from before monitoring starts to after it stops, with setup/cloud disabled. The required result is zero application bytes. Only an explicit Confirm & Upload may activate the cloud client in Phase 6.

## N=500 crossings/site and battery

- Use two unseen sites, >=500 physical crossings/site, including 400 OUT + 100 IN. Mount Tier A/B and Tier C phones next to an independent reference camera. Humans label each direction, stack size and crossing time before comparing app events.
- Include cold-idle entry from all frame edges, born outside/born in deadband, occlusion <=1.5 s, reverse crossings, pauses, two-bag stacks, hard negatives, dim light and busy 40–50% burst periods. An entering bag at t0 confirms no earlier than t+2; no zero warmup lag claim.
- Keep setup lines inside central [0.20,0.80], verify the OUT arrow and single/double-stack toggle. Save per-site count errors, false positives/negatives and evidence of no duplicate counts.
- Run eight hours on a 5,000 mAh OLED phone at the hot site. Record battery percentage/charge counter, device/ambient temperature, blackout brightness, duty cycle, dropped frames, delegate and power conditions. No charger during the measured interval. Target <=6%/h under the specified typical workload; report raw measurements and workload.

## Garden and privacy/cloud (later phases)

- Verify plaque quality/repeatability, cross-session alignment residuals and covariance, 25 cm-separated plants under mid-range updates, <=1.5 m spawns, 1.5–3.0 m update-only, covariance process noise and 10 m fallback re-anchors. Coarse mode stays labeled +/-2 m.
- In Phase 6, automated face redaction remains best-effort. Every candidate needs preview, manual redaction/deselection and a fresh explicit Confirm & Upload tap. Capture zero uploads before that tap; compare transmitted keyframes and deterministic audit summary against the approved selection.
