#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <cmath>
#include <algorithm>
#include <cstring>
#include <queue>

#ifdef HAVE_OPENCV
#include <opencv2/opencv.hpp>
#include <opencv2/photo.hpp>
#include <opencv2/imgproc.hpp>
#endif

#define LOG_TAG "NativeImaging"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct PointF {
    float x;
    float y;
};

// Mask Selection helper functions operating on ALPHA_8 native bitmap buffer.
// stride = bytes per row (info.stride); never assume stride == width.
// Row base: pixels + y * stride; pixel: base[x] (1 byte per pixel).
static void drawCircleAlpha8(uint8_t* pixels, int width, int height, int stride, float cx, float cy, float radius, bool draw) {
    int minX = std::max(0, (int)std::floor(cx - radius));
    int maxX = std::min(width - 1, (int)std::ceil(cx + radius));
    int minY = std::max(0, (int)std::floor(cy - radius));
    int maxY = std::min(height - 1, (int)std::ceil(cy + radius));

    float r2 = radius * radius;
    uint8_t val = draw ? 255 : 0;

    for (int y = minY; y <= maxY; ++y) {
        float dy = (float)y - cy;
        float dy2 = dy * dy;
        uint8_t* row = pixels + y * stride;
        for (int x = minX; x <= maxX; ++x) {
            float dx = (float)x - cx;
            if (dx * dx + dy2 <= r2) {
                row[x] = val;
            }
        }
    }
}

static void drawLineAlpha8(uint8_t* pixels, int width, int height, int stride, float x0, float y0, float x1, float y1, float radius, bool draw) {
    float dx = x1 - x0;
    float dy = y1 - y0;
    float len = std::sqrt(dx * dx + dy * dy);
    if (len == 0.0f) {
        drawCircleAlpha8(pixels, width, height, stride, x0, y0, radius, draw);
        return;
    }

    int steps = (int)std::ceil(len);
    float stepX = dx / steps;
    float stepY = dy / steps;

    float curX = x0;
    float curY = y0;
    for (int i = 0; i <= steps; ++i) {
        drawCircleAlpha8(pixels, width, height, stride, curX, curY, radius, draw);
        curX += stepX;
        curY += stepY;
    }
}

static void fillPolygonAlpha8(uint8_t* pixels, int width, int height, int stride, const std::vector<PointF>& pts, uint8_t val) {
    if (pts.size() < 3) return;

    int minY = height - 1;
    int maxY = 0;
    for (const auto& p : pts) {
        minY = std::min(minY, std::max(0, (int)std::floor(p.y)));
        maxY = std::max(maxY, std::min(height - 1, (int)std::ceil(p.y)));
    }

    int numPts = (int)pts.size();

    for (int y = minY; y <= maxY; ++y) {
        float scanY = (float)y + 0.5f;
        std::vector<float> nodeX;

        int j = numPts - 1;
        for (int i = 0; i < numPts; ++i) {
            if ((pts[i].y < scanY && pts[j].y >= scanY) || (pts[j].y < scanY && pts[i].y >= scanY)) {
                float ix = pts[i].x + (scanY - pts[i].y) / (pts[j].y - pts[i].y) * (pts[j].x - pts[i].x);
                nodeX.push_back(ix);
            }
            j = i;
        }

        std::sort(nodeX.begin(), nodeX.end());

        int rowBase = y * stride;
        for (size_t k = 0; k < nodeX.size(); k += 2) {
            if (k + 1 >= nodeX.size()) break;
            int startX = std::max(0, (int)std::ceil(nodeX[k]));
            int endX = std::min(width - 1, (int)std::floor(nodeX[k + 1]));
            for (int x = startX; x <= endX; ++x) {
                pixels[rowBase + x] = val;
            }
        }
    }
}

