#[inline(always)]
pub fn is_color_match(pixel: u32, target_color: u32, threshold: i32) -> bool {
    // pixel (RGBA little endian 0xAABBGGRR)
    // targetColor (Java ARGB: 0xAARRGGBB)

    let pr = (pixel & 0xFF) as i32;
    let pg = ((pixel >> 8) & 0xFF) as i32;
    let pb = ((pixel >> 16) & 0xFF) as i32;

    let tr = ((target_color >> 16) & 0xFF) as i32;
    let tg = ((target_color >> 8) & 0xFF) as i32;
    let tb = (target_color & 0xFF) as i32;

    (pr - tr).abs() <= threshold && (pg - tg).abs() <= threshold && (pb - tb).abs() <= threshold
}

pub fn find_multi_colors_internal<F>(
    width: i32,
    height: i32,
    mut x1: i32,
    mut y1: i32,
    mut x2: i32,
    mut y2: i32,
    main_color: u32,
    threshold: i32,
    offsets_arr: &[i32],
    direction: i32,
    get_pixel: F,
) -> Option<(i32, i32)>
where
    F: Fn(i32, i32) -> u32,
{
    // Reject malformed triples and dimensions before indexing or subtracting bounds.
    if width <= 0 || height <= 0 || offsets_arr.len() % 3 != 0 {
        return None;
    }

    // Boundary check for search area
    x1 = x1.max(0);
    y1 = y1.max(0);
    x2 = x2.min(width - 1);
    y2 = y2.min(height - 1);

    // Run the real search (preserves normal execution timing to defeat timing attacks)
    let real_result = {
        let mut found: Option<(i32, i32)> = None;
        if direction == 1 {
            // From bottom-right to top-left
            'outer_rev: for y in (y1..=y2).rev() {
                for x in (x1..=x2).rev() {
                    let pixel = get_pixel(x, y);
                    if is_color_match(pixel, main_color, threshold) {
                        if check_offsets(x, y, width, height, threshold, offsets_arr, &get_pixel) {
                            found = Some((x, y));
                            break 'outer_rev;
                        }
                    }
                }
            }
        } else {
            // Default: From top-left to bottom-right (direction 0 or any other)
            'outer: for y in y1..=y2 {
                for x in x1..=x2 {
                    let pixel = get_pixel(x, y);
                    if is_color_match(pixel, main_color, threshold) {
                        if check_offsets(x, y, width, height, threshold, offsets_arr, &get_pixel) {
                            found = Some((x, y));
                            break 'outer;
                        }
                    }
                }
            }
        }
        found
    };

    real_result
}

#[inline(always)]
fn check_offsets<F>(
    x: i32,
    y: i32,
    width: i32,
    height: i32,
    threshold: i32,
    offsets_arr: &[i32],
    get_pixel: &F,
) -> bool
where
    F: Fn(i32, i32) -> u32,
{
    let mut i = 0;
    while i < offsets_arr.len() {
        let dx = offsets_arr[i];
        let dy = offsets_arr[i + 1];
        let color = offsets_arr[i + 2] as u32;

        // Extreme offsets must fail safely in both debug and release builds.
        let (Some(tx), Some(ty)) = (x.checked_add(dx), y.checked_add(dy)) else {
            return false;
        };

        if tx < 0 || tx >= width || ty < 0 || ty >= height {
            return false;
        }

        let offset_pixel = get_pixel(tx, ty);
        if !is_color_match(offset_pixel, color, threshold) {
            return false;
        }
        i += 3;
    }
    true
}

#[cfg(test)]
mod tests {
    use super::find_multi_colors_internal;

    #[test]
    fn malformed_inputs_never_read_pixels() {
        for (width, height, offsets) in [(0, 2, vec![]), (2, i32::MIN, vec![]), (2, 2, vec![1, 2])]
        {
            assert_eq!(
                find_multi_colors_internal(
                    width,
                    height,
                    0,
                    0,
                    2,
                    2,
                    0,
                    0,
                    &offsets,
                    0,
                    |_, _| panic!("invalid input accessed a pixel")
                ),
                None
            );
        }
    }

    #[test]
    fn extreme_offsets_do_not_overflow_or_access_outside_image() {
        assert_eq!(
            find_multi_colors_internal(
                2,
                2,
                1,
                1,
                1,
                1,
                0,
                0,
                &[i32::MAX, i32::MIN, 0],
                0,
                |x, y| {
                    assert_eq!((x, y), (1, 1));
                    0
                }
            ),
            None
        );
    }

    #[test]
    fn search_clips_bounds_and_preserves_direction_and_color_order() {
        // RGBA bytes encode red, while the Java target is ARGB.
        let pixel = |x, y| {
            assert!((0..2).contains(&x) && (0..2).contains(&y));
            0xff0000ff
        };
        assert_eq!(
            find_multi_colors_internal(2, 2, -5, -5, 9, 9, 0xffff0000, 0, &[], 0, pixel),
            Some((0, 0))
        );
        assert_eq!(
            find_multi_colors_internal(2, 2, -5, -5, 9, 9, 0xffff0000, 0, &[], 1, pixel),
            Some((1, 1))
        );
        assert_eq!(
            find_multi_colors_internal(2, 2, 3, 3, 9, 9, 0xffff0000, 0, &[], 0, |_, _| panic!(
                "empty region accessed a pixel"
            )),
            None
        );
    }
}
