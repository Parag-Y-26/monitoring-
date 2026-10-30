import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("import_models", ROOT / "tools/import_benchmark_models.py")
importer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(importer)


class ConstructionModelBundleTests(unittest.TestCase):
    def fixture(self, root, pairs):
        (root / "models").mkdir()
        models = []
        for model, variant in pairs:
            asset = f"models/{model.lower()}_{variant.lower()}.tflite"
            data = b"\x08\x00\x00\x00TFL3" + variant.encode()
            (root / asset).write_bytes(data)
            models.append(dict(model=model, variant=variant, asset=asset,
                               sha256=hashlib.sha256(data).hexdigest(), license="Apache-2.0"))
        (root / "manifest.json").write_text(json.dumps(dict(version=1, models=models)))
        (root / "LICENSE").write_text("Apache License Version 2.0")
        (root / "provenance.json").write_text(json.dumps({key: "fixture" for key in
            ("upstream_commit", "weight_sources", "conversion_command", "calibration_manifest_sha256", "license_reviewed_by")}))

    def test_accepts_tiny_fp16_and_int8_only(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            self.fixture(root, [("YOLOX_TINY", "FP16"), ("YOLOX_TINY", "INT8")])
            self.assertEqual(2, len(importer.validate_bundle(root)["models"]))

    def test_rejects_retired_model_even_with_all_old_variants(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            self.fixture(root, [(model, variant) for model in ("YOLOX_TINY", "YOLOX_NANO") for variant in ("FP16", "INT8")])
            with self.assertRaises(ValueError):
                importer.validate_bundle(root)

    def test_requires_both_construction_variants(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            self.fixture(root, [("YOLOX_TINY", "FP16")])
            with self.assertRaises(ValueError):
                importer.validate_bundle(root)
