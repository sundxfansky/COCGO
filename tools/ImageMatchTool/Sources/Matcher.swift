import CoreGraphics
import Foundation

/// RGBA8 pixel buffer decoded from a CGImage, row-major, 4 bytes per pixel.
struct PixelBuffer {
    let width: Int
    let height: Int
    let data: [UInt8]

    init?(image: CGImage) {
        let width = image.width
        let height = image.height
        // Keep the source RGB space so screenshot colors are not converted.
        let space: CGColorSpace = (image.colorSpace?.model == .rgb ? image.colorSpace : nil)
            ?? CGColorSpace(name: CGColorSpace.sRGB)!
        var bytes = [UInt8](repeating: 0, count: width * height * 4)
        let ok = bytes.withUnsafeMutableBytes { raw -> Bool in
            guard let ctx = CGContext(
                data: raw.baseAddress, width: width, height: height, bitsPerComponent: 8,
                bytesPerRow: width * 4, space: space,
                bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
            ) else { return false }
            ctx.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
            return true
        }
        guard ok else { return nil }
        self.width = width
        self.height = height
        data = bytes
    }

    @inline(__always)
    func rgb(_ x: Int, _ y: Int) -> (Int, Int, Int) {
        let i = (y * width + x) * 4
        return (Int(data[i]), Int(data[i + 1]), Int(data[i + 2]))
    }
}

/// Block-mean template, identical to what the Android side expects.
struct BlockTemplate {
    let block: Int
    let cols: Int
    let rows: Int
    /// 0xRRGGBB per block, row-major.
    let means: [UInt32]

    var pixelWidth: Int { cols * block }
    var pixelHeight: Int { rows * block }

    /// Builds the template from the pixel rect (x, y, w, h). Trailing pixels that do not
    /// fill a whole block are ignored, matching the runtime matcher.
    static func make(from buf: PixelBuffer, x: Int, y: Int, w: Int, h: Int, block: Int) -> BlockTemplate? {
        let cols = w / block
        let rows = h / block
        guard block > 0, cols > 0, rows > 0 else { return nil }
        let area = block * block
        var means: [UInt32] = []
        means.reserveCapacity(cols * rows)
        for r in 0..<rows {
            for c in 0..<cols {
                var sr = 0, sg = 0, sb = 0
                for dy in 0..<block {
                    for dx in 0..<block {
                        let p = buf.rgb(x + c * block + dx, y + r * block + dy)
                        sr += p.0; sg += p.1; sb += p.2
                    }
                }
                // Rounded mean per channel.
                let mr = (sr + area / 2) / area, mg = (sg + area / 2) / area, mb = (sb + area / 2) / area
                means.append(UInt32(mr << 16 | mg << 8 | mb))
            }
        }
        return BlockTemplate(block: block, cols: cols, rows: rows, means: means)
    }

    /// Concatenated "RRGGBB" hex string used by BlockTemplateSchema.parse.
    var hexString: String {
        means.map { String(format: "%06X", $0) }.joined()
    }
}

struct MatchResult {
    let x: Int
    let y: Int
    let score: Double
    /// Best score at a position that does not overlap the best match (nil if none).
    let secondScore: Double?
    let elapsedMs: Double
}

enum Matcher {
    /// Exhaustive search over every pixel position of the inclusive search rect.
    /// Unlike the runtime matcher it always reports the best score, even if it is low,
    /// so the threshold can be tuned.
    static func search(buf: PixelBuffer, tpl: BlockTemplate, x1: Int, y1: Int, x2: Int, y2: Int) -> MatchResult? {
        let start = Date()
        let sx1 = max(0, x1), sy1 = max(0, y1)
        let sx2 = min(buf.width - 1, x2), sy2 = min(buf.height - 1, y2)
        let rw = sx2 - sx1 + 1, rh = sy2 - sy1 + 1
        let tw = tpl.pixelWidth, th = tpl.pixelHeight
        guard rw >= tw, rh >= th else { return nil }

        // Integral image per channel over the search rect, with one extra leading row/column.
        let iw = rw + 1
        var integral = [Int](repeating: 0, count: iw * (rh + 1) * 3)
        for y in 0..<rh {
            var rowR = 0, rowG = 0, rowB = 0
            for x in 0..<rw {
                let p = buf.rgb(sx1 + x, sy1 + y)
                rowR += p.0; rowG += p.1; rowB += p.2
                let i = ((y + 1) * iw + x + 1) * 3
                let up = (y * iw + x + 1) * 3
                integral[i] = integral[up] + rowR
                integral[i + 1] = integral[up + 1] + rowG
                integral[i + 2] = integral[up + 2] + rowB
            }
        }

        let b = tpl.block
        let area = b * b
        let blocks: [(dx: Int, dy: Int, r: Int, g: Int, b: Int)] = tpl.means.enumerated().map { i, m in
            (dx: (i % tpl.cols) * b, dy: (i / tpl.cols) * b,
             r: Int(m >> 16 & 0xFF) * area, g: Int(m >> 8 & 0xFF) * area, b: Int(m & 0xFF) * area)
        }
        let fullScale = 255.0 * Double(area) * 3.0 * Double(blocks.count)

        var best: (d: Int, x: Int, y: Int)? = nil
        var second: (d: Int, x: Int, y: Int)? = nil
        integral.withUnsafeBufferPointer { ii in
            @inline(__always) func blockSum(_ x: Int, _ y: Int, _ c: Int) -> Int {
                let a = (y * iw + x) * 3 + c
                let bb = (y * iw + x + b) * 3 + c
                let cc = ((y + b) * iw + x) * 3 + c
                let d = ((y + b) * iw + x + b) * 3 + c
                return ii[d] - ii[bb] - ii[cc] + ii[a]
            }
            for py in 0...(rh - th) {
                for px in 0...(rw - tw) {
                    // Prune with the second best so both top results stay exact.
                    let budget = second?.d ?? Int.max
                    var total = 0
                    var pruned = false
                    for blk in blocks {
                        let x = px + blk.dx, y = py + blk.dy
                        total += abs(blockSum(x, y, 0) - blk.r) + abs(blockSum(x, y, 1) - blk.g) + abs(blockSum(x, y, 2) - blk.b)
                        if total > budget { pruned = true; break }
                    }
                    if pruned { continue }
                    let cand = (d: total, x: px, y: py)
                    if let cur = best {
                        let overlaps = abs(cur.x - px) < tw && abs(cur.y - py) < th
                        if total < cur.d {
                            if !overlaps { second = cur }
                            best = cand
                        } else if !overlaps && total < (second?.d ?? Int.max) {
                            second = cand
                        }
                    } else {
                        best = cand
                    }
                }
            }
        }
        guard let found = best else { return nil }
        return MatchResult(
            x: sx1 + found.x, y: sy1 + found.y,
            score: 1.0 - Double(found.d) / fullScale,
            secondScore: second.map { 1.0 - Double($0.d) / fullScale },
            elapsedMs: Date().timeIntervalSince(start) * 1000
        )
    }
}
