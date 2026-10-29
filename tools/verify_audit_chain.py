#!/usr/bin/env python3
"""Offline deterministic verifier. An external P-256 SPKI trust anchor is mandatory.

This verifies signatures and continuity, not Android attestation certificate roots.
Without --expected-tip, deletion after the last externally retained checkpoint is
undetectable. Never accept a key claimed inside the bundle as its own trust anchor.
"""
import argparse
import base64
import binascii
import hashlib
import json
from pathlib import Path
import re
import secrets
import struct

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

GENESIS = "0" * 64
MAX_LONG = (1 << 63) - 1


def frame(parts):
    result = bytearray()
    for part in parts:
        encoded = part.encode("utf-8")
        if len(encoded) > 65536:
            raise ValueError("Oversized field")
        result.extend(struct.pack(">I", len(encoded)))
        result.extend(encoded)
    return bytes(result)


def integer(value, minimum=0):
    if type(value) is not int or not minimum <= value <= MAX_LONG:
        raise ValueError("Invalid signed 64-bit integer")
    return value


def decimal(value):
    if not isinstance(value, str) or not re.fullmatch(r"[+-]?\d+", value, flags=re.ASCII):
        raise ValueError("Invalid decimal")
    return integer(int(value))


def valid_hash(value):
    return isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value) is not None


def payload_bytes(p):
    if set(p) != {"kind", "sessionId", "sequence", "bootId", "elapsedMs", "wallMs", "fields"}:
        raise ValueError("Unknown/missing payload fields")
    integer(p["sequence"], 1)
    integer(p["elapsedMs"])
    integer(p["wallMs"])
    if not isinstance(p["sessionId"], str) or not p["sessionId"].strip():
        raise ValueError("Missing session")
    if not isinstance(p["bootId"], str) or not p["bootId"].strip():
        raise ValueError("Missing boot")
    fields = p["fields"]
    if not isinstance(fields, dict) or len(fields) > 64:
        raise ValueError("Invalid fields")
    parts = ["EdgePulse/audit-payload/v1", p["kind"], p["sessionId"], str(p["sequence"]),
             p["bootId"], str(p["elapsedMs"]), str(p["wallMs"]), str(len(fields))]
    for key in sorted(fields):
        if not re.fullmatch(r"[a-z][a-z0-9_]{0,63}", key) or not isinstance(fields[key], str):
            raise ValueError("Invalid field")
        parts.extend([key, fields[key]])
    return frame(parts)


def record_bytes(record):
    if not valid_hash(record["previousHash"]):
        raise ValueError("Invalid previous hash")
    return frame(["EdgePulse/audit-record/v1", record["previousHash"], record["publicKeySpkiBase64"]]) + payload_bytes(record["payload"])


def public_key(encoded):
    if not isinstance(encoded, str) or not 1 <= len(encoded) <= 2048:
        raise ValueError("Invalid public key")
    key = serialization.load_der_public_key(base64.b64decode(encoded, validate=True))
    if not isinstance(key, ec.EllipticCurvePublicKey) or not isinstance(key.curve, ec.SECP256R1):
        raise ValueError("P-256 public key required")
    return key