extern "C" {

// Dilatasi lingkaran manual stride-aware (dipakai bila tanpa OpenCV atau
// bila cv::dilate melempar exception).
static void dilateMaskHand(uint8_t* srcPtr, uint8_t* dstPtr, int w, int h,
                           int srcStride, int dstStride, int radius) {
    for (int y = 0; y < h; ++y) {
        std::memcpy(dstPtr + y * dstStride, srcPtr + y * srcStride, w);
    }

    int r2 = radius * radius;
    for (int y = 0; y < h; ++y) {
        uint8_t* srcRow = srcPtr + y * srcStride;
        for (int x = 0; x < w; ++x) {
            if (srcRow[x] > 0) {
                int minY = std::max(0, y - radius);
                int maxY = std::min(h - 1, y + radius);
                int minX = std::max(0, x - radius);
                int maxX = std::min(w - 1, x + radius);
                for (int ny = minY; ny <= maxY; ++ny) {
                    int dy = ny - y;
                    int dy2 = dy * dy;
                    uint8_t* dstRow = dstPtr + ny * dstStride;
                    for (int nx = minX; nx <= maxX; ++nx) {
                        int dx = nx - x;
                        if (dx * dx + dy2 <= r2) {
                            dstRow[nx] = 255;
                        }
                    }
                }
            }
        }
    }
}

JNIEXPORT jstring JNICALL
Java_com_mochits_core_imaging_NativeBridge_nativeGetOpenCVVersion(
        JNIEnv* env,
        jobject /* this */) {
#ifdef HAVE_OPENCV
    std::string version = CV_VERSION;
    return env->NewStringUTF(version.c_str());
#else
    return env->NewStringUTF("OpenCV Not Loaded");
#endif
}

JNIEXPORT void JNICALL
Java_com_mochits_core_imaging_NativeBridge_nativeDrawCircle(
        JNIEnv* env,
        jobject /* this */,
        jobject bitmap,
        jfloat cx,
        jfloat cy,
        jfloat radius,
        jboolean draw) {
    AndroidBitmapInfo info;
    void* pixels = nullptr;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0 || info.format != ANDROID_BITMAP_FORMAT_A_8) return;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0 || !pixels) return;

    drawCircleAlpha8(static_cast<uint8_t*>(pixels), info.width, info.height, info.stride, cx, cy, radius, draw);

    AndroidBitmap_unlockPixels(env, bitmap);
}

JNIEXPORT void JNICALL
Java_com_mochits_core_imaging_NativeBridge_nativeDrawLine(
        JNIEnv* env,
        jobject /* this */,
        jobject bitmap,
        jfloat x0,
        jfloat y0,
        jfloat x1,
        jfloat y1,
        jfloat radius,
        jboolean draw) {
    AndroidBitmapInfo info;
    void* pixels = nullptr;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0 || info.format != ANDROID_BITMAP_FORMAT_A_8) return;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0 || !pixels) return;

    drawLineAlpha8(static_cast<uint8_t*>(pixels), info.width, info.height, info.stride, x0, y0, x1, y1, radius, draw);

    AndroidBitmap_unlockPixels(env, bitmap);
}

JNIEXPORT void JNICALL
Java_com_mochits_core_imaging_NativeBridge_nativeDrawPolygon(
        JNIEnv* env,
        jobject /* this */,
        jobject bitmap,
        jfloatArray pointsX,
        jfloatArray pointsY,
        jboolean draw) {
    if (!pointsX || !pointsY) return;
    jsize lenX = env->GetArrayLength(pointsX);
    jsize lenY = env->GetArrayLength(pointsY);
    if (lenX != lenY || lenX < 3) return;

    jfloat* arrX = env->GetFloatArrayElements(pointsX, nullptr);
    jfloat* arrY = env->GetFloatArrayElements(pointsY, nullptr);
    if (!arrX || !arrY) return;

    std::vector<PointF> pts(lenX);
    for (jsize i = 0; i < lenX; ++i) {
        pts[i] = {arrX[i], arrY[i]};
    }

    env->ReleaseFloatArrayElements(pointsX, arrX, JNI_ABORT);
    env->ReleaseFloatArrayElements(pointsY, arrY, JNI_ABORT);

    AndroidBitmapInfo info;
    void* pixels = nullptr;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0 || info.format != ANDROID_BITMAP_FORMAT_A_8) return;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0 || !pixels) return;

    fillPolygonAlpha8(static_cast<uint8_t*>(pixels), info.width, info.height, info.stride, pts, draw ? 255 : 0);

    AndroidBitmap_unlockPixels(env, bitmap);
}

