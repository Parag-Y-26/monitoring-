import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("model_sources", ROOT / "tools/modelprep/prepare_sources.py")
sources = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(sources)


class ModelSourceTests(unittest.TestCase):
    def setUp(self):
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, "w") as output:
            for index in range(1, 4):
                output.writestr(f"val2017/{index:012}.jpg", b"image" + bytes([index]))
            output.writestr("../../outside.jpg", b"excluded")
        self.data = archive.getvalue()
        self.url = __import__("urllib.parse", fromlist=["urlunsplit"]).urlunsplit(("https", "example.invalid", "/fixture.zip", "", ""))
        self.etag = '"test-fixture"'

    def response(self, request, timeout=None):
        start, end = map(int, request.get_header("Range").removeprefix("bytes=").split("-"))
        self.assertEqual(self.etag, request.get_header("If-match"))
        response = io.BytesIO(self.data[start:end + 1])
        response.status = 206
        response.headers = {"Content-Range": f"bytes {start}-{end}/{len(self.data)}", "ETag": self.etag}
        return response

    def lock(self):
        return dict(weights=[], coco_archive=self.url, coco_archive_bytes=len(self.data),
                    coco_archive_etag=self.etag, calibration_samples=2, calibration_seed=42)

    def test_real_archive_entries_are_selected_reproducibly_and_paths_stay_inside_output(self):
        with tempfile.TemporaryDirectory(dir=ROOT / "artifacts") as folder, patch.object(sources.urllib.request, "urlopen", self.response):
            output = Path(folder)
            sources.prepare(output, self.lock())
            manifest = json.loads((output / "calibration-manifest.json").read_text())
            self.assertEqual(2, len(manifest["images"]))
            first = (output / "calibration-manifest.json").read_bytes()
            sources.prepare(output, self.lock())
            self.assertEqual(first, (output / "calibration-manifest.json").read_bytes())
            self.assertFalse((output.parent / "outside.jpg").exists())

    def test_same_size_modified_cached_image_is_rejected(self):
        with tempfile.TemporaryDirectory(dir=ROOT / "artifacts") as folder, patch.object(sources.urllib.request, "urlopen", self.response):
            output = Path(folder)
            sources.prepare(output, self.lock())
            image = next((output / "calibration").glob("*.jpg"))
            image.write_bytes(b"X" * image.stat().st_size)
            with self.assertRaises(ValueError):
                sources.prepare(output, self.lock())

    def test_server_ignoring_byte_ranges_is_rejected(self):
        def ignored(request, timeout=None):
            response = io.BytesIO(self.data)
            response.status = 200
            response.headers = {}
            return response
        with patch.object(sources.urllib.request, "urlopen", ignored):
            with self.assertRaises(ValueError):
                sources.RemoteZipFile(self.url, len(self.data), self.etag).read(8)


if __name__ == "__main__":
    unittest.main()
