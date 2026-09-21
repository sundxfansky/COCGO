/// Validate the entire readable RGBA region, including padded rows.
pub fn valid_rgba_layout(width: i32, height: i32, stride: i32, capacity: usize) -> bool {
    if width <= 0 || height <= 0 || stride <= 0 {
        return false;
    }
    let Some(row_bytes) = (width as usize).checked_mul(4) else {
        return false;
    };
    if (stride as usize) < row_bytes {
        return false;
    }
    (height as usize - 1)
        .checked_mul(stride as usize)
        .and_then(|prefix| prefix.checked_add(row_bytes))
        .is_some_and(|required| required <= capacity)
}

#[cfg(test)]
mod tests {
    use super::valid_rgba_layout;

    #[test]
    fn validates_stride_capacity_and_dimensions() {
        assert!(valid_rgba_layout(2, 2, 12, 20));
        assert!(!valid_rgba_layout(2, 2, 12, 19));
        assert!(!valid_rgba_layout(2, 2, 4, 16));
        assert!(!valid_rgba_layout(-1, 2, 8, 16));
        assert!(!valid_rgba_layout(2, 0, 8, 16));
        assert!(!valid_rgba_layout(i32::MAX, i32::MAX, i32::MAX, usize::MAX));
    }
}
