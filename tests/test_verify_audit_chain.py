"""Adversarial tests use fresh ephemeral P-256 keys, never repository credentials."""
import base64
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import unittest
import subprocess
import sys

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("verifier", ROOT / "tools/verify_audit_chain.py")
verifier = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verifier)


class AuditVerifierTests(unittest.TestCase):
    def setUp(self):
        self.old = ec.generate_private_key(ec.SECP256R1())
        self.new = ec.generate_private_key(ec.SECP256R1())

    def public(self, key):
        return base64.b64encode(key.public_key().public_bytes(
            serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).decode()

    def record(self, key, seq=1, previous="0" * 64, kind="EVENT", fields=None):
        payload = dict(kind=kind, sessionId="session-1", sequence=seq, bootId="boot-1",
                       elapsedMs=seq * 100, wallMs=1800000000000 + seq * 100,
                       fields=fields or dict(direction="OUT", bag_weight="1", running_out_total=str(seq),
                                             track_id=str(seq), webp_sha256="a" * 64))
        record = dict(payload=payload, previousHash=previous, publicKeySpkiBase64=self.public(key))
        digest = hashlib.sha256(verifier.record_bytes(record)).digest()
        record["chainHash"] = digest.hex()
        record["signatureBase64"] = base64.b64encode(key.sign(digest, ec.ECDSA(hashes.SHA256()))).decode()
        return record

    def test_valid(self):
        a = self.record(self.old)
        result = verifier.verify([a], self.public(self.old))
        self.assertTrue(result["valid"])
        self.assertEqual(1, result["verifiedOutCount"])

    def test_tamper_and_deletion(self):
        a = self.record(self.old)
        b = self.record(self.old, 2, a["chainHash"])
        changed = copy.deepcopy(a)
        changed["payload"]["fields"]["bag_weight"] = "2"
        self.assertFalse(verifier.verify([changed], self.public(self.old))["valid"])
        self.assertFalse(verifier.verify([a], self.public(self.old), b["chainHash"])["valid"])
        self.assertFalse(verifier.verify([b, a], self.public(self.old))["valid"])
        self.assertFalse(verifier.verify([], self.public(self.old))["valid"])

    def test_unsigned_key_change_alert(self):
        a = self.record(self.old)
        b = self.record(self.new, 2, a["chainHash"])
        result = verifier.verify([a, b], self.public(self.old))
        self.assertFalse(result["valid"])
        self.assertEqual("PUBKEY_ROTATED_TAMPER_ALERT", result["keyContinuityStatus"])

    def test_old_key_authorizes_named_key(self):
        a = self.record(self.old)
        rotation = self.record(self.old, 2, a["chainHash"], "ROTATION",
                               {"new_public_key_spki_b64": self.public(self.new)})
        b = self.record(self.new, 3, rotation["chainHash"])
        b = self.record(self.new, 3, rotation["chainHash"], fields=dict(b["payload"]["fields"], running_out_total="2"))
        result = verifier.verify([a, rotation, b], self.public(self.old))
        self.assertTrue(result["valid"])
        self.assertEqual("AUTHORIZED_KEY_ROTATION", result["keyContinuityStatus"])
        self.assertEqual(2, result["verifiedOutCount"])

    def test_self_signed_rotation_cannot_authorize_new_key(self):
        a = self.record(self.old)
        rotation = self.record(self.new, 2, a["chainHash"], "ROTATION",
                               {"new_public_key_spki_b64": self.public(self.new)})
        self.assertFalse(verifier.verify([a, rotation], self.public(self.old))["valid"])

    def test_malformed_data_returns_failure(self):
        for obj in [{}, {"payload": None}, self.record(self.old, fields={"bag_weight": "100"})]:
            with self.subTest(obj=obj):
                self.assertFalse(verifier.verify([obj], self.public(self.old))["valid"])
        a = self.record(self.old)
        a["signatureBase64"] = "!"
        self.assertFalse(verifier.verify([a], self.public(self.old))["valid"])

    def test_jvm_generated_cross_language_fixtures(self):
        folder = ROOT / "core/build/audit-fixtures"
        self.assertTrue(folder.exists(), "Run :core:test first to generate JVM-signed fixtures")
        for path in sorted(folder.glob("*.json")):
            with self.subTest(path=path.name):
                fixture = json.loads(path.read_text(encoding="utf-8"))
                result = verifier.verify(fixture["records"], fixture["trustedKey"])
                self.assertEqual(fixture["expectedValid"], result["valid"])
                self.assertEqual(fixture["expectedContinuity"], result["keyContinuityStatus"])

    def test_jvm_wire_bundles_pass_the_actual_cli(self):
        folder = ROOT / "core/build/audit-bundles"
        self.assertTrue(folder.exists(), "Run :core:test first to generate wire bundles")
        for name, expected in (("valid", 0), ("authorized", 0), ("unsigned-change", 1), ("tampered", 1)):
            with self.subTest(name=name):
                process = subprocess.run([sys.executable, str(ROOT / "tools/verify_audit_chain.py"),
                    str(folder / f"{name}.json"), "--trusted-key", str(folder / f"{name}.anchor")],
                    capture_output=True, text=True, timeout=15, check=False)
                self.assertEqual(expected, process.returncode, process.stdout + process.stderr)


if __name__ == "__main__":
    unittest.main()
