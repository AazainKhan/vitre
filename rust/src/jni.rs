//! The JNI surface: `dev.glasslauncher.glass.NativeImage`. Hand-declared (no jni crate, no std, so the library is a
//! few KB): Bitmaps are locked with the NDK's libjnigraphics; the env and objects are only passed through. Each entry
//! returns false, with the bitmap untouched, unless it is a lockable ARGB_8888 bitmap, so the Kotlin side falls back.
#![cfg(target_os = "android")]

use core::ffi::c_void;

#[repr(C)]
struct Info {
    width: u32,
    height: u32,
    stride: u32,
    format: i32,
    flags: u32,
}

const RGBA_8888: i32 = 1;

#[link(name = "jnigraphics")]
extern "C" {
    fn AndroidBitmap_getInfo(env: *mut c_void, bitmap: *mut c_void, info: *mut Info) -> i32;
    fn AndroidBitmap_lockPixels(env: *mut c_void, bitmap: *mut c_void, pixels: *mut *mut c_void) -> i32;
    fn AndroidBitmap_unlockPixels(env: *mut c_void, bitmap: *mut c_void) -> i32;
}

// Linked explicitly: without std nothing else pulls in bionic, and dlopen then fails on __cxa_finalize.
#[link(name = "c")]
extern "C" {
    fn abort() -> !;
}

#[panic_handler]
fn panic(_: &core::panic::PanicInfo) -> ! {
    unsafe { abort() }
}

/// Runs [f] on the bitmap's pixels (w, h, stride) while locked.
unsafe fn with_pixels(env: *mut c_void, bitmap: *mut c_void, f: impl FnOnce(&mut [u8], usize, usize, usize)) -> u8 {
    let mut info = Info { width: 0, height: 0, stride: 0, format: 0, flags: 0 };
    if AndroidBitmap_getInfo(env, bitmap, &mut info) != 0 || info.format != RGBA_8888 || info.width == 0 || info.height == 0 {
        return 0;
    }
    let mut p: *mut c_void = core::ptr::null_mut();
    if AndroidBitmap_lockPixels(env, bitmap, &mut p) != 0 || p.is_null() {
        return 0;
    }
    let (w, h, stride) = (info.width as usize, info.height as usize, info.stride as usize);
    f(core::slice::from_raw_parts_mut(p as *mut u8, stride * (h - 1) + w * 4), w, h, stride);
    AndroidBitmap_unlockPixels(env, bitmap);
    1
}

/// `static native boolean saturate(Bitmap bitmap, float saturation)`: Android's ColorMatrix.setSaturation, in place.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_glasslauncher_glass_NativeImage_saturate(env: *mut c_void, _: *mut c_void, bitmap: *mut c_void, saturation: f32) -> u8 {
    with_pixels(env, bitmap, |px, w, h, stride| crate::saturate(px, w, h, stride, saturation))
}

/// `static native boolean scaleOffset(Bitmap bitmap, float scale, float offset)`: Blur.legible's matrix, in place.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_glasslauncher_glass_NativeImage_scaleOffset(env: *mut c_void, _: *mut c_void, bitmap: *mut c_void, scale: f32, offset: f32) -> u8 {
    with_pixels(env, bitmap, |px, w, h, stride| crate::scale_offset(px, w, h, stride, scale, offset))
}

/// With no std, nothing defines the unwinding personality that aarch64's unwind tables still name, so `dlopen` failed on every 64-bit
/// device ("cannot locate symbol rust_eh_personality") and the kernels silently fell back to Skia (P347). The build is panic=abort, so
/// it is never called. 32-bit ARM's tables do not name it, so that build is untouched.
#[cfg(target_arch = "aarch64")]
#[no_mangle]
pub extern "C" fn rust_eh_personality() {}