def verify(records, trusted_key, expected_tip=None):
    status, count, outgoing, incoming, gaps = "CONTINUOUS_VERIFIED", 0, 0, 0, 0
    previous, expected_key, clocks = GENESIS, trusted_key, {}
    session_out_counts, gap_ends = {}, {}

    def failed(error):
        return dict(valid=False, keyContinuityStatus=status, verifiedRecords=count,
                    verifiedOutCount=0, verifiedInCount=0, gapDurationMs=0,
                    tipHash=previous if count else None, error=error, externallyAnchoredTip=False)

    try:
        if not isinstance(records, list) or not records:
            return failed("EMPTY_CHAIN")
        public_key(trusted_key)
        for index, record in enumerate(records):
            if set(record) != {"payload", "previousHash", "publicKeySpkiBase64", "chainHash", "signatureBase64"}:
                raise ValueError("Unknown/missing envelope fields")
            if record["publicKeySpkiBase64"] != expected_key:
                status = "PUBKEY_ROTATED_TAMPER_ALERT"
                return failed("UNAUTHORIZED_KEY_CHANGE")
            p = record["payload"]
            if integer(p["sequence"], 1) != index + 1 or record["previousHash"] != previous:
                raise ValueError("Broken sequence/link")
            digest = hashlib.sha256(record_bytes(record)).digest()
            if not valid_hash(record["chainHash"]) or digest.hex() != record["chainHash"]:
                raise ValueError("Hash mismatch")
            public_key(record["publicKeySpkiBase64"]).verify(
                base64.b64decode(record["signatureBase64"], validate=True), digest, ec.ECDSA(hashes.SHA256()))
            if p["elapsedMs"] < clocks.get(p["bootId"], 0):
                raise ValueError("Monotonic regression")
            clocks[p["bootId"]] = p["elapsedMs"]
            f = p["fields"]
            if p["kind"] == "EVENT":
                if set(f) != {"direction", "bag_weight", "running_out_total", "track_id", "webp_sha256"}:
                    raise ValueError("Invalid event schema")
                weight = decimal(f["bag_weight"])
                running_out = decimal(f["running_out_total"])
                decimal(f["track_id"])
                if weight not in (1, 2) or not valid_hash(f["webp_sha256"]):
                    raise ValueError("Invalid event")
                if f["direction"] == "OUT":
                    outgoing = integer(outgoing + weight)
                elif f["direction"] == "IN":
                    incoming = integer(incoming + weight)
                else:
                    raise ValueError("Invalid direction")
                session_out = integer(session_out_counts.get(p["sessionId"], 0) + (weight if f["direction"] == "OUT" else 0))
                if running_out != session_out:
                    raise ValueError("Running count mismatch")
                session_out_counts[p["sessionId"]] = session_out
            elif p["kind"] == "GAP":
                if set(f) != {"reason", "gap_start_elapsed_ms", "gap_end_elapsed_ms", "gap_start_wall_ms"}:
                    raise ValueError("Invalid gap schema")
                if f["reason"] not in {"THERMAL_SEVERE", "OS_KILL_DETECTED", "CAMERA_ERROR", "PAUSE"}:
                    raise ValueError("Invalid gap reason")
                start, end = decimal(f["gap_start_elapsed_ms"]), decimal(f["gap_end_elapsed_ms"])
                decimal(f["gap_start_wall_ms"])
                if not 0 <= start <= end <= p["elapsedMs"]:
                    raise ValueError("Invalid gap interval")
                if start < gap_ends.get(p["bootId"], 0):
                    raise ValueError("Overlapping gaps")
                gaps = integer(gaps + end - start)
                gap_ends[p["bootId"]] = end
            elif p["kind"] == "ROTATION":
                if set(f) != {"new_public_key_spki_b64"} or f["new_public_key_spki_b64"] == expected_key:
                    raise ValueError("Invalid rotation")
                expected_key = f["new_public_key_spki_b64"]
                public_key(expected_key)
                status = "AUTHORIZED_KEY_ROTATION"
            else:
                raise ValueError("Unknown record kind")
            previous, count = record["chainHash"], count + 1
        if expected_tip is not None and previous != expected_tip:
            return failed("EXTERNAL_TIP_MISMATCH")
        return dict(valid=True, keyContinuityStatus=status, verifiedRecords=count,
                    verifiedOutCount=outgoing, verifiedInCount=incoming, gapDurationMs=gaps,
                    tipHash=previous, error=None, externallyAnchoredTip=expected_tip is not None)
    except (ValueError, TypeError, KeyError, AttributeError, OverflowError, InvalidSignature, binascii.Error):
        return failed("INVALID_AUDIT_RECORD")


def no_duplicate_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON key")
        result[key] = value
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundle", nargs="?", type=Path)
    parser.add_argument("--trusted-key", type=Path, help="Externally enrolled SPKI base64 text")
    parser.add_argument("--expected-tip", help="Externally retained checkpoint chain hash")
    parser.add_argument("--generate-challenge", type=Path, help="Write a fresh 32-byte verifier challenge (base64)")
    args = parser.parse_args()
    if args.generate_challenge:
        with args.generate_challenge.open("x", encoding="ascii") as output:
            output.write(base64.b64encode(secrets.token_bytes(32)).decode() + "\n")
        return 0
    if args.bundle is None or args.trusted_key is None:
        parser.error("bundle and --trusted-key are required")
    try:
        if args.bundle.stat().st_size > 64 * 1024 * 1024:
            raise ValueError("Bundle too large; verify segmented exports")
        bundle = json.loads(args.bundle.read_text(encoding="utf-8"), object_pairs_hook=no_duplicate_keys)
        if bundle.get("version") != 1 or set(bundle) != {"version", "records"}:
            raise ValueError("Unsupported bundle")
        result = verify(bundle["records"], args.trusted_key.read_text(encoding="ascii").strip(), args.expected_tip)
    except (OSError, ValueError, KeyError, AttributeError):
        print(json.dumps({"valid": False, "error": "INVALID_BUNDLE_OR_TRUST_ANCHOR"}))
        return 2
    print(json.dumps(result, indent=2))
    return 0 if result["valid"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
