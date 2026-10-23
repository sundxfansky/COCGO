//! Block-mean template matching.
//!
//! The template is described by the mean RGB color of every `block x block` cell
//! (generated offline by the ImageMatchTool). At runtime the search area is reduced
//! to per-pixel block sums once, so every candidate position costs one lookup per
//! template block instead of one per template pixel. No rotation or scaling is
//! considered: the template is matched at its original size.
//!
//! This module has no crate dependencies so it can be unit tested on the host with
//! `rustc --test src/color/block_match.rs`.

/// Largest supported block edge. 255 * 16 * 16 = 65280 still fits a `u16` block sum.
pub const MAX_BLOCK: i32 = 16;

/// Number of best coarse candidates kept for the refinement pass.
const MAX_CANDIDATES: usize = 16;

/// Template made of block means.
pub struct BlockTemplate<'a> {
    pub block: i32,
    pub cols: i32,
    pub rows: i32,
    /// Row-major block means encoded as 0xRRGGBB.
    pub means: &'a [u32],
}

/// Result of a successful match. `x`/`y` is the template's top-left pixel.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct BlockMatch {
    pub x: i32,
    pub y: i32,
    pub score: f64,
}

/// Block sums of the search area: for every top-left position (x, y) the sum of each
/// channel over the `block x block` square starting there.
struct BlockSums {
    width: usize,
    data: Vec<u16>,
}

impl BlockSums {
    #[inline(always)]
    fn at(&self, x: usize, y: usize) -> (i32, i32, i32) {
        let i = (y * self.width + x) * 3;
        (self.data[i] as i32, self.data[i + 1] as i32, self.data[i + 2] as i32)
    }
}

/// Searches `[x1, x2] x [y1, y2]` (inclusive) for the position where the template fits
/// best. The whole template must lie inside the search area. Returns the best match
/// whose similarity is at least `min_score` (0.0..=1.0).
pub fn find_block_template<F>(
    width: i32,
    height: i32,
    x1: i32,
    y1: i32,
    x2: i32,
    y2: i32,
    tpl: &BlockTemplate,
    min_score: f64,
    get_pixel: F,
) -> Option<BlockMatch>
where
    F: Fn(i32, i32) -> u32,
{
    let block = tpl.block;
    if width <= 0 || height <= 0 || block <= 0 || block > MAX_BLOCK || tpl.cols <= 0 || tpl.rows <= 0 {
        return None;
    }
    let block_count = (tpl.cols as usize).checked_mul(tpl.rows as usize)?;
    if tpl.means.len() != block_count || !(0.0..=1.0).contains(&min_score) {
        return None;
    }

    // Clip the search area to the image.
    let x1 = x1.max(0);
    let y1 = y1.max(0);
    let x2 = x2.min(width - 1);
    let y2 = y2.min(height - 1);
    let tw = tpl.cols.checked_mul(block)?;
    let th = tpl.rows.checked_mul(block)?;
    let rw = x2.checked_sub(x1)?.checked_add(1)?;
    let rh = y2.checked_sub(y1)?.checked_add(1)?;
    if rw < tw || rh < th {
        return None;
    }

    let sums = build_block_sums(x1, y1, rw, rh, block, &get_pixel);

    // Template means scaled to block sums, ordered so the most distinctive blocks are
    // compared first. Distinctive blocks reject wrong positions earliest.
    let area = (block * block) as i64;
    let order = block_order(tpl);

    // The largest positions the template's top-left corner may take inside the area.
    let max_px = (rw - tw) as usize;
    let max_py = (rh - th) as usize;

    // Maximum total channel difference: 255 per channel per pixel.
    let full_scale = 255.0 * area as f64 * 3.0 * block_count as f64;
    let final_budget = ((1.0 - min_score) * full_scale) as i64;

    let ctx = ScoreCtx { sums: &sums, order: &order };

    // Coarse pass with a step of half a block. Instead of applying the final threshold
    // (a misaligned true position may score lower than it really is), it keeps the best
    // candidates and prunes with the worst kept difference once the list is full.
    let step = ((block / 2).max(1)) as usize;
    let mut candidates: Vec<(i64, usize, usize)> = Vec::new();
    let mut coarse_budget = i64::MAX;
    let mut py = 0;
    while py <= max_py {
        let mut px = 0;
        while px <= max_px {
            if let Some(diff) = ctx.diff(px, py, coarse_budget) {
                push_candidate(&mut candidates, diff, px, py, step);
                if candidates.len() == MAX_CANDIDATES {
                    coarse_budget = candidates.iter().map(|c| c.0).max().unwrap_or(i64::MAX);
                }
            }
            px += step;
        }
        py += step;
    }

    // Refinement pass: every pixel offset around each coarse candidate. The budget
    // tightens to the best difference found so far.
    let mut best: Option<(i64, usize, usize)> = None;
    let mut budget = final_budget;
    // Merged neighbours can sit up to two steps from the true position.
    let radius = if step > 1 { step * 2 } else { 0 };
    for &(_, cx, cy) in &candidates {
        let sx = cx.saturating_sub(radius);
        let sy = cy.saturating_sub(radius);
        let ex = (cx + radius).min(max_px);
        let ey = (cy + radius).min(max_py);
        for ry in sy..=ey {
            for rx in sx..=ex {
                if let Some(diff) = ctx.diff(rx, ry, budget) {
                    let better = match best {
                        None => true,
                        // Ties keep the top-most, then left-most position for determinism.
                        Some((bd, bx, by)) => diff < bd || (diff == bd && (ry, rx) < (by, bx)),
                    };
                    if better {
                        best = Some((diff, rx, ry));
                        budget = diff;
                    }
                }
            }
        }
    }

    best.map(|(diff, px, py)| BlockMatch {
        x: x1 + px as i32,
        y: y1 + py as i32,
        score: 1.0 - diff as f64 / full_scale,
    })
}

