//! Porting 1:1 dari ImageEffects.radialBlurPixels + sampleBilinear(Clamped).
//! Murni piksel + angka. Akumulasi PREMULTIPLIED (warna = c*a/255).

const TAPS: usize = 21;

#[inline]
fn clamp_f(v: f32, lo: f32, hi: f32) -> f32 {
    if v < lo { lo } else if v > hi { hi } else { v }
}

#[inline]
fn to_i(v: f32) -> i32 {
    v as i32
}

/// Bilinear premultiplied. `clamp_edges = true` → tepi direplikasi;
/// kalau false, sampel keluar bitmap = transparan (kontribusi nol).
fn sample_bilinear(
    pixels: &[i32],
    width: usize,
    height: usize,
    x: f32,
    y: f32,
    clamp_edges: bool,
) -> i32 {
    if !clamp_edges && (x < 0.0 || y < 0.0 || x > (width - 1) as f32 || y > (height - 1) as f32) {
        return 0;
    }
    let xc = clamp_f(x, 0.0, (width - 1) as f32);
    let yc = clamp_f(y, 0.0, (height - 1) as f32);
    let x0 = xc as usize;
    let y0 = yc as usize;
    let x1 = (x0 + 1).min(width - 1);
    let y1 = (y0 + 1).min(height - 1);
    let fx = xc - x0 as f32;
    let fy = yc - y0 as f32;

    let p00 = pixels[y0 * width + x0];
    let p10 = pixels[y0 * width + x1];
    let p01 = pixels[y1 * width + x0];
    let p11 = pixels[y1 * width + x1];
    let w00 = (1.0 - fx) * (1.0 - fy);
    let w10 = fx * (1.0 - fy);
    let w01 = (1.0 - fx) * fy;
    let w11 = fx * fy;

    // c*a/255 di-interpolasi, BUKAN straight-alpha lalu dikali (menggelap).
    let prem = |shift: u32| -> f32 {
        let ch = |p: i32| -> f32 { ((p as u32 >> shift) & 0xFF) as f32 };
        let a = |p: i32| -> f32 { ((p as u32 >> 24) & 0xFF) as f32 };
        (ch(p00) * a(p00) * w00
            + ch(p10) * a(p10) * w10
            + ch(p01) * a(p01) * w01
            + ch(p11) * a(p11) * w11) / 255.0
    };
    let raw_a = ((p00 as u32 >> 24) & 0xFF) as f32 * w00
        + ((p10 as u32 >> 24) & 0xFF) as f32 * w10
        + ((p01 as u32 >> 24) & 0xFF) as f32 * w01
        + ((p11 as u32 >> 24) & 0xFF) as f32 * w11;
    let ch = |shift: u32| -> i32 { (prem(shift) + 0.5) as i32 };
    ((to_i(raw_a + 0.5) << 24) | (ch(16) << 16) | (ch(8) << 8) | ch(0))
        as u32 as i32
}

pub fn radial_blur_pixels(
    pixels: &[i32],
    width: usize,
    height: usize,
    strength: f32,
    mode: i32, // 0 = SPIN, 1 = ZOOM
    center_x: f32,
    center_y: f32,
    clamp_edges: bool,
) -> Vec<i32> {
    let s = clamp_f(strength, 0.0, 1.0);
    if s <= 0.0 || width == 0 || height == 0 || pixels.len() < width * height {
        return pixels.to_vec();
    }
    let cx = clamp_f(center_x, 0.0, 1.0) * (width - 1) as f32;
    let cy = clamp_f(center_y, 0.0, 1.0) * (height - 1) as f32;
    let mut out = vec![0i32; width * height];

    let max_angle = 2.0 * s;
    let max_zoom = 0.6 * s;
    let center_radius2 = 1.4f32 * 1.4f32;
    let mut tap_weight = [0f32; TAPS];
    let mut tap_ca = [0f32; TAPS];
    let mut tap_sa = [0f32; TAPS];
    let mut tap_m = [0f32; TAPS];
    for t in 0..TAPS {
        let f = t as f32 / (TAPS - 1) as f32 - 0.5;
        tap_weight[t] = (-(f * f) * 8.0).exp();
        if mode == 1 {
            tap_m[t] = 1.0 + f * max_zoom;
        } else {
            let ang = f * max_angle;
            tap_ca[t] = ang.cos();
            tap_sa[t] = ang.sin();
        }
    }

    for y in 0..height {
        for x in 0..width {
            let idx = y * width + x;
            let dx = x as f32 - cx;
            let dy = y as f32 - cy;
            let dist2 = dx * dx + dy * dy;
            if dist2 <= center_radius2 {
                out[idx] = pixels[idx];
                continue;
            }
            let mut sum_a = 0f32;
            let mut sum_r = 0f32;
            let mut sum_g = 0f32;
            let mut sum_b = 0f32;
            let mut sum_w = 0f32;
            for t in 0..TAPS {
                let weight = tap_weight[t];
                let (sx, sy) = if mode == 1 {
                    let m = tap_m[t];
                    (cx + dx * m, cy + dy * m)
                } else {
                    let ca = tap_ca[t];
                    let sa = tap_sa[t];
                    (cx + dx * ca - dy * sa, cy + dx * sa + dy * ca)
                };
                let sampled = sample_bilinear(pixels, width, height, sx, sy, clamp_edges);
                sum_w += weight;
                let sa = (sampled as u32 >> 24) & 0xFF;
                if sa != 0 {
                    sum_a += weight * sa as f32;
                    sum_r += weight * (((sampled >> 16) & 0xFF) as f32);
                    sum_g += weight * (((sampled >> 8) & 0xFF) as f32);
                    sum_b += weight * ((sampled & 0xFF) as f32);
                }
            }
            out[idx] = if sum_w <= 0.0 {
                pixels[idx]
            } else if sum_a < 0.5 {
                0
            } else {
                let aa = (sum_a / sum_w + 0.5) as i32;
                let inv = 255.0 / sum_a;
                ((aa.clamp(0, 255) << 24)
                    | ((sum_r * inv + 0.5) as i32).clamp(0, 255) << 16
                    | ((sum_g * inv + 0.5) as i32).clamp(0, 255) << 8
                    | ((sum_b * inv + 0.5) as i32).clamp(0, 255)) as u32 as i32
            };
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pusat_tidak_berubah_dan_alpha_tidak_rontok_di_tepi() {
        let w = 16usize;
        let h = 16usize;
        let src = vec![0xFF_FF_80_40u32 as i32; w * h];
        let out = radial_blur_pixels(&src, w, h, 0.8, 1, 0.5, 0.5, true);
        assert_eq!(out[(h / 2) * w + w / 2], src[(h / 2) * w + w / 2]);
        for p in &out {
            assert_eq!(((*p as u32) >> 24) & 0xFF, 255);
        }
    }

    #[test]
    fn spin_deterministik() {
        let w = 24usize;
        let h = 24usize;
        let src = vec![0xFF_FF_00_00u32 as i32; w * h];
        assert_eq!(
            radial_blur_pixels(&src, w, h, 0.7, 0, 0.5, 0.5, false),
            radial_blur_pixels(&src, w, h, 0.7, 0, 0.5, 0.5, false)
        );
    }
}
