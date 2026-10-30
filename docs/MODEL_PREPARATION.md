# COCO benchmark asset preparation

State: conversion completed on 2026-10-29. The two Tiny exports are bundled in `app/src/main/assets/models` and invoke on the API 37 Android emulator. Field accuracy remains HUMAN-GATED / NOT PASSED; see STATUS for physical-device observations.

The user selected official upstream YOLOX COCO pretrained Tiny checkpoint and a real COCO calibration subset. `tools/modelprep/source-lock.json` fixes upstream commit `6ddff4824372906469a7fae2dc3206c7aa4bbaee`, official release asset sizes and observed download SHA-256 digests. These are locally computed TLS-download hashes, not independently published upstream checksums. The canonical release tag includes `rc0`; it is a weights source, not an Android prerelease dependency. The upstream Apache-2.0 license and conversion provenance were reviewed before import.

`prepare_sources.py` deterministically selects 100 real COCO val2017 images with seed 20261029. Bounded HTTPS ZIP ranges, exact Content-Range/ETag, CRC and per-image SHA-256 checks protect acquisition and cached inputs. Calibration manifest SHA-256: `5be63118580ca3acce8d8765ec0b24ff5607491d4c494cd73b04afb3986cc6c7`. Images remain in ignored `artifacts/modelprep`; they are not redistributed in the APK. Three host tests cover source selection/cache/range rejection.

Run `./tools/modelprep/Run-ModelPrep.ps1 -Python <python-executable>` with Docker Desktop's Linux engine. Docker is used only to build these Android assets, never as an application backend. Python 3.12.14's eligible image digest and the converter compatibility set are pinned. Fixed source is fetched while building the image; conversion runs with networking disabled, two CPUs and a 4 GB memory limit. No WSL repair is required or was attempted.

The exporter loads checkpoints with `weights_only=True`, exports a fixed 320x320 graph and checks FP32 parity before producing FP16 and full calibrated INT8 derivatives. To preserve layout through ONNX2TF, it exactly fuses Focus slicing into a strided convolution and exports the three 4D raw heads before a checked NHWC flatten/concatenate bridge. Two converter math tests check borders/parity and whole-head equivalence. Both retained final outputs are finite `[1,2100,85]`; numerical errors are retained in `provenance.json`, not called accuracy.

| Asset | Bytes |
| --- | ---: |
| Tiny FP16 | 10,187,592 |
| Tiny INT8 | 5,369,896 |

Bundle import used `python tools/import_benchmark_models.py <bundle-folder> --license-reviewed`. It checks both required Tiny variants, flatbuffer headers, sizes, digests and source/calibration/license provenance. Android tests independently check packaged digests, FLOAT32/INT8 input/output contracts and inference for every asset; another test invokes LiteRT's CompiledModel API on CPU.

These stock COCO derivatives do not contain the cement-bag domain classifier. Real site collection, training, calibration and accuracy/battery testing remain HUMAN-GATED. The stock bundle is for runtime integration and baseline delegate benchmarking.

The native benchmark requests NPU CompiledModel INT8, GPU FP16 and XNNPACK INT8 for Tiny, explicitly reports unavailable backends and caches only the matching device fingerprint/model/runtime result. User-triggered reruns bypass the cache. Local SAF export makes the report reviewable. Actual acceleration needs physical profiling; emulator results cannot satisfy physical acceptance.

Local conversion logs are in ignored `artifacts/model-conversion.log`. Docker's Linux engine ran the completed conversion successfully; its earlier startup problem no longer blocks model preparation.