/// Template block (pixel offset inside the template, scaled RGB sums).
struct OrderedBlock {
    dx: usize,
    dy: usize,
    r: i32,
    g: i32,
    b: i32,
}

struct ScoreCtx<'a> {
    sums: &'a BlockSums,
    order: &'a [OrderedBlock],
}

impl ScoreCtx<'_> {
    /// Total absolute channel difference at (px, py), or None once it exceeds `budget`.
    #[inline(always)]
    fn diff(&self, px: usize, py: usize, budget: i64) -> Option<i64> {
        let mut total: i64 = 0;
        for b in self.order {
            let (r, g, bl) = self.sums.at(px + b.dx, py + b.dy);
            total += ((r - b.r).abs() + (g - b.g).abs() + (bl - b.b).abs()) as i64;
            if total > budget {
                return None;
            }
        }
        Some(total)
    }
}

/// Builds the comparison order: blocks sorted by distance from the template's overall
/// mean color, most distinctive first.
fn block_order(tpl: &BlockTemplate) -> Vec<OrderedBlock> {
    let area = tpl.block * tpl.block;
    let n = tpl.means.len() as i64;
    let (mut sr, mut sg, mut sb) = (0i64, 0i64, 0i64);
    for &m in tpl.means {
        sr += ((m >> 16) & 0xFF) as i64;
        sg += ((m >> 8) & 0xFF) as i64;
        sb += (m & 0xFF) as i64;
    }
    let (ar, ag, ab) = ((sr / n) as i32, (sg / n) as i32, (sb / n) as i32);

    let mut keyed: Vec<(i32, OrderedBlock)> = tpl
        .means
        .iter()
        .enumerate()
        .map(|(i, &m)| {
            let r = ((m >> 16) & 0xFF) as i32;
            let g = ((m >> 8) & 0xFF) as i32;
            let b = (m & 0xFF) as i32;
            let col = i % tpl.cols as usize;
            let row = i / tpl.cols as usize;
            let key = (r - ar).abs() + (g - ag).abs() + (b - ab).abs();
            (
                key,
                OrderedBlock {
                    dx: col * tpl.block as usize,
                    dy: row * tpl.block as usize,
                    r: r * area,
                    g: g * area,
                    b: b * area,
                },
            )
        })
        .collect();
    // Stable sort keeps row-major order among equally distinctive blocks.
    keyed.sort_by(|a, b| b.0.cmp(&a.0));
    keyed.into_iter().map(|(_, b)| b).collect()
}