JNIEXPORT void JNICALL
Java_com_mochits_core_imaging_NativeBridge_nativeDilateMask(
        JNIEnv* env,
        jobject /* this */,
        jobject srcMaskBitmap,
        jobject dstMaskBitmap,
        jint radius) {
    AndroidBitmapInfo srcInfo, dstInfo;
    void* srcPixels = nullptr;
    void* dstPixels = nullptr;

    if (AndroidBitmap_getInfo(env, srcMaskBitmap, &srcInfo) < 0 || srcInfo.format != ANDROID_BITMAP_FORMAT_A_8) return;
    if (AndroidBitmap_getInfo(env, dstMaskBitmap, &dstInfo) < 0 || dstInfo.format != ANDROID_BITMAP_FORMAT_A_8) return;

    if (srcInfo.width != dstInfo.width || srcInfo.height != dstInfo.height) return;

    if (AndroidBitmap_lockPixels(env, srcMaskBitmap, &srcPixels) < 0 || !srcPixels) return;
    if (AndroidBitmap_lockPixels(env, dstMaskBitmap, &dstPixels) < 0 || !dstPixels) {
        AndroidBitmap_unlockPixels(env, srcMaskBitmap);
        return;
    }

    int w = srcInfo.width;
    int h = srcInfo.height;
    int srcStride = srcInfo.stride;
    int dstStride = dstInfo.stride;

    if (radius <= 0) {
        uint8_t* srcPtr = static_cast<uint8_t*>(srcPixels);
        uint8_t* dstPtr = static_cast<uint8_t*>(dstPixels);
        for (int y = 0; y < h; ++y) {
            std::memcpy(dstPtr + y * dstStride, srcPtr + y * srcStride, w);
        }
    } else {
#ifdef HAVE_OPENCV
        bool ocvOk = false;
        try {
            cv::Mat srcMat(h, w, CV_8UC1, srcPixels, srcStride);
            cv::Mat dstMat(h, w, CV_8UC1, dstPixels, dstStride);

            int kernelSize = radius * 2 + 1;
            cv::Mat element = cv::getStructuringElement(cv::MORPH_ELLIPSE, cv::Size(kernelSize, kernelSize));
            cv::dilate(srcMat, dstMat, element);
            ocvOk = true;
        } catch (const cv::Exception& e) {
            LOGE("dilate OCV gagal, pakai loop tangan: %s", e.what());
        } catch (...) {
            LOGE("dilate OCV gagal (unknown), pakai loop tangan");
        }
        if (!ocvOk) {
            dilateMaskHand(
                static_cast<uint8_t*>(srcPixels), static_cast<uint8_t*>(dstPixels),
                w, h, srcStride, dstStride, radius);
        }
#else
        dilateMaskHand(
            static_cast<uint8_t*>(srcPixels), static_cast<uint8_t*>(dstPixels),
            w, h, srcStride, dstStride, radius);
#endif
    }

    AndroidBitmap_unlockPixels(env, dstMaskBitmap);
    AndroidBitmap_unlockPixels(env, srcMaskBitmap);
}

