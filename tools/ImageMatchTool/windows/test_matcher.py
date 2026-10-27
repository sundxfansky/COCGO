"""Checks the Python matcher against direct per-block computations. Run: py -m unittest test_matcher"""

import unittest

import numpy as np

from image_match_tool import PixelRect, generate_code, sanitize_image_name, sanitize_property_name, swift_double
from matcher import BlockTemplate, reference_distances, search


class BlockTemplateTest(unittest.TestCase):
    def test_means_are_rounded_and_trailing_pixels_ignored(self):
        rng = np.random.default_rng(1)
        pixels = rng.integers(0, 256, size=(40, 50, 3), dtype=np.uint8)
        tpl = BlockTemplate.make(pixels, 3, 4, 17, 12, 5)
        self.assertEqual((tpl.cols, tpl.rows), (3, 2))
        for r in range(2):
            for c in range(3):
                cell = pixels[4 + r * 5:4 + r * 5 + 5, 3 + c * 5:3 + c * 5 + 5].reshape(-1, 3).astype(int)
                expected = [(int(s) + 12) // 25 for s in cell.sum(axis=0)]
                self.assertEqual(list(tpl.means[r, c]), expected)
        self.assertEqual(len(tpl.hex_string), 6 * 6)

    def test_too_small_returns_none(self):
        pixels = np.zeros((10, 10, 3), dtype=np.uint8)
        self.assertIsNone(BlockTemplate.make(pixels, 0, 0, 4, 10, 5))


class SearchTest(unittest.TestCase):
    def test_exact_copy_is_found_with_score_one(self):
        rng = np.random.default_rng(2)
        pixels = rng.integers(0, 256, size=(120, 160, 3), dtype=np.uint8)
        tpl = BlockTemplate.make(pixels, 70, 40, 20, 15, 5)
        result = search(pixels, tpl, 0, 0, 159, 119)
        self.assertEqual((result.x, result.y), (70, 40))
        # Not exactly 1: the template stores rounded means, same as the Swift tool.
        self.assertGreater(result.score, 0.998)
        self.assertIsNotNone(result.second_score)
        self.assertLess(result.second_score, 1.0)

    def test_matches_reference_distances(self):
        rng = np.random.default_rng(3)
        pixels = rng.integers(0, 256, size=(30, 36, 3), dtype=np.uint8)
        tpl = BlockTemplate.make(pixels, 10, 8, 9, 8, 3)
        x1, y1, x2, y2 = 2, 3, 33, 27
        ref = np.array(reference_distances(pixels, tpl, x1, y1, x2, y2))
        full = 255.0 * 9 * 3 * tpl.count
        by, bx = np.unravel_index(np.argmin(ref), ref.shape)
        result = search(pixels, tpl, x1, y1, x2, y2)
        self.assertEqual((result.x, result.y), (x1 + bx, y1 + by))
        self.assertAlmostEqual(result.score, 1.0 - ref[by, bx] / full)
        # Second best: minimum over positions whose rect does not overlap the best one.
        th, tw = tpl.pixel_height, tpl.pixel_width
        ys, xs = np.indices(ref.shape)
        mask = (np.abs(ys - by) >= th) | (np.abs(xs - bx) >= tw)
        self.assertAlmostEqual(result.second_score, 1.0 - ref[mask].min() / full)

    def test_search_smaller_than_template(self):
        pixels = np.zeros((50, 50, 3), dtype=np.uint8)
        tpl = BlockTemplate.make(pixels, 0, 0, 20, 20, 5)
        self.assertIsNone(search(pixels, tpl, 0, 0, 10, 10))


class CodeGenTest(unittest.TestCase):
    def test_generated_code_matches_swift_format(self):
        code = generate_code("Btn", "btn_1", PixelRect(10, 20, 100, 50), PixelRect(30, 40, 12, 8), 5, swift_double(0.9), 'a"b')
        self.assertIn('BlockTemplateSchema.fromImage(\n    10, 20, 109, 69, "btn_1", 5, 0.9, "a\\"b"\n)', code)
        self.assertIn("reads btn_1.png (12x8, captured at 30, 40)", code)

    def test_sanitizers(self):
        self.assertEqual(sanitize_property_name("9 abc-d"), "Template9abcd")
        self.assertEqual(sanitize_property_name("按钮_x"), "按钮_x")
        self.assertEqual(sanitize_image_name("按钮.png"), "png")
        self.assertEqual(sanitize_image_name("中文"), "template")
        self.assertEqual(swift_double(1), "1.0")


if __name__ == "__main__":
    unittest.main()
