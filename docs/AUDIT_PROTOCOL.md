# Audit protocol v1

Every record contains a typed payload, previous SHA-256 digest, signer X.509/SPKI public key in base64, current digest and DER-encoded SHA256withECDSA signature in base64. Keys must be NIST P-256.

`frame(parts)` writes each UTF-8 string as a four-byte big-endian byte length followed by its bytes. Field names use ASCII `[a-z][a-z0-9_]{0,63}` and sort lexicographically. Fields contain strings; sequence and timestamps are signed 64-bit JSON integers. Floating-point values are not part of this protocol.

Payload parts, in order: `EdgePulse/audit-payload/v1`, kind, session ID, sequence, boot ID, elapsed milliseconds, wall milliseconds, field count, then sorted name/value pairs.

The signed digest is SHA-256 over the framed parts `EdgePulse/audit-record/v1`, previous lowercase hex digest, signer SPKI base64, followed by the canonical payload. Java `SHA256withECDSA` and Python `ECDSA(SHA256)` sign this 32-byte digest consistently. Genesis is 64 zero hex characters. Sequence starts at one and never skips or repeats across the exported history.

EVENT binds direction, bag weight, running OUT total, track ID and plaintext WebP SHA-256. GAP binds reason, start/end elapsed times and start wall time. ROTATION binds the new public key and is signed by the currently trusted outgoing key. The next record must use the named new key. Every previous record remains intact.

Elapsed times cannot regress within a boot ID. Explicit boot IDs separate reboots. Running OUT totals must match derived per-session counts. Overlapping downtime intervals are rejected instead of counted twice.

Both verifiers require an externally enrolled initial public key. The Python CLI takes `--trusted-key <spki-base64-file>`. An optional externally retained `--expected-tip <digest>` detects suffix removal. Without that anchor, signatures cannot prove that a valid suffix was not deleted. A public key shipped inside an untrusted export cannot establish its own identity.

`DailyCheckpointExporter` writes a complete verified chain as `checkpoint_YYYYMMDD_HHMMSS.sig.json` through a user-selected local SAF folder. Each leaf signature binds the prior chain. A supervisor should retain the exported tip and initial public key outside the phone. The Android setup screen exports the public identity/certificates and selects the local folder. A WorkManager job requests export every four hours; Android scheduling can delay it. Shift-end export will be wired when Phase 2 introduces session stop. Empty histories produce an explicit NO_HISTORY result rather than a fabricated checkpoint. Encrypted settings retain the last export status/time.

`MonitoringAuditRepository` verifies persisted history before writing, serializes writers, checks the committed head, and transactionally persists each signed event plus its encrypted keyframe index/counts. Signed gaps and outgoing-key rotation records use the same global sequence. Android tests reopen the repository, recover counts/gaps, persist a rotation and continue signing under the named replacement identity. Full cross-device transfer/import and its UI are not yet implemented.

SAF export permits only `com.android.externalstorage.documents` (device/SD/USB) to keep a cloud-backed document provider from uploading audit data during monitoring. Private keys are never exported. Public identity exports include `attestation_verified: false`; offline enrollment means no attestation claim.

Generate a verifier nonce outside the app using `python tools/verify_audit_chain.py --generate-challenge <new-file>`. A nonce is 32 random bytes, base64-encoded. Supply it at device key creation. Empty app enrollment means no attestation challenge and no attestation claim. Certificate-root, revocation and attestation-extension verification are separate, not implemented checks; exporting certificates alone does not prove hardware security or freshness.

Example verification: `python tools/verify_audit_chain.py <checkpoint-file> --trusted-key <enrolled-key-file> --expected-tip <retained-tip>`. Exit 0 means valid, 1 means verification failure, 2 means input/enrollment failure. Counts are zeroed in failed summaries.