JNIEXPORT jlong JNICALL
Java_com_mochits_core_imaging_NativeBridge_nativeMagicWandSelect(
        JNIEnv* env,
        jobject /* this */,
        jobject srcBitmap,
        jobject maskBitmap,
        jint startX,
        jint startY,
        jfloat tolerance) {
    // Status (32 bit bawah): 0 ok, 1 format citra, 2 ukuran mask,
    // 3 seed di luar, 4 kunci piksel gagal, 5 alokasi/proses gagal.
    // 32 bit atas: jumlah piksel terseleksi (untuk peringatan bocor).
    AndroidBitmapInfo srcInfo, maskInfo;
    void* srcPixels = nullptr;
    void* maskPixels = nullptr;

    if (AndroidBitmap_getInfo(env, srcBitmap, &srcInfo) < 0 || srcInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return 1;
    if (AndroidBitmap_getInfo(env, maskBitmap, &maskInfo) < 0 || maskInfo.format != ANDROID_BITMAP_FORMAT_A_8) return 1;

    int width = srcInfo.width;
    int height = srcInfo.height;
    if (maskInfo.width != width || maskInfo.height != height) return 2;
    if (startX < 0 || startX >= width || startY < 0 || startY >= height) return 3;

    if (AndroidBitmap_lockPixels(env, srcBitmap, &srcPixels) < 0 || !srcPixels) return 4;
    if (AndroidBitmap_lockPixels(env, maskBitmap, &maskPixels) < 0 || !maskPixels) {
        AndroidBitmap_unlockPixels(env, srcBitmap);
        return 4;
    }

    // Satu-satunya implementasi flood (kontrak Euclidean ke seed,
    // FIXED-range, konektivitas 4 — sama di semua backend/build).
    // Stride-aware: RGBA stride piksel = stride byte / 4; mask 1 byte/px.
    int status = 0;
    size_t floodCount = 0;
    try {
        const int srcStridePx = srcInfo.stride / 4;
        const int maskStride = maskInfo.stride;
        uint32_t* srcBase = static_cast<uint32_t*>(srcPixels);
        uint8_t* maskBase = static_cast<uint8_t*>(maskPixels);

        uint32_t targetColor = srcBase[startY * srcStridePx + startX];
        // lockPixels memberi PREMULTIPLIED: transparan = (0,0,0,0) sama
        // dengan hitam pekat bila alpha diabaikan. Bandingkan alpha juga
        // (Euclidean 4D) agar garis hitam tak bocor ke area transparan.
        // Fallback JVM menyamakan ruang ini (premultiply ulang).
        int targetR = (targetColor) & 0xFF;
        int targetG = (targetColor >> 8) & 0xFF;
        int targetB = (targetColor >> 16) & 0xFF;
        int targetA = (targetColor >> 24) & 0xFF;

        // Euclidean RGB distance (tolerance already mapped to 0..441.673).
        // Tighter than per-channel Chebyshev; prevents diagonal color leaks outside target.
        float tolSq = tolerance * tolerance;

        // Penahan tepi (edge-gated, rujukan Canny: gradien kuat = dinding).
        // Gradien luminance >= 100 (garis tinta; halo AA ~30-80 lolos) jadi
        // dinding, didilasi 1px untuk menutup patahan 1px. Seleksi berhenti
        // di garis asli walau toleransi longgar. Seed selalu ditulis.
        const int EDGE_THR = 100;
        auto lumOf = [](uint32_t c) -> int {
            return (int)(((c & 0xFF) * 77 + ((c >> 8) & 0xFF) * 150 + ((c >> 16) & 0xFF) * 29) >> 8);
        };
        const size_t wh = (size_t)width * (size_t)height;
        std::vector<uint8_t> edgeDil(wh, 0);
        {
            std::vector<uint8_t> edge(wh, 0);
            for (int y = 0; y < height; ++y) {
                size_t sOff = (size_t)y * (size_t)srcStridePx;
                int y2 = y + 1 < height ? y + 1 : y;
                size_t sOff2 = (size_t)y2 * (size_t)srcStridePx;
                for (int x = 0; x < width; ++x) {
                    int x2 = x + 1 < width ? x + 1 : x;
                    int l0 = lumOf(srcBase[sOff + x]);
                    int dh = l0 - lumOf(srcBase[sOff + x2]);
                    if (dh < 0) dh = -dh;
                    int dv = l0 - lumOf(srcBase[sOff2 + x]);
                    if (dv < 0) dv = -dv;
                    if ((dh > dv ? dh : dv) >= EDGE_THR) edge[(size_t)y * (size_t)width + x] = 255;
                }
            }
            for (int y = 0; y < height; ++y) {
                for (int x = 0; x < width; ++x) {
                    bool w = false;
                    for (int jy = y - 1; jy <= y + 1 && !w; ++jy) {
                        if (jy < 0 || jy >= height) continue;
                        size_t eOff = (size_t)jy * (size_t)width;
                        for (int jx = x - 1; jx <= x + 1; ++jx) {
                            if (jx < 0 || jx >= width) continue;
                            if (edge[eOff + jx]) { w = true; break; }
                        }
                    }
                    if (w) edgeDil[(size_t)y * (size_t)width + x] = 255;
                }
            }
        }
        struct FloodSpan { int y; int x1; int x2; int dy; int depth; };
        std::vector<uint8_t> filled(wh, 0);

        // Budget kedalaman pita ketat: lolos dinding hanya dalam 3 langkah
        // baris dari seed (titik/garis tipis utuh; jaringan global terhenti).
        auto eligibleAt = [&](int x, int y, int depth) -> bool {
            size_t p = (size_t)y * (size_t)width + (size_t)x;
            uint32_t c = srcBase[(size_t)y * (size_t)srcStridePx + (size_t)x];
            float dr = static_cast<float>(((int)(c & 0xFF)) - targetR);
            float dg = static_cast<float>(((int)((c >> 8) & 0xFF)) - targetG);
            float db = static_cast<float>(((int)((c >> 16) & 0xFF)) - targetB);
            float da = static_cast<float>(((int)((c >> 24) & 0xFF)) - targetA);
            float distSq = dr * dr + dg * dg + db * db + da * da;
            // Pita ketat dibatasi toleransi: warna ≈ seed selalu lolos
            // dinding, tapi tak pernah melampaui toleransi itu sendiri.
            if (distSq > tolSq) return false;
            if (distSq <= 625.0f) return depth < 3;
            if (edgeDil[p]) return false;
            return true;
        };
        auto fillRun = [&](int y, int xa, int xb) {
            uint8_t* mrow = maskBase + (size_t)y * (size_t)maskStride;
            size_t pOff = (size_t)y * (size_t)width;
            for (int x = xa; x <= xb; ++x) {
                filled[pOff + (size_t)x] = 1;
                mrow[x] = 255;
                ++floodCount;
            }
        };

        int sx1 = startX, sx2 = startX;
        while (sx1 - 1 >= 0 && eligibleAt(sx1 - 1, startY, 0)) --sx1;
        while (sx2 + 1 < width && eligibleAt(sx2 + 1, startY, 0)) ++sx2;
        fillRun(startY, sx1, sx2);

        std::vector<FloodSpan> spanStack;
        spanStack.reserve(256);
        spanStack.push_back({startY, sx1, sx2, 1, 0});
        spanStack.push_back({startY, sx1, sx2, -1, 0});
        while (!spanStack.empty()) {
            FloodSpan s = spanStack.back();
            spanStack.pop_back();
            int ny = s.y + s.dy;
            if (ny < 0 || ny >= height) continue;
            const int cd = s.depth + 1;
            size_t nOff = (size_t)ny * (size_t)width;
            int x = s.x1;
            while (x <= s.x2) {
                if (filled[nOff + (size_t)x] || !eligibleAt(x, ny, cd)) { ++x; continue; }
                int nx1 = x, nx2 = x;
                while (nx1 - 1 >= 0 && !filled[nOff + (size_t)nx1 - 1] && eligibleAt(nx1 - 1, ny, cd)) --nx1;
                while (nx2 + 1 < width && !filled[nOff + (size_t)nx2 + 1] && eligibleAt(nx2 + 1, ny, cd)) ++nx2;
                fillRun(ny, nx1, nx2);
                spanStack.push_back({ny, nx1, nx2, s.dy, cd});
                spanStack.push_back({ny, nx1, nx2, -s.dy, cd});
                x = nx2 + 1;
            }
        }
    } catch (...) {
        LOGE("nativeMagicWandSelect gagal (alloc/proses)");
        status = 5;
    }

    AndroidBitmap_unlockPixels(env, maskBitmap);
    AndroidBitmap_unlockPixels(env, srcBitmap);
    return (static_cast<jlong>(floodCount) << 32) | static_cast<jlong>(static_cast<uint32_t>(status));
}

JNIEXPORT void JNICALL
Java_com_mochits_core_imaging_NativeBridge_nativeClearMask(
        JNIEnv* env,
        jobject /* this */,
        jobject bitmap) {
    AndroidBitmapInfo info;
    void* pixels = nullptr;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0 || info.format != ANDROID_BITMAP_FORMAT_A_8) return;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0 || !pixels) return;

    std::memset(pixels, 0, (size_t)info.stride * (size_t)info.height);

    AndroidBitmap_unlockPixels(env, bitmap);
}

