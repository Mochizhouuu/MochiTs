//! Core texture MochiTs — porting 1:1 dari ImageEffects.applyTexturePixels.
//! Murni `IntArray` + angka: TIDAK memakai Android API, jadi bisa di-cargo test.
//!
//! Kode preset mengikuti `TexturePreset.ordinal`:
//! 0 NONE, 1 GRAIN, 2 PAPER, 3 CANVAS, 4 HALFTONE, 5 STRIPES,
//! 6 DOTS, 7 CHEVRON, 8 BRICKS, 9 CUSTOM.

#[inline]
fn clamp(v: f32, lo: f32, hi: f32) -> f32 {
    if v < lo { lo } else if v > hi { hi } else { v }
}

/// Modulo float yang selalu non-negatif (sampel rotasi bisa negatif).
#[inline]
fn fmod(a: f32, b: f32) -> f32 {
    let m = a % b;
    if m < 0.0 { m + b } else { m }
}

#[inline]
fn smoothstep(e0: f32, e1: f32, x: f32) -> f32 {
    let t = if e1 == e0 {
        if x < e0 { 0.0 } else { 1.0 }
    } else {
        clamp((x - e0) / (e1 - e0), 0.0, 1.0)
    };
    t * t * (3.0 - 2.0 * t)
}

/// Cakupan band a..b dengan tepi anti-alias selebar aa (0..1).
#[inline]
fn band_coverage(v: f32, a: f32, b: f32, aa: f32) -> f32 {
    smoothstep(a - aa, a + aa, v) * (1.0 - smoothstep(b - aa, b + aa, v))
}

/// Setengah lebar transisi AA dalam ruang sampel.
#[inline]
fn edge_aa(scale: f32, feature_width: f32) -> f32 {
    let pixel = 0.5 / scale.max(0.2);
    let fraction = (feature_width.max(0.05) * 0.3).min(0.6);
    clamp(pixel.min(fraction), 0.02, 0.6)
}

fn hash_noise(x: i32, y: i32, seed: i32) -> f32 {
    // Kotlin Int overflow = wrapping; `ushr` = shift logika pada u32.
    let mut h: i32 = x
        .wrapping_mul(374_761_393)
        .wrapping_add(y.wrapping_mul(668_265_263))
        .wrapping_add(seed.wrapping_mul(1_442_695_041));
    h = ((h as u32) ^ ((h as u32) >> 13)) as i32;
    h = h.wrapping_mul(1_274_126_177);
    h = ((h as u32) ^ ((h as u32) >> 16)) as i32;
    ((h as u32) & 0x7FFF_FFFF) as f32 / i32::MAX as f32
}

fn value_noise(x: f32, y: f32, seed: i32) -> f32 {
    let x0 = x.floor() as i32;
    let y0 = y.floor() as i32;
    let fx = x - x0 as f32;
    let fy = y - y0 as f32;
    let u = fx * fx * (3.0 - 2.0 * fx);
    let v = fy * fy * (3.0 - 2.0 * fy);
    let a = hash_noise(x0, y0, seed);
    let b = hash_noise(x0 + 1, y0, seed);
    let c = hash_noise(x0, y0 + 1, seed);
    let d = hash_noise(x0 + 1, y0 + 1, seed);
    a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v
}

#[inline]
fn argb(r: i32, g: i32, b: i32) -> i32 {
    r << 16 | g << 8 | b
}

#[inline]
fn to_i32(v: f32) -> i32 {
    v as i32 // Kotlin Float.toInt() = truncate toward zero
}

