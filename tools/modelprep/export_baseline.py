#!/usr/bin/env python3
"""Linux-only COCO baseline conversion; no training and no field accuracy claim.

This job is intentionally separate from Android and runs with networking disabled
after the image is built and prepare_sources.py has supplied all input images.
"""
import gc
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

import numpy as np
from PIL import Image, ImageOps


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def input_image(path):
    with Image.open(path) as image:
        square = ImageOps.pad(image.convert("RGB"), (320, 320), method=Image.Resampling.BILINEAR,
                              color=(114, 114, 114), centering=(0.5, 0.5))
        # Official COCO YOLOX weights use BGR values in [0,255].
        return np.asarray(square, dtype=np.float32)[..., ::-1].copy()[None]


def fuse_focus_block(focus):
    """Exactly re-index a four-parity Focus + convolution into one strided convolution."""
    import torch
    base = focus.conv
    old = base.conv
    if old.in_channels % 4 or old.groups != 1 or old.dilation != (1, 1):
        raise ValueError("Unsupported Focus convolution; do not approximate it")
    channels = old.in_channels // 4
    new = torch.nn.Conv2d(channels, old.out_channels,
        kernel_size=tuple(2 * value for value in old.kernel_size),
        stride=tuple(2 * value for value in old.stride),
        padding=tuple(2 * value for value in old.padding), bias=old.bias is not None,
        device=old.weight.device, dtype=old.weight.dtype)
    with torch.no_grad():
        for block, (dy, dx) in enumerate(((0, 0), (1, 0), (0, 1), (1, 1))):
            new.weight[:, :, dy::2, dx::2].copy_(old.weight[:, block * channels:(block + 1) * channels])
        if old.bias is not None:
            new.bias.copy_(old.bias)
    base.conv = new
    return base.eval()


def make_raw_head_model(model):
    """Expose the three unflattened heads, avoiding layout guesses in 4D-to-3D ONNX Reshape."""
    import torch

    class RawHeads(torch.nn.Module):
        def __init__(self, wrapped):
            super().__init__()
            self.wrapped = wrapped

        def forward(self, images):
            head = self.wrapped.head
            outputs = []
            for index, feature in enumerate(self.wrapped.backbone(images)):
                stem = head.stems[index](feature)
                classes = head.cls_preds[index](head.cls_convs[index](stem)).sigmoid()
                regression = head.reg_convs[index](stem)
                boxes = head.reg_preds[index](regression)
                confidence = head.obj_preds[index](regression).sigmoid()
                outputs.append(torch.cat((boxes, confidence, classes), dim=1))
            return tuple(outputs)

    return RawHeads(model).eval()


def io_and_metrics(path, sample_nhwc, expected, variant):
    from ai_edge_litert.interpreter import Interpreter
    interpreter = Interpreter(model_path=str(path), num_threads=2)
    interpreter.allocate_tensors()
    inputs, outputs = interpreter.get_input_details(), interpreter.get_output_details()
    if len(inputs) != 1 or tuple(inputs[0]["shape"]) != (1, 320, 320, 3) or len(outputs) != 1:
        raise ValueError("Unexpected converted model interface")
    if variant == "INT8":
        if inputs[0]["dtype"] != np.int8 or outputs[0]["dtype"] not in (np.int8, np.float32):
            raise ValueError("Expected INT8 input with INT8 or FLOAT32 output")
        scale, zero = inputs[0]["quantization"]
        if scale <= 0:
            raise ValueError("Missing INT8 calibration scale")
        encoded = np.clip(np.rint(sample_nhwc / scale + zero), -128, 127).astype(np.int8)
    else:
        if inputs[0]["dtype"] != np.float32 or outputs[0]["dtype"] != np.float32:
            raise ValueError("FP16-weights model must retain FLOAT32 I/O")
        if variant == "FP16" and not any(item["dtype"] == np.float16 for item in interpreter.get_tensor_details()):
            raise ValueError("No FP16 weight tensors were exported")
        encoded = sample_nhwc
    interpreter.set_tensor(inputs[0]["index"], encoded)
    interpreter.invoke()
    actual = interpreter.get_tensor(outputs[0]["index"])
    if outputs[0]["dtype"] == np.int8:
        scale, zero = outputs[0]["quantization"]
        if scale <= 0:
            raise ValueError("Missing INT8 output scale")
        actual = (actual.astype(np.float32) - zero) * scale
    if actual.shape != expected.shape or not np.isfinite(actual).all():
        raise ValueError("Invalid converted detector output")
    if variant == "FP32":
        np.testing.assert_allclose(actual, expected, rtol=0.001, atol=0.001)
    return {"max_abs_error": float(np.abs(actual - expected).max()),
            "rmse": float(np.sqrt(np.mean((actual - expected) ** 2))),
            "output_shape": list(actual.shape), "accuracy_evaluated": False}


