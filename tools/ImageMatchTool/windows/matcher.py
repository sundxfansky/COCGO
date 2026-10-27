"""Block-mean template building and exhaustive matching.

Python port of tools/ImageMatchTool/Sources/Matcher.swift. The block means must stay identical to
BlockTemplateSchema.blockMeans on the Android side, so the rounding and the ignored trailing pixels
follow the Swift and Kotlin code exactly.
"""

from __future__ import annotations

import time
from dataclasses import dataclass
from typing import List, Optional

import numpy as np


@dataclass(frozen=True)
class BlockTemplate:
    block: int
    cols: int
    rows: int
    # Rounded per-block channel means, shape (rows, cols, 3), row-major like the Kotlin IntArray.
    means: np.ndarray

    @property
    def pixel_width(self) -> int:
        return self.cols * self.block

    @property
    def pixel_height(self) -> int:
        return self.rows * self.block

    @property
    def count(self) -> int:
        return self.cols * self.rows

    @property
    def hex_string(self) -> str:
        """Concatenated "RRGGBB" hex string, same as BlockTemplate.hexString in Swift."""
        return "".join("%02X%02X%02X" % tuple(int(v) for v in m) for m in self.means.reshape(-1, 3))

    @staticmethod
    def make(pixels: np.ndarray, x: int, y: int, w: int, h: int, block: int) -> Optional["BlockTemplate"]:
        """Builds the template from the pixel rect (x, y, w, h) of an (H, W, 3) uint8 RGB array.

        Trailing pixels that do not fill a whole block are ignored, matching the runtime matcher.
        """
        if block <= 0:
            return None
        cols, rows = w // block, h // block
        if cols <= 0 or rows <= 0:
            return None
        area = block * block
        crop = pixels[y:y + rows * block, x:x + cols * block].astype(np.int64)
        sums = crop.reshape(rows, block, cols, block, 3).sum(axis=(1, 3))
        # Rounded mean per channel, integer division like Swift/Kotlin.
        means = (sums + area // 2) // area
        return BlockTemplate(block=block, cols=cols, rows=rows, means=means.astype(np.int64))


@dataclass(frozen=True)
class MatchResult:
    x: int
    y: int
    score: float
    # Best score at a position that does not overlap the best match (None if there is none).
    second_score: Optional[float]
    elapsed_ms: float


def search(pixels: np.ndarray, tpl: BlockTemplate, x1: int, y1: int, x2: int, y2: int) -> Optional[MatchResult]:
    """Exhaustive search over every pixel position of the inclusive search rect.

    Unlike the runtime matcher it always reports the best score, even if it is low, so the
    threshold can be tuned. Ties resolve to the first position in raster order, like the Swift code.
    """
    start = time.perf_counter()
    height, width = pixels.shape[:2]
    sx1, sy1 = max(0, x1), max(0, y1)
    sx2, sy2 = min(width - 1, x2), min(height - 1, y2)
    rw, rh = sx2 - sx1 + 1, sy2 - sy1 + 1
    tw, th = tpl.pixel_width, tpl.pixel_height
    if rw < tw or rh < th:
        return None

    b = tpl.block
    area = b * b
    region = pixels[sy1:sy2 + 1, sx1:sx2 + 1].astype(np.int64)

    # Integral image per channel with one extra leading row/column.
    integral = np.zeros((rh + 1, rw + 1, 3), dtype=np.int64)
    integral[1:, 1:] = region.cumsum(axis=0).cumsum(axis=1)
    # Sum of every b x b block keyed by its top-left pixel; at most 255 * 256, fits int32.
    block_sums = (integral[b:, b:] - integral[:-b, b:] - integral[b:, :-b] + integral[:-b, :-b]).astype(np.int32)
    channels = [np.ascontiguousarray(block_sums[:, :, c]) for c in range(3)]

    ph, pw = rh - th + 1, rw - tw + 1
    full_scale = 255.0 * area * 3.0 * tpl.count
    # Use int32 accumulation when the worst-case distance cannot overflow it (much faster).
    acc_type = np.int32 if full_scale < 2 ** 31 else np.int64
    total = np.zeros((ph, pw), dtype=acc_type)
    diff = np.empty((ph, pw), dtype=np.int32)
    targets = tpl.means * area
    for r in range(tpl.rows):
        dy = r * b
        for c in range(tpl.cols):
            dx = c * b
            for ch in range(3):
                np.subtract(channels[ch][dy:dy + ph, dx:dx + pw], int(targets[r, c, ch]), out=diff)
                np.abs(diff, out=diff)
                total += diff

    best_index = int(np.argmin(total))
    by, bx = divmod(best_index, pw)
    best_d = int(total[by, bx])

    # Exclude every position whose template rect overlaps the best one, then take the minimum.
    masked = total.astype(np.int64)
    masked[max(0, by - th + 1):by + th, max(0, bx - tw + 1):bx + tw] = np.iinfo(np.int64).max
    second_d = int(masked.min())
    second_score = None if second_d == np.iinfo(np.int64).max else 1.0 - second_d / full_scale

    return MatchResult(
        x=sx1 + bx,
        y=sy1 + by,
        score=1.0 - best_d / full_scale,
        second_score=second_score,
        elapsed_ms=(time.perf_counter() - start) * 1000.0,
    )


def reference_distances(pixels: np.ndarray, tpl: BlockTemplate, x1: int, y1: int, x2: int, y2: int) -> List[List[int]]:
    """Slow, direct per-position distance used by the tests to validate [search]."""
    b = tpl.block
    area = b * b
    rows = []
    for py in range(y1, y2 - tpl.pixel_height + 2):
        row = []
        for px in range(x1, x2 - tpl.pixel_width + 2):
            d = 0
            for r in range(tpl.rows):
                for c in range(tpl.cols):
                    cell = pixels[py + r * b:py + r * b + b, px + c * b:px + c * b + b].astype(np.int64)
                    s = cell.reshape(-1, 3).sum(axis=0)
                    d += int(np.abs(s - tpl.means[r, c] * area).sum())
            row.append(d)
        rows.append(row)
    return rows
