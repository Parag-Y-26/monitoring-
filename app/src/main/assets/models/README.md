# Bundled baseline models

Both official COCO YOLOX-Tiny 320x320 FP16/INT8 exports are present. `manifest.json` binds every asset's SHA-256. `LICENSE` is the upstream Apache-2.0 license and `provenance.json` records the exact source commit/checkpoints, real 100-image COCO calibration manifest and numerical parity results.

Input is NHWC BGR [0,255], with centered letterbox padding 114. FP16 weights use FLOAT32 I/O; INT8 I/O requires the exported tensor scale and zero point. Output is the raw undecoded 80-class COCO head `[1,2100,85]`. These models do not detect the cement-bag domain class; field training and accuracy remain HUMAN-GATED.

Android emulator tests verify every packaged digest, tensor contract and actual invocation for both assets, plus the LiteRT CompiledModel path on CPU. The harness requests three delegate paths for Tiny. They do not prove NPU/GPU availability or physical-device latency. The harness uses 20 warmups, 100 measured invocations and nearest-rank p95; Construction requires Tiny <=40 ms, with <=35 ms at 5 FPS as the Tier C target.

See `docs/MODEL_PREPARATION.md` for reproducible conversion. No model downloads occur in the Android app.
