#!/usr/bin/env python3
"""Fetch digest-pinned official YOLOX checkpoints and a reproducible real COCO subset.

Only selected ZIP entries are fetched using HTTP ranges. No training or model
conversion runs here. Dataset images and checkpoints stay outside Git.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import random
import re
import urllib.request
import zipfile
import zlib

LOCK_PATH = Path(__file__).with_name("source-lock.json")


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


class RemoteZipFile(io.RawIOBase):
    def __init__(self, url, size, etag):
        self.url, self.length, self.etag = url, size, etag
        self.position, self.cache_start, self.cache = 0, 0, b""

    def seekable(self):
        return True

    def readable(self):
        return True

    def tell(self):
        return self.position

    def seek(self, offset, whence=0):
        position = offset if whence == 0 else self.position + offset if whence == 1 else self.length + offset
        if not 0 <= position <= self.length:
            raise ValueError("ZIP seek outside archive")
        self.position = position
        return position

    def read(self, size=-1):
        remaining = self.length - self.position if size < 0 else min(size, self.length - self.position)
        if remaining > 8 * 1024 * 1024:
            raise ValueError("Refusing an unexpectedly large ZIP read")
        result = bytearray()
        while remaining:
            if not self.cache_start <= self.position < self.cache_start + len(self.cache):
                end = min(self.length - 1, self.position + max(512 * 1024, remaining) - 1)
                request = urllib.request.Request(self.url, headers={
                    "Range": f"bytes={self.position}-{end}", "If-Match": self.etag,
                })
                with urllib.request.urlopen(request, timeout=45) as response:
                    if response.status != 206 or response.headers.get("Content-Range") != f"bytes {self.position}-{end}/{self.length}":
                        raise ValueError("Server did not honor the bounded range request")
                    if response.headers.get("ETag") != self.etag:
                        raise ValueError("COCO archive changed; review the source lock")
                    self.cache = response.read(end - self.position + 1)
                    self.cache_start = self.position
                    if len(self.cache) != end - self.position + 1:
                        raise ValueError("Truncated archive range")
            offset = self.position - self.cache_start
            length = min(remaining, len(self.cache) - offset)
            result.extend(self.cache[offset:offset + length])
            self.position += length
            remaining -= length
        return bytes(result)


def prepare(output, lock):
    output.mkdir(parents=True, exist_ok=True)
    weights = output / "weights"
    weights.mkdir(exist_ok=True)
    for item in lock["weights"]:
        destination = weights / item["name"]
        if not destination.exists():
            url = lock["yolox_repository"].removesuffix(".git") + "/releases/download/" + lock["release"] + "/" + item["name"]
            partial = destination.with_suffix(".part")
            with urllib.request.urlopen(url, timeout=90) as source, partial.open("wb") as target:
                while chunk := source.read(1024 * 1024):
                    target.write(chunk)
                    if target.tell() > item["bytes"]:
                        raise ValueError("Oversized checkpoint")
            partial.replace(destination)
        if destination.stat().st_size != item["bytes"] or sha256(destination) != item["sha256"]:
            raise ValueError("Checkpoint digest mismatch")
    image_dir = output / "calibration"
    image_dir.mkdir(exist_ok=True)
    entries = []
    with RemoteZipFile(lock["coco_archive"], lock["coco_archive_bytes"], lock["coco_archive_etag"]) as remote, zipfile.ZipFile(remote) as archive:
        files = sorted((info for info in archive.infolist() if re.fullmatch(r"val2017/\d{12}\.jpg", info.filename)), key=lambda entry: entry.filename)
        chosen = random.Random(lock["calibration_seed"]).sample(files, lock["calibration_samples"])
        for index, info in enumerate(chosen, 1):
            if not 0 < info.file_size <= 5_000_000 or info.compress_size > 5_000_000:
                raise ValueError("Unexpected calibration image size")
            destination = image_dir / Path(info.filename).name
            if not destination.exists():
                destination.write_bytes(archive.read(info))  # zipfile checks the entry's CRC.
            if destination.stat().st_size != info.file_size:
                raise ValueError("Incomplete calibration image")
            if zlib.crc32(destination.read_bytes()) & 0xFFFFFFFF != info.CRC:
                raise ValueError("Cached image does not match the official COCO archive")
            entries.append({"path": "calibration/" + destination.name, "sha256": sha256(destination), "coco_archive_entry": info.filename})
            if index % 10 == 0:
                print(f"COCO calibration: {index}/{len(chosen)}", flush=True)
    manifest = {"source": lock["coco_archive"], "etag": lock["coco_archive_etag"], "seed": lock["calibration_seed"], "images": entries}
    manifest_path = output / "calibration-manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print("Prepared official checkpoints and real COCO inputs; conversion has not run.")
    print("Calibration manifest SHA-256:", sha256(manifest_path))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    prepare(args.output.resolve(), json.loads(LOCK_PATH.read_text(encoding="utf-8")))


if __name__ == "__main__":
    main()
