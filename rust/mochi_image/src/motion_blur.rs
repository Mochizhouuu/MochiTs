//! Porting 1:1 dari MotionBlur.blurDirectional (Kotlin).
//! Murni piksel + angka, tanpa Android API → di-test dengan cargo test.

const MAX_TAPS: usize = 97;

#[inline]
fn clamp(v: f64, lo: f64, hi: f64) -> f64 {
    if v < lo { lo } else if v > hi { hi } else { v }
}

/// Motion blur direksional: Gaussian, bilinear, akumulasi premultiplied.
pub fn blur_directional(
    pixels: &[i32],
    width: usize,
    height: usize,
    angle_deg: f32,
    radius: f32,
) -> Vec<i32> {
    if pixels.len() != width.saturating_mul(height) || width == 0 || height == 0 {
        return pixels.to_vec();
    }
    if radius <= 0.0 {
        return pixels.to_vec();
    }

    let rad = (angle_deg as f64).to_radians();
    let dx = rad.cos() as f32;
    let dy = rad.sin() as f32;

    let sigma = (radius / 2.5).max(0.4) as f64;
    let two_sigma_sq = 2.0 * sigma * sigma;
    let taps = ((2.0 * (radius as f64).ceil()) as usize + 1).clamp(1, MAX_TAPS);
    let step = if taps > 1 { (2.0 * radius) / (taps as f32 - 1.0) } else { 0.0 };

    let mut tap_ox = vec![0f32; taps];
    let mut tap_oy = vec![0f32; taps];
    let mut tap_w = vec![0f64; taps];
    for i in 0..taps {
        let d = -radius + i as f32 * step;
        tap_ox[i] = dx * d;
        tap_oy[i] = dy * d;
        let dd = d as f64;
        tap_w[i] = (-dd * dd / two_sigma_sq).exp();
    }
    let mut weight_sum = 0f64;
    for w in &tap_w {
        weight_sum += *w;
    }
    if weight_sum <= 0.0 {
        return pixels.to_vec();
    }

    let max_x = (width - 1) as f32;
    let max_y = (height - 1) as f32;
    let mut out = vec![0i32; pixels.len()];
    for by in 0..height {
        for bx in 0..width {
            let mut sum_a = 0f64;
            let mut sum_ra = 0f64;
            let mut sum_ga = 0f64;
            let mut sum_ba = 0f64;
            for t in 0..taps {
                let w = tap_w[t] / weight_sum;
                let fx = (bx as f32 + tap_ox[t]).clamp(0.0, max_x);
                let fy = (by as f32 + tap_oy[t]).clamp(0.0, max_y);
                let x0 = fx as usize;
                let y0 = fy as usize;
                let x1 = (x0 + 1).min(width - 1);
                let y1 = (y0 + 1).min(height - 1);
                let tx = (fx - x0 as f32) as f64;
                let ty = (fy - y0 as f32) as f64;
                let b00 = (1.0 - tx) * (1.0 - ty);
                let b10 = tx * (1.0 - ty);
                let b01 = (1.0 - tx) * ty;
                let b11 = tx * ty;

                let samples = [
                    (pixels[y0 * width + x0], b00),
                    (pixels[y0 * width + x1], b10),
                    (pixels[y1 * width + x0], b01),
                    (pixels[y1 * width + x1], b11),
                ];
                for (p, bw) in samples {
                    let ww = w * bw;
                    let aa = ((p as u32 >> 24) & 0xFF) as f64;
                    sum_a += ww * aa;
                    sum_ra += ww * aa * (((p >> 16) & 0xFF) as f64);
                    sum_ga += ww * aa * (((p >> 8) & 0xFF) as f64);
                    sum_ba += ww * aa * ((p & 0xFF) as f64);
                }
            }
            let a_out = clamp(sum_a, 0.0, 255.0);
            out[by * width + bx] = if a_out < 0.5 {
                0
            } else {
                let r = clamp(sum_ra / a_out, 0.0, 255.0);
                let g = clamp(sum_ga / a_out, 0.0, 255.0);
                let b = clamp(sum_ba / a_out, 0.0, 255.0);
                ((a_out + 0.5) as i32) << 24
                    | ((r + 0.5) as i32) << 16
                    | ((g + 0.5) as i32) << 8
                    | ((b + 0.5) as i32)
            };
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn deterministik_dan_transparan_tetap_transparan() {
        let w = 16usize;
        let h = 16usize;
        let mut src = vec![0xFF_FF_00_00u32 as i32; w * h];
        // Separuh kanvas transparan: hasil tidak boleh menambah alpha di sana.
        for i in 0..(w * h / 2) {
            src[i] = 0;
        }
        let a = blur_directional(&src, w, h, 45.0, 4.0);
        let b = blur_directional(&src, w, h, 45.0, 4.0);
        assert_eq!(a, b);
        for i in 0..(w * h / 2) {
            assert_eq!((a[i] as u32 >> 24) & 0xFF, 0);
        }
        assert!(a.iter().any(|p| (p as u32 >> 24) & 0xFF > 0));
    }

    #[test]
    fn radius_nol_mengembalikan_input() {
        let src = vec![0xFF_12_34_56u32 as i32; 9];
        assert_eq!(blur_directional(&src, 3, 3, 0.0, 0.0), src);
    }
}
