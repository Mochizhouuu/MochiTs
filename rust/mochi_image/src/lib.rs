//! JNI bastion — SATU-SATUNYA tempat yang tahu Android/JVM.
//! Core piksel ada di `texture.rs` (murni Rust, di-test tanpa SDK).

mod motion_blur;
mod texture;

use jni::objects::JIntArray;
use jni::sys::{jfloat, jint, jintArray};
use jni::JNIEnv;

/// Dipanggil dari Kotlin:
/// `RustNative.applyTexture(...)` (top-level Kotlin)
///
/// Nama symbol = Java_com_mochits_app_imaging_RustNative_applyTexture.
/// Karena tidak memakai `jni` crate gen macros, signature JNI ditulis eksplisit.
#[no_mangle]
pub extern "C" fn Java_com_mochits_app_imaging_RustNative_applyTexture<'local>(
    env: JNIEnv<'local>,
    _class: jni::objects::JClass<'local>,
    src: jintArray,
    width: jint,
    height: jint,
    preset: jint,
    strength: jfloat,
    scale: jfloat,
    tint: jint,
    angle_deg: jfloat,
) -> jintArray {
    if width <= 0 || height <= 0 {
        return src;
    }
    let len = (width as usize).saturating_mul(height as usize);
    let src_arr = unsafe { JIntArray::from_raw(src) };
    let mut input = vec![0i32; len];
    if env.get_int_array_region(&src_arr, 0, &mut input).is_err() {
        return src;
    }

    let out = texture::apply_texture_pixels(
        &input,
        width as usize,
        height as usize,
        preset,
        strength,
        scale,
        tint as u32,
        angle_deg,
    );

    match env.new_int_array(len as jni::sys::jsize) {
        Ok(arr) => {
            if env.set_int_array_region(&arr, 0, &out).is_err() {
                return src;
            }
            arr.into_raw()
        }
        Err(_) => src,
    }
}

/// JNI untuk `RustNative.blurDirectional` (top-level Kotlin).
#[no_mangle]
pub extern "C" fn Java_com_mochits_app_imaging_RustNative_blurDirectional<'local>(
    env: JNIEnv<'local>,
    _class: jni::objects::JClass<'local>,
    src: jintArray,
    width: jint,
    height: jint,
    angle_deg: jfloat,
    radius: jfloat,
) -> jintArray {
    if width <= 0 || height <= 0 {
        return src;
    }
    let len = (width as usize).saturating_mul(height as usize);
    let src_arr = unsafe { JIntArray::from_raw(src) };
    let mut input = vec![0i32; len];
    if env.get_int_array_region(&src_arr, 0, &mut input).is_err() {
        return src;
    }
    let out = motion_blur::blur_directional(
        &input, width as usize, height as usize, angle_deg, radius,
    );
    match env.new_int_array(len as jni::sys::jsize) {
        Ok(arr) => {
            if env.set_int_array_region(&arr, 0, &out).is_err() {
                return src;
            }
            arr.into_raw()
        }
        Err(_) => src,
    }
}