JNIEXPORT void JNICALL
Java_com_mochits_core_imaging_NativeBridge_nativeInvertMask(
        JNIEnv* env,
        jobject /* this */,
        jobject bitmap) {
    AndroidBitmapInfo info;
    void* pixels = nullptr;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0 || info.format != ANDROID_BITMAP_FORMAT_A_8) return;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0 || !pixels) return;

    uint8_t* ptr = static_cast<uint8_t*>(pixels);
    for (int y = 0; y < info.height; ++y) {
        uint8_t* row = ptr + y * info.stride;
        for (int x = 0; x < info.width; ++x) {
            row[x] = 255 - row[x];
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap);
}

JNIEXPORT jboolean JNICALL
Java_com_mochits_core_imaging_NativeBridge_nativeHasMask(
        JNIEnv* env,
        jobject /* this */,
        jobject bitmap) {
    AndroidBitmapInfo info;
    void* pixels = nullptr;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0 || info.format != ANDROID_BITMAP_FORMAT_A_8) return JNI_FALSE;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0 || !pixels) return JNI_FALSE;

    uint8_t* ptr = static_cast<uint8_t*>(pixels);
    bool has = false;

    for (int y = 0; y < info.height && !has; ++y) {
        uint8_t* row = ptr + y * info.stride;
        for (int x = 0; x < info.width; ++x) {
            if (row[x] > 0) {
                has = true;
                break;
            }
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    return has ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_mochits_core_imaging_NativeBridge_nativeInpaintTelea(
        JNIEnv* env,
        jobject /* this */,
        jobject srcBitmap,
        jobject maskBitmap,
        jobject dstBitmap,
        jfloat radius) {
    AndroidBitmapInfo srcInfo, maskInfo, dstInfo;
    void* srcPixels = nullptr;
    void* maskPixels = nullptr;
    void* dstPixels = nullptr;

    if (AndroidBitmap_getInfo(env, srcBitmap, &srcInfo) < 0 || srcInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;
    if (AndroidBitmap_getInfo(env, maskBitmap, &maskInfo) < 0 || maskInfo.format != ANDROID_BITMAP_FORMAT_A_8) return JNI_FALSE;
    if (AndroidBitmap_getInfo(env, dstBitmap, &dstInfo) < 0 || dstInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;

    if (srcInfo.width != dstInfo.width || srcInfo.height != dstInfo.height) return JNI_FALSE;
    if (srcInfo.width != maskInfo.width || srcInfo.height != maskInfo.height) return JNI_FALSE;

    if (AndroidBitmap_lockPixels(env, srcBitmap, &srcPixels) < 0 || !srcPixels) return JNI_FALSE;
    if (AndroidBitmap_lockPixels(env, maskBitmap, &maskPixels) < 0 || !maskPixels) {
        AndroidBitmap_unlockPixels(env, srcBitmap);
        return JNI_FALSE;
    }
    if (AndroidBitmap_lockPixels(env, dstBitmap, &dstPixels) < 0 || !dstPixels) {
        AndroidBitmap_unlockPixels(env, maskBitmap);
        AndroidBitmap_unlockPixels(env, srcBitmap);
        return JNI_FALSE;
    }

#ifdef HAVE_OPENCV
    int w = srcInfo.width;
    int h = srcInfo.height;

    cv::Mat srcMat(h, w, CV_8UC4, srcPixels, srcInfo.stride);
    cv::Mat bgrMat;
    cv::cvtColor(srcMat, bgrMat, cv::COLOR_RGBA2BGR);

    cv::Mat maskMat(h, w, CV_8UC1, maskPixels, maskInfo.stride);

    cv::Mat inpaintedBgr;
    cv::inpaint(bgrMat, maskMat, inpaintedBgr, static_cast<double>(radius), cv::INPAINT_TELEA);

    cv::Mat dstMat(h, w, CV_8UC4, dstPixels, dstInfo.stride);
    cv::cvtColor(inpaintedBgr, dstMat, cv::COLOR_BGR2RGBA);

    AndroidBitmap_unlockPixels(env, dstBitmap);
    AndroidBitmap_unlockPixels(env, maskBitmap);
    AndroidBitmap_unlockPixels(env, srcBitmap);
    return JNI_TRUE;
#else
    // Fallback if compiled without OpenCV (e.g. initial test phase before OpenCV CMake linking)
    // Laporkan GAGAL (bukan sukses-semu): penyalin buta membuat UI mengira inpaint berhasil.
    std::memcpy(dstPixels, srcPixels, srcInfo.height * srcInfo.stride);
    AndroidBitmap_unlockPixels(env, dstBitmap);
    AndroidBitmap_unlockPixels(env, maskBitmap);
    AndroidBitmap_unlockPixels(env, srcBitmap);
    return JNI_FALSE;
#endif
}

}
