use crate::color::block_match::{find_block_template, BlockTemplate};
use crate::color::buffer_layout::valid_rgba_layout;
use jni::objects::{JByteBuffer, JIntArray, JObject};
use jni::sys::{jdouble, jint, jintArray};
use jni::JNIEnv;

/// Scale used to return the similarity score as an integer (score * SCORE_SCALE).
const SCORE_SCALE: f64 = 10000.0;

// Symbol name stripped — registered via RegisterNatives in JNI_OnLoad
/// Returns `[x, y, score * 10000]` of the best block-template match, or null.
pub extern "system" fn find_block_template_raw(
    env: JNIEnv,
    _class: JObject,
    byte_buffer: JByteBuffer,
    width: jint,
    height: jint,
    stride: jint,
    x1: jint,
    y1: jint,
    x2: jint,
    y2: jint,
    block: jint,
    cols: jint,
    rows: jint,
    means: JIntArray,
    min_score: jdouble,
) -> jintArray {
    let Ok(src_buf) = env.get_direct_buffer_address(&byte_buffer) else {
        return std::ptr::null_mut();
    };
    let Ok(capacity) = env.get_direct_buffer_capacity(&byte_buffer) else {
        return std::ptr::null_mut();
    };
    // JNI direct buffers may be undersized or sliced at an unaligned byte offset.
    if !valid_rgba_layout(width, height, stride, capacity) {
        return std::ptr::null_mut();
    }

    let means_len = env.get_array_length(&means).unwrap_or(0) as usize;
    let mut means_vec = vec![0i32; means_len];
    if env.get_int_array_region(&means, 0, &mut means_vec).is_err() {
        return std::ptr::null_mut();
    }
    let means_u32: Vec<u32> = means_vec.iter().map(|&m| (m as u32) & 0x00FF_FFFF).collect();

    let stride_usize = stride as usize;
    let get_pixel = |x: i32, y: i32| {
        let offset = (y as usize * stride_usize) + (x as usize * 4);
        unsafe { (src_buf.add(offset) as *const u32).read_unaligned() }
    };

    let tpl = BlockTemplate { block, cols, rows, means: &means_u32 };
    let Some(m) = find_block_template(width, height, x1, y1, x2, y2, &tpl, min_score, get_pixel) else {
        return std::ptr::null_mut();
    };

    if let Ok(res_arr) = env.new_int_array(3) {
        let buf = [m.x, m.y, (m.score * SCORE_SCALE).round() as i32];
        if env.set_int_array_region(&res_arr, 0, &buf).is_ok() {
            return res_arr.as_raw();
        }
    }
    std::ptr::null_mut()
}