def main():
    import onnx
    import onnx2tf
    import tensorflow as tf
    import torch
    from yolox.exp import get_exp

    root = Path("/work")
    lock = json.loads(Path("/opt/modelprep/source-lock.json").read_text())
    calibration_file = root / "calibration-manifest.json"
    calibration = json.loads(calibration_file.read_text())
    if len(calibration["images"]) != lock["calibration_samples"]:
        raise ValueError("Incomplete real COCO calibration inputs")
    for item in calibration["images"]:
        if digest(root / item["path"]) != item["sha256"]:
            raise ValueError("Calibration image digest mismatch")
    actual_commit = subprocess.check_output(["git", "-C", "/opt/YOLOX", "rev-parse", "HEAD"], text=True).strip()
    if actual_commit != lock["yolox_commit"]:
        raise ValueError("Upstream source revision mismatch")
    bundle = root / "bundle"
    (bundle / "models").mkdir(parents=True, exist_ok=True)
    scratch = root / "conversion"
    scratch.mkdir(exist_ok=True)
    torch.set_num_threads(2)
    sample_nhwc = input_image(root / calibration["images"][0]["path"])
    sample_nchw = np.transpose(sample_nhwc, (0, 3, 1, 2)).copy()
    models, metrics = [], {}

    for item in lock["weights"]:
        checkpoint = root / "weights" / item["name"]
        if digest(checkpoint) != item["sha256"]:
            raise ValueError("Official checkpoint digest mismatch")
        if item["model"] != "YOLOX_TINY":
            raise ValueError("Only the construction YOLOX-Tiny model is supported")
        name = "yolox-tiny"
        model = get_exp(None, name).get_model().cpu().eval()
        state = torch.load(checkpoint, map_location="cpu", weights_only=True)
        model.load_state_dict(state["model"], strict=True)
        model.head.decode_in_inference = False
        tensor = torch.from_numpy(sample_nchw)
        with torch.inference_mode():
            expected = model(tensor).numpy()
        model.backbone.backbone.stem = fuse_focus_block(model.backbone.backbone.stem)
        with torch.inference_mode():
            np.testing.assert_allclose(model(tensor).numpy(), expected, rtol=0.001, atol=0.001)
        onnx_path = scratch / (name + ".onnx")
        torch.onnx.export(make_raw_head_model(model), tensor, str(onnx_path), input_names=["images"],
                          output_names=[f"head_{stride}" for stride in model.head.strides],
                          opset_version=13, dynamo=False, do_constant_folding=True)
        onnx.checker.check_model(str(onnx_path))
        saved = scratch / (name + "-saved")
        onnx2tf.convert(input_onnx_file_path=str(onnx_path), output_folder_path=str(saved),
                        tflite_backend="tf_converter", output_signaturedefs=True,
                        not_use_onnxsim=True, non_verbose=True)
        backend = tf.saved_model.load(str(saved))
        signature = backend.signatures["serving_default"]
        specs = list(signature.structured_input_signature[1].values())
        if len(specs) != 1 or tuple(specs[0].shape) != (1, 320, 320, 3):
            raise ValueError("The SavedModel must have a fixed NHWC 320x320 input")
        input_name = next(iter(signature.structured_input_signature[1]))
        channels = expected.shape[-1]

        class FlatPredictions(tf.Module):
            def __init__(self):
                super().__init__()
                self.backend = backend

            @tf.function(input_signature=[tf.TensorSpec((1, 320, 320, 3), tf.float32, name="images")])
            def serve(self, images):
                heads = signature(**{input_name: images})
                normalized = []
                for value in heads.values():
                    if value.shape.rank != 4:
                        raise ValueError("Expected three four-dimensional YOLOX heads")
                    if value.shape[-1] == channels:
                        normalized.append(value)
                    elif value.shape[1] == channels:
                        normalized.append(tf.transpose(value, (0, 2, 3, 1)))
                    else:
                        raise ValueError("Unknown head layout; do not guess channel axes")
                if len(normalized) != 3:
                    raise ValueError("Expected exactly three YOLOX feature levels")
                normalized.sort(key=lambda value: int(value.shape[1]) * int(value.shape[2]), reverse=True)
                return {"predictions": tf.concat([tf.reshape(value, (1, -1, channels)) for value in normalized], axis=1)}

        flat = FlatPredictions()
        flattened = scratch / (name + "-flat-saved")
        tf.saved_model.save(flat, str(flattened), signatures={"serving_default": flat.serve})

        # Validate the unquantized export before trusting any quantized derivatives.
        fp32 = scratch / (name + "-checked-fp32.tflite")
        fp32.write_bytes(tf.lite.TFLiteConverter.from_saved_model(str(flattened)).convert())
        metrics[name + "_FP32"] = io_and_metrics(fp32, sample_nhwc, expected, "FP32")

        for variant in ("FP16", "INT8"):
            converter = tf.lite.TFLiteConverter.from_saved_model(str(flattened))
            converter.optimizations = [tf.lite.Optimize.DEFAULT]
            if variant == "FP16":
                converter.target_spec.supported_types = [tf.float16]
            else:
                def representative():
                    for image in calibration["images"]:
                        yield [input_image(root / image["path"])]
                converter.representative_dataset = representative
                converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
                converter.inference_input_type = tf.int8
                # Keep INT8 internals/input with a float interface for detection scores.
                # Re-export and measure score error: a float boundary alone cannot recover
                # precision already lost by an internally quantized concatenation.
                converter.inference_output_type = tf.float32
            filename = name.replace("-", "_") + "_320_" + variant.lower() + ".tflite"
            path = bundle / "models" / filename
            path.write_bytes(converter.convert())
            metrics[name + "_" + variant] = io_and_metrics(path, sample_nhwc, expected, variant)
            models.append({"model": item["model"], "variant": variant, "asset": "models/" + filename,
                           "sha256": digest(path), "license": "Apache-2.0"})
            print("Exported and interface-checked:", filename, flush=True)
        del model, state, tensor, signature
        tf.keras.backend.clear_session()
        gc.collect()

    shutil.copyfile("/opt/YOLOX/LICENSE", bundle / "LICENSE")
    provenance = {"upstream_commit": actual_commit, "weight_sources": lock["weights"],
                  "conversion_command": "tools/modelprep/Run-ModelPrep.ps1",
                  "calibration_manifest_sha256": digest(calibration_file),
                  "license_reviewed_by": "Codex: upstream Apache-2.0 repository and official release sources",
                  "input_contract": "NHWC BGR float32 [0,255], centered 320x320 letterbox, padding 114; INT8 uses exported scale/zero-point",
                  "output_contract": "COCO 80-class raw YOLOX head; decode_in_inference=false; not a trained cement-bag detector",
                  "accuracy_evaluated": False, "physical_benchmark": "HUMAN-GATED", "metrics": metrics}
    (bundle / "provenance.json").write_text(json.dumps(provenance, indent=2) + "\n")
    # Publish the manifest last; its presence means both conversions/structural checks completed.
    (bundle / "manifest.json").write_text(json.dumps({"version": 1, "models": models}, indent=2) + "\n")
    print("COCO baseline bundle prepared. Field training, accuracy and physical latency are not verified.")


if __name__ == "__main__":
    main()
