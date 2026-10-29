#!/usr/bin/env python3
"""Validate and import a locally supplied, license-reviewed benchmark bundle.

Expected bundle: manifest.json, LICENSE, provenance.json and models/*.tflite.
This checks container magic, names and SHA-256. It does not certify model quality,
license ownership, training data, quantization calibration or hardware support.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil


def validate_bundle(bundle):
    bundle = bundle.resolve(strict=True)
    manifest = json.loads((bundle / "manifest.json").read_text(encoding="utf-8"))
    license_text = (bundle / "LICENSE").read_text(encoding="utf-8")
    provenance = json.loads((bundle / "provenance.json").read_text(encoding="utf-8"))
    if "Apache License" not in license_text or "Version 2.0" not in license_text:
        raise ValueError("Supply the upstream Apache-2.0 license")
    if not isinstance(provenance, dict) or not all(provenance.get(key) for key in
            ("upstream_commit", "weight_sources", "conversion_command", "calibration_manifest_sha256", "license_reviewed_by")):
        raise ValueError("Missing model conversion, calibration or license-review provenance")
    if set(manifest) != {"version", "models"} or manifest["version"] != 1:
        raise ValueError("Invalid manifest")
    expected = {(model, variant) for model in ("YOLOX_TINY", "YOLOX_NANO") for variant in ("FP16", "INT8")}
    seen, names = set(), set()
    for item in manifest["models"]:
        if set(item) != {"model", "variant", "asset", "sha256", "license"}:
            raise ValueError("Invalid model entry")
        pair = item["model"], item["variant"]
        if pair not in expected or pair in seen or item["asset"] in names or item["license"] != "Apache-2.0":
            raise ValueError("Unexpected, duplicate or non-Apache model")
        if not re.fullmatch(r"models/[a-z0-9_]+\.tflite", item["asset"]):
            raise ValueError("Invalid model path")
        model = (bundle / item["asset"]).resolve(strict=True)
        if not model.is_relative_to(bundle) or model.stat().st_size > 100_000_000:
            raise ValueError("Unsafe/oversized asset")
        digest = hashlib.sha256()
        with model.open("rb") as source:
            header = source.read(8)
            if len(header) != 8 or header[4:8] != b"TFL3":
                raise ValueError("Not a TFLite flatbuffer")
            digest.update(header)
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(chunk)
        if digest.hexdigest() != item["sha256"]:
            raise ValueError("Model checksum mismatch")
        seen.add(pair)
        names.add(item["asset"])
    if seen != expected:
        raise ValueError("All four Tiny/Nano FP16/INT8 assets are required")
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundle", type=Path)
    parser.add_argument("--license-reviewed", action="store_true", required=True,
                        help="Confirm that provenance and the actual weights' license were reviewed")
    args = parser.parse_args()
    manifest = validate_bundle(args.bundle)
    destination = Path(__file__).resolve().parents[1] / "app/src/main/assets/models"
    paths = [Path(item["asset"]).name for item in manifest["models"]] + ["manifest.json", "LICENSE", "provenance.json"]
    if any((destination / name).exists() for name in paths):
        parser.error("Destination assets already exist; review and replace them explicitly")
    destination.mkdir(parents=True, exist_ok=True)
    for item in manifest["models"]:
        shutil.copyfile(args.bundle / item["asset"], destination / Path(item["asset"]).name)
    for name in ("LICENSE", "provenance.json", "manifest.json"):
        shutil.copyfile(args.bundle / name, destination / name)
    print("Imported four checksum-verified assets. Runtime tensor validation and physical benchmarking remain required.")


if __name__ == "__main__":
    main()
