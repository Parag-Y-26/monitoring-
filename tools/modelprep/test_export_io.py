"""Exercise exporter interface validation without TensorFlow or a downloaded model."""
import importlib.util
from pathlib import Path
import sys
import types
import unittest
from unittest.mock import patch

import numpy as np

SPEC = importlib.util.spec_from_file_location("export_baseline", Path(__file__).with_name("export_baseline.py"))
exporter = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(exporter)


class ExportIoTests(unittest.TestCase):
    def run_interface(self, output_type, input_type=np.int8, output_scale=.1):
        actual = np.array([[[1, 2, 3]]], dtype=output_type)

        class Interpreter:
            def __init__(self, **kwargs):
                pass

            def allocate_tensors(self):
                pass

            def get_input_details(self):
                return [dict(shape=(1, 320, 320, 3), dtype=input_type, quantization=(1., 0), index=0)]

            def get_output_details(self):
                return [dict(dtype=output_type, quantization=(output_scale, 0), index=1)]

            def set_tensor(self, index, value):
                np.testing.assert_array_equal(value, np.zeros((1, 320, 320, 3), dtype=np.int8))

            def invoke(self):
                pass

            def get_tensor(self, index):
                return actual

        module = types.ModuleType("ai_edge_litert.interpreter")
        module.Interpreter = Interpreter
        expected = actual.astype(np.float32) * output_scale if output_type == np.int8 else actual
        with patch.dict(sys.modules, {"ai_edge_litert.interpreter": module}):
            return exporter.io_and_metrics(Path("fixture.tflite"), np.zeros((1, 320, 320, 3)), expected, "INT8")

    def test_int8_input_float_output_is_not_dequantized_again(self):
        self.assertEqual(0., self.run_interface(np.float32, output_scale=0.)["rmse"])

    def test_legacy_int8_output_is_dequantized(self):
        self.assertEqual(0., self.run_interface(np.int8)["rmse"])

    def test_bad_interfaces_and_missing_quantization_are_rejected(self):
        for output_type, input_type, scale in ((np.float64, np.int8, .1), (np.float32, np.float32, .1), (np.int8, np.int8, 0.)):
            with self.subTest(output_type=output_type, input_type=input_type, scale=scale):
                with self.assertRaises(ValueError):
                    self.run_interface(output_type, input_type, scale)


if __name__ == "__main__":
    unittest.main()
