"""Run inside the model-preparation image; this is export-equivalence math, not field accuracy."""
import copy
import unittest
import torch
from yolox.models.network_blocks import Focus
from export_baseline import fuse_focus_block, make_raw_head_model
from yolox.exp import get_exp


class FocusFusionTests(unittest.TestCase):
    def test_fused_convolution_preserves_all_four_pixel_parities_and_border_padding(self):
        torch.manual_seed(42)
        torch.set_num_threads(2)
        for channels in (1, 3):
            for height, width in ((8, 10), (18, 14)):
                with self.subTest(channels=channels, shape=(height, width)):
                    original = Focus(channels, 7, ksize=3).double().eval()
                    sample = torch.randn(2, channels, height, width, dtype=torch.float64) * 255
                    expected = original(sample)
                    fused = fuse_focus_block(copy.deepcopy(original))
                    torch.testing.assert_close(fused(sample), expected, rtol=1e-10, atol=1e-10)

    def test_three_raw_heads_flatten_to_the_original_tiny_and_nano_predictions(self):
        torch.set_num_threads(2)
        for name in ("yolox-tiny", "yolox-nano"):
            with self.subTest(model=name):
                model = get_exp(None, name).get_model().eval()
                model.head.decode_in_inference = False
                sample = torch.randn(1, 3, 64, 96)
                with torch.no_grad():
                    expected = model(sample)
                    heads = make_raw_head_model(model)(sample)
                    actual = torch.cat([head.flatten(start_dim=2) for head in heads], dim=2).permute(0, 2, 1)
                    torch.testing.assert_close(actual, expected)


if __name__ == "__main__":
    unittest.main()