/// Keeps the best coarse candidates, merging candidates that sit next to each other.
fn push_candidate(list: &mut Vec<(i64, usize, usize)>, diff: i64, x: usize, y: usize, step: usize) {
    if let Some(near) = list
        .iter_mut()
        .find(|c| c.1.abs_diff(x) <= step && c.2.abs_diff(y) <= step)
    {
        if diff < near.0 {
            *near = (diff, x, y);
        }
        return;
    }
    if list.len() < MAX_CANDIDATES {
        list.push((diff, x, y));
        return;
    }
    // Replace the worst candidate if this one is better.
    if let Some(worst) = list.iter_mut().max_by_key(|c| c.0) {
        if diff < worst.0 {
            *worst = (diff, x, y);
        }
    }
}

/// Computes block sums for every top-left position inside the `rw x rh` area at (x0, y0)
/// with two sliding-window passes (horizontal, then vertical).
fn build_block_sums<F>(x0: i32, y0: i32, rw: i32, rh: i32, block: i32, get_pixel: &F) -> BlockSums
where
    F: Fn(i32, i32) -> u32,
{
    let rw = rw as usize;
    let rh = rh as usize;
    let b = block as usize;
    let hw = rw - b + 1; // positions per row
    let vh = rh - b + 1; // positions per column

    // Horizontal sums: hs[y][x] = sum of pixels (x..x+b, y).
    let mut hs = vec![0u16; hw * rh * 3];
    let mut row = vec![0u32; rw * 3];
    for y in 0..rh {
        for x in 0..rw {
            let p = get_pixel(x0 + x as i32, y0 + y as i32);
            row[x * 3] = p & 0xFF;
            row[x * 3 + 1] = (p >> 8) & 0xFF;
            row[x * 3 + 2] = (p >> 16) & 0xFF;
        }
        for c in 0..3 {
            let mut acc: u32 = (0..b).map(|x| row[x * 3 + c]).sum();
            hs[(y * hw) * 3 + c] = acc as u16;
            for x in 1..hw {
                acc = acc + row[(x + b - 1) * 3 + c] - row[(x - 1) * 3 + c];
                hs[(y * hw + x) * 3 + c] = acc as u16;
            }
        }
    }

    // Vertical sums over the horizontal sums give the full block sums.
    let mut data = vec![0u16; hw * vh * 3];
    for i in 0..hw * 3 {
        let mut acc: u32 = (0..b).map(|y| hs[y * hw * 3 + i] as u32).sum();
        data[i] = acc as u16;
        for y in 1..vh {
            acc = acc + hs[(y + b - 1) * hw * 3 + i] as u32 - hs[(y - 1) * hw * 3 + i] as u32;
            data[y * hw * 3 + i] = acc as u16;
        }
    }

    BlockSums { width: hw, data }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Deterministic pseudo-random RGBA image (little endian 0xAABBGGRR).
    fn noise_image(w: i32, h: i32, seed: u32) -> Vec<u32> {
        let mut s = seed;
        (0..w * h)
            .map(|_| {
                s = s.wrapping_mul(1_103_515_245).wrapping_add(12_345);
                0xFF00_0000 | (s >> 8) & 0x00FF_FFFF
            })
            .collect()
    }

    /// Builds block means (0xRRGGBB) of a template cut from `img` at (tx, ty).
    fn make_template(img: &[u32], w: i32, tx: i32, ty: i32, block: i32, cols: i32, rows: i32) -> Vec<u32> {
        let mut out = Vec::new();
        for r in 0..rows {
            for c in 0..cols {
                let (mut sr, mut sg, mut sb) = (0u32, 0u32, 0u32);
                for dy in 0..block {
                    for dx in 0..block {
                        let p = img[((ty + r * block + dy) * w + tx + c * block + dx) as usize];
                        sr += p & 0xFF;
                        sg += (p >> 8) & 0xFF;
                        sb += (p >> 16) & 0xFF;
                    }
                }
                let a = (block * block) as u32;
                let round = |v: u32| (v + a / 2) / a;
                out.push((round(sr) << 16) | (round(sg) << 8) | round(sb));
            }
        }
        out
    }

    #[test]
    fn finds_exact_template_at_unaligned_position() {
        let (w, h) = (200, 120);
        let img = noise_image(w, h, 7);
        let means = make_template(&img, w, 73, 41, 5, 6, 4);
        let tpl = BlockTemplate { block: 5, cols: 6, rows: 4, means: &means };
        let m = find_block_template(w, h, 0, 0, w - 1, h - 1, &tpl, 0.95, |x, y| img[(y * w + x) as usize])
            .expect("template should be found");
        assert_eq!((m.x, m.y), (73, 41));
        assert!(m.score > 0.99, "score {}", m.score);
    }

    #[test]
    fn respects_search_area_and_threshold() {
        let (w, h) = (120, 80);
        let img = noise_image(w, h, 11);
        let means = make_template(&img, w, 60, 30, 4, 5, 5);
        let tpl = BlockTemplate { block: 4, cols: 5, rows: 5, means: &means };
        let px = |x: i32, y: i32| img[(y * w + x) as usize];
        // Area that does not contain the template: best score on noise stays far below 0.95.
        assert_eq!(find_block_template(w, h, 0, 0, 50, 79, &tpl, 0.95, px), None);
        // Area that just fits the template.
        let m = find_block_template(w, h, 60, 30, 79, 49, &tpl, 0.95, px).unwrap();
        assert_eq!((m.x, m.y), (60, 30));
    }

    #[test]
    fn tolerates_small_color_noise() {
        let (w, h) = (160, 100);
        let img = noise_image(w, h, 3);
        let means = make_template(&img, w, 31, 52, 5, 8, 6);
        // Shift every channel by up to 6 levels.
        let noisy: Vec<u32> = img
            .iter()
            .enumerate()
            .map(|(i, &p)| {
                let d = (i % 13) as u32 / 2;
                let ch = |s: u32| ((((p >> s) & 0xFF) + d).min(255)) << s;
                0xFF00_0000 | ch(0) | ch(8) | ch(16)
            })
            .collect();
        let tpl = BlockTemplate { block: 5, cols: 8, rows: 6, means: &means };
        let m = find_block_template(w, h, 0, 0, w - 1, h - 1, &tpl, 0.97, |x, y| noisy[(y * w + x) as usize]).unwrap();
        assert_eq!((m.x, m.y), (31, 52));
    }

    #[test]
    fn large_area_with_smooth_image_is_fast() {
        // Smooth gradient background with a distinctive patch, close to real UI screens.
        let (w, h) = (1280, 720);
        let mut img: Vec<u32> = (0..w * h)
            .map(|i| {
                let (x, y) = ((i % w) as u32, (i / w) as u32);
                0xFF00_0000 | ((x * 255 / w as u32) & 0xFF) | (((y * 255 / h as u32) & 0xFF) << 8) | (0x40 << 16)
            })
            .collect();
        for y in 400..460 {
            for x in 900..980 {
                img[(y * w + x) as usize] = 0xFF00_0000 | (((x * 7 + y * 3) % 256) as u32) << 16 | 0x30;
            }
        }
        let means = make_template(&img, w, 895, 395, 5, 18, 14);
        let tpl = BlockTemplate { block: 5, cols: 18, rows: 14, means: &means };
        let start = std::time::Instant::now();
        let m = find_block_template(w, h, 0, 0, w - 1, h - 1, &tpl, 0.95, |x, y| img[(y * w + x) as usize]).unwrap();
        let elapsed = start.elapsed();
        assert_eq!((m.x, m.y), (895, 395));
        println!("full-screen search took {:?}", elapsed);
    }

    #[test]
    fn rejects_invalid_input_without_reading_pixels() {
        let means = [0u32; 4];
        let panic_px = |_: i32, _: i32| -> u32 { panic!("pixel accessed") };
        let cases = [
            BlockTemplate { block: 0, cols: 2, rows: 2, means: &means },
            BlockTemplate { block: 17, cols: 2, rows: 2, means: &means },
            BlockTemplate { block: 2, cols: 3, rows: 2, means: &means },
        ];
        for tpl in &cases {
            assert_eq!(find_block_template(100, 100, 0, 0, 99, 99, tpl, 0.9, panic_px), None);
        }
        let ok = BlockTemplate { block: 5, cols: 2, rows: 2, means: &means };
        // Search area smaller than the template.
        assert_eq!(find_block_template(100, 100, 0, 0, 8, 8, &ok, 0.9, panic_px), None);
        // Out-of-range similarity.
        assert_eq!(find_block_template(100, 100, 0, 0, 99, 99, &ok, 1.5, panic_px), None);
    }
}