/// Porting langsung dari `ImageEffects.applyTexturePixels`.
pub fn apply_texture_pixels(
    pixels: &[i32],
    width: usize,
    height: usize,
    preset: i32,
    strength: f32,
    scale: f32,
    tint: u32,
    angle_deg: f32,
) -> Vec<i32> {
    let s = strength.clamp(0.0, 1.0);
    if preset == 0 || s <= 0.0 {
        return pixels.to_vec();
    }
    let mut out = vec![0i32; pixels.len()];
    let sc = scale.max(0.2);
    let rotated = angle_deg % 360.0 != 0.0;
    let rad = (angle_deg as f64).to_radians();
    let cos_a = if rotated { rad.cos() as f32 } else { 1.0 };
    let sin_a = if rotated { rad.sin() as f32 } else { 0.0 };
    let cx = width as f32 / 2.0;
    let cy = height as f32 / 2.0;
    let inv_sqrt2: f32 = 0.707_106_78;

    let tint_alpha = (tint >> 24) & 0xFF;
    let has_tint = tint_alpha > 8;
    let tint_r = ((tint >> 16) & 0xFF) as f32;
    let tint_g = ((tint >> 8) & 0xFF) as f32;
    let tint_b = (tint & 0xFF) as f32;
    let tint_k = tint_alpha as f32 / 255.0;

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
                (
                    (dx * cos_a + dy * sin_a + cx) / sc,
                    (-dx * sin_a + dy * cos_a + cy) / sc,
                )
            } else {
                (x as f32 / sc, y as f32 / sc)
            };

            let lum = (0.299 * ((p >> 16) & 0xFF) as f32
                + 0.587 * ((p >> 8) & 0xFF) as f32
                + 0.114 * (p & 0xFF) as f32) / 255.0;

            let mut t = 0.5f32;
            let mut dark_cov = 0.0f32;
            match preset {
                1 => {
                    // GRAIN — filmic, signal dependent.
                    let n1 = value_noise(sx * 1.6, sy * 1.6, 11);
                    let n2 = value_noise(sx * 0.55, sy * 0.55, 17);
                    let n3 = hash_noise(sx as i32, sy as i32, 23);
                    let raw = (n1 - 0.5) * 0.55 + (n2 - 0.5) * 0.3 + (n3 - 0.5) * 0.15;
                    let response = 0.35 + 0.65 * (1.0 - (lum - 0.5).abs() * 1.6).clamp(0.0, 1.0);
                    let sign = if raw > 0.0 { 1.0 } else if raw < 0.0 { -1.0 } else { 0.0 };
                    let shaped = sign * raw.abs().sqrt() * 1.3 * response;
                    t = clamp(0.5 + shaped, 0.02, 0.98);
                }
                2 => {
                    // PAPER
                    let base = value_noise(sx * 0.055, sy * 0.055, 23);
                    let mid = value_noise(sx * 0.16, sy * 0.16, 29);
                    let fiber = value_noise(
                        sx * 0.75 + sy * 0.31,
                        sy * 0.75 - sx * 0.31,
                        41,
                    );
                    let micro = hash_noise(sx as i32, sy as i32, 43) - 0.5;
                    t = clamp(
                        0.5 + ((base * 0.45 + mid * 0.35 + fiber * 0.2) - 0.5) * 1.9 + micro * 0.06,
                        0.02,
                        0.98,
                    );
                }
                3 => {
                    // CANVAS — grid kotak.
                    let cell = 12.0f32;
                    let line = 2.2f32;
                    let aa = edge_aa(sc, line);
                    let gx = fmod(sx, cell);
                    let gy = fmod(sy, cell);
                    dark_cov = band_coverage(gy, 0.0, line, aa).max(band_coverage(gx, 0.0, line, aa));
                    let seed = hash_noise((sx / cell).floor() as i32, (sy / cell).floor() as i32, 67);
                    let thread = 0.63 + (seed - 0.5) * 0.18;
                    t = thread - dark_cov * (thread - 0.30);
                }
                4 => {
                    // HALFTONE — screen 45 derajat.
                    let u = (sx + sy) * inv_sqrt2;
                    let v = (sy - sx) * inv_sqrt2;
                    let per = 6.0f32;
                    let gx = fmod(u, per) / per - 0.5;
                    let gy = fmod(v, per) / per - 0.5;
                    let dist = (gx * gx + gy * gy).sqrt();
                    let r = (1.0 - lum) * (0.62 * sc).clamp(0.05, 2.2);
                    let aa = edge_aa(sc, r * 2.0);
                    dark_cov = 1.0 - smoothstep(r - aa, r + aa, dist);
                    t = 0.86 - dark_cov * 0.72;
                }
                5 => {
                    // STRIPES
                    let per = 8.0f32;
                    let d = fmod(sx + sy, per);
                    dark_cov = band_coverage(d, 0.0, per * 0.45, edge_aa(sc, per * 0.45));
                    t = 0.86 - dark_cov * 0.72;
                }
                6 => {
                    // DOTS
                    let per = 7.0f32;
                    let gx = fmod(sx, per) / per - 0.5;
                    let gy = fmod(sy, per) / per - 0.5;
                    let dist = (gx * gx + gy * gy).sqrt();
                    let r = 0.34 * sc;
                    let aa = edge_aa(sc, r * 2.0);
                    dark_cov = 1.0 - smoothstep(r - aa, r + aa, dist);
                    t = 0.86 - dark_cov * 0.72;
                }
                7 => {
                    // CHEVRON
                    let zig_per = 10.0f32;
                    let zig_amp = 5.0f32;
                    let stripe_per = 7.0f32;
                    let xm = fmod(sx, zig_per);
                    let tri = if xm < zig_per * 0.5 { xm } else { zig_per - xm };
                    let yy = fmod(sy + tri / (zig_per * 0.5) * zig_amp, stripe_per);
                    dark_cov = band_coverage(
                        yy,
                        0.0,
                        stripe_per * 0.5,
                        edge_aa(sc, stripe_per * 0.5),
                    );
                    t = 0.86 - dark_cov * 0.72;
                }
                8 => {
                    // BRICKS
                    let bw = 20.0f32;
                    let bh = 10.0f32;
                    let mortar = 2.6f32;
                    let aa = edge_aa(sc, mortar);
                    let row = (sy / bh).floor();
                    let col_shift = fmod(row, 2.0) * (bw * 0.5);
                    let xx = fmod(sx + col_shift, bw);
                    let yy = fmod(sy, bh);
                    dark_cov = band_coverage(yy, 0.0, mortar, aa)
                        .max(band_coverage(xx, 0.0, mortar, aa));
                    let seed = hash_noise(
                        ((sx + col_shift) / bw).floor() as i32,
                        row as i32,
                        97,
                    );
                    let brick = 0.63 + (seed - 0.5) * 0.18;
                    t = brick - dark_cov * (brick - 0.30);
                }
                _ => {}
            }

            if has_tint {
                let mask = if dark_cov > 0.0 {
                    dark_cov
                } else {
                    ((t - 0.5) * 2.0).clamp(0.0, 1.0)
                };
                let k = mask * s * tint_k;
                let br = ((p >> 16) & 0xFF) as f32;
                let bg = ((p >> 8) & 0xFF) as f32;
                let bb = (p & 0xFF) as f32;
                out[idx] = (alpha << 24) as i32
                    | argb(
                        to_i32(br + (tint_r - br) * k).clamp(0, 255),
                        to_i32(bg + (tint_g - bg) * k).clamp(0, 255),
                        to_i32(bb + (tint_b - bb) * k).clamp(0, 255),
                    );
            } else {
                let base_r = ((p >> 16) & 0xFF) as f32 / 255.0;
                let base_g = ((p >> 8) & 0xFF) as f32 / 255.0;
                let base_b = (p & 0xFF) as f32 / 255.0;
                let o_r = if base_r < 0.5 {
                    2.0 * base_r * t
                } else {
                    1.0 - 2.0 * (1.0 - base_r) * (1.0 - t)
                };
                let o_g = if base_g < 0.5 {
                    2.0 * base_g * t
                } else {
                    1.0 - 2.0 * (1.0 - base_g) * (1.0 - t)
                };
                let o_b = if base_b < 0.5 {
                    2.0 * base_b * t
                } else {
                    1.0 - 2.0 * (1.0 - base_b) * (1.0 - t)
                };
                out[idx] = (alpha << 24) as i32
                    | argb(
                        to_i32((base_r + (o_r - base_r) * s) * 255.0).clamp(0, 255),
                        to_i32((base_g + (o_g - base_g) * s) * 255.0).clamp(0, 255),
                        to_i32((base_b + (o_b - base_b) * s) * 255.0).clamp(0, 255),
                    );
            }
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn deterministik_dan_alpha_terjaga() {
        let src = vec![0x80_96_8C_A0u32 as i32; 64];
        let a = apply_texture_pixels(&src, 8, 8, 1, 0.75, 1.5, 0, 0.0);
        let b = apply_texture_pixels(&src, 8, 8, 1, 0.75, 1.5, 0, 0.0);
        assert_eq!(a, b);
        for p in &a {
            assert_eq!(((*p as u32) >> 24) & 0xFF, 128);
        }
        assert!(a.iter().any(|p| *p != src[0]));
    }

    #[test]
    fn kotak_canvas_tetap_kotak() {
        let src = vec![0xFF_80_80_80u32 as i32; 32 * 32];
        let out = apply_texture_pixels(&src, 32, 32, 3, 1.0, 1.0, 0, 0.0);
        let lum = |p: i32| (p >> 16) & 0xFF;
        for y in [4usize, 8] {
            assert!(
                lum(out[y * 32 + 1]) < lum(out[y * 32 + 6]) - 40,
                "garis vertikal kanvas harus gelap"
            );
        }
    }

    #[test]
    fn rotasi_pola_mengubah_hasil() {
        let src = vec![0xFF_D2_D2_D2u32 as i32; 24 * 24];
        let flat = apply_texture_pixels(&src, 24, 24, 5, 0.9, 1.0, 0, 0.0);
        let tilt = apply_texture_pixels(&src, 24, 24, 5, 0.9, 1.0, 0, 35.0);
        assert_ne!(flat, tilt);
    }
}
