//! Porting dari ImageEffects.applyCustomTexturePixels:
//! tile bitmap bilinear premultiplied + wrap + rotasi. Murni piksel.

#[inline]
fn clamp_f(v: f32, lo: f32, hi: f32) -> f32 {
    if v < lo { lo } else if v > hi { hi } else { v }
}

#[inline]
fn fmod(a: f32, b: f32) -> f32 {
    let m = a % b;
    if m < 0.0 { m + b } else { m }
}

pub fn apply_custom_texture_pixels(
    pixels: &[i32],
    width: usize,
    height: usize,
    texture: &[i32],
    tw: usize,
    th: usize,
    strength: f32,
    scale: f32,
    angle_deg: f32,
) -> Vec<i32> {
    let s = clamp_f(strength, 0.0, 1.0);
    if s <= 0.0 || width == 0 || height == 0 || pixels.len() < width * height || tw == 0 || th == 0 {
        return pixels.to_vec();
    }
    let sc = scale.max(0.2);
    let tile_w = tw as f32 * sc;
    let tile_h = th as f32 * sc;
    let rotated = angle_deg % 360.0 != 0.0;
    let rad = (angle_deg as f64).to_radians();
    let cos_a = if rotated { rad.cos() as f32 } else { 1.0 };
    let sin_a = if rotated { rad.sin() as f32 } else { 0.0 };
    let cx = width as f32 / 2.0;
    let cy = height as f32 / 2.0;

    let mut out = vec![0i32; width * height];
    for y in 0..height {
        for x in 0..width {
            let idx = y * width + x;
            let p = pixels[idx];
            let alpha = (p as u32 >> 24) & 0xFF;
            if alpha == 0 {
                continue;
            }
            let (sx, sy) = if rotated {
                let dx = x as f32 - cx;
                let dy = y as f32 - cy;
                (dx * cos_a + dy * sin_a + cx, -dx * sin_a + dy * cos_a + cy)
            } else {
                (x as f32, y as f32)
            };
            let fx = fmod(sx, tile_w) / sc;
            let fy = fmod(sy, tile_h) / sc;
            let tx0 = (fx.floor() as usize).min(tw - 1);
            let ty0 = (fy.floor() as usize).min(th - 1);
            let tx1 = (tx0 + 1) % tw;
            let ty1 = (ty0 + 1) % th;
            let fu = clamp_f(fx - tx0 as f32, 0.0, 1.0);
            let fv = clamp_f(fy - ty0 as f32, 0.0, 1.0);

            let c00 = texture[ty0 * tw + tx0];
            let c10 = texture[ty0 * tw + tx1];
            let c01 = texture[ty1 * tw + tx0];
            let c11 = texture[ty1 * tw + tx1];
            let w00 = (1.0 - fu) * (1.0 - fv);
            let w10 = fu * (1.0 - fv);
            let w01 = (1.0 - fu) * fv;
            let w11 = fu * fv;
            let a = |p: i32| -> f32 { ((p as u32 >> 24) & 0xFF) as f32 / 255.0 };
            let ch = |p: i32, shift: u32| -> f32 { ((p as u32 >> shift) & 0xFF) as f32 / 255.0 };
            let a00 = a(c00);
            let a10 = a(c10);
            let a01 = a(c01);
            let a11 = a(c11);
            let a_t = a00 * w00 + a10 * w10 + a01 * w01 + a11 * w11;
            if a_t <= 1e-4 {
                out[idx] = p;
                continue;
            }
            let r_p = ch(c00, 16) * a00 * w00 + ch(c10, 16) * a10 * w10
                + ch(c01, 16) * a01 * w01 + ch(c11, 16) * a11 * w11;
            let g_p = ch(c00, 8) * a00 * w00 + ch(c10, 8) * a10 * w10
                + ch(c01, 8) * a01 * w01 + ch(c11, 8) * a11 * w11;
            let b_p = ch(c00, 0) * a00 * w00 + ch(c10, 0) * a10 * w10
                + ch(c01, 0) * a01 * w01 + ch(c11, 0) * a11 * w11;
            let base_r = ((p >> 16) & 0xFF) as f32 / 255.0;
            let base_g = ((p >> 8) & 0xFF) as f32 / 255.0;
            let base_b = (p & 0xFF) as f32 / 255.0;
            let e = s * a_t;
            let to_ch = |base: f32, prem: f32| -> i32 {
                (base + (clamp_f(prem / a_t, 0.0, 1.0) - base) * e) as i32
            };
            out[idx] = (alpha << 24) as i32
                | (to_ch(base_r, r_p).clamp(0, 255) << 16)
                | (to_ch(base_g, g_p).clamp(0, 255) << 8)
                | to_ch(base_b, b_p).clamp(0, 255);
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn deterministik_dan_alpha_terjaga() {
        let w = 12usize;
        let h = 12usize;
        let src = vec![0xFF_80_80_80u32 as i32; w * h];
        let tex = vec![0xFF_FF_00_00u32 as i32; 4 * 4];
        let a = apply_custom_texture_pixels(&src, w, h, &tex, 4, 4, 1.0, 1.0, 0.0);
        let b = apply_custom_texture_pixels(&src, w, h, &tex, 4, 4, 1.0, 1.0, 0.0);
        assert_eq!(a, b);
        for p in &a {
            assert_eq!(((*p as u32) >> 24) & 0xFF, 255);
        }
        assert!(a.iter().any(|p| *p != src[0]));
    }

    #[test]
    fn rotasi_mengubah_hasil() {
        let src = vec![0xFF_80_80_80u32 as i32; 16 * 16];
        let tex: Vec<i32> = (0..64)
            .map(|i| if i % 7 == 0 { 0xFF_FF_FF_FFu32 as i32 } else { 0x00_00_00_00 })
            .collect();
        let flat = apply_custom_texture_pixels(&src, 16, 16, &tex, 8, 8, 1.0, 1.0, 0.0);
        let tilt = apply_custom_texture_pixels(&src, 16, 16, &tex, 8, 8, 1.0, 1.0, 30.0);
        assert_ne!(flat, tilt);
    }
}
