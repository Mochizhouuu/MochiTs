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

static void applyMorphologicalCloseAndFeather(
        uint8_t* selectionMatData, int w, int h, int stride, jfloat featherRadius) {
#ifdef HAVE_OPENCV
    try {
        cv::Mat selectionMat(h, w, CV_8UC1, selectionMatData, stride);
        // Morphological close (3x3 ellipse)
        cv::Mat kernel = cv::getStructuringElement(cv::MORPH_ELLIPSE, cv::Size(3, 3));
        cv::morphologyEx(selectionMat, selectionMat, cv::MORPH_CLOSE, kernel);

        if (featherRadius > 0.0f) {
            int ksize = static_cast<int>(std::ceil(featherRadius) * 2 + 1);
            if (ksize % 2 == 0) ksize++;
            if (ksize < 3) ksize = 3;
            cv::GaussianBlur(selectionMat, selectionMat, cv::Size(ksize, ksize), featherRadius);
        }
        return;
    } catch (const cv::Exception& e) {
        LOGE("applyMorphologicalCloseAndFeather OCV failed: %s", e.what());
    } catch (...) {
        LOGE("applyMorphologicalCloseAndFeather OCV failed (unknown)");
    }
#endif
    // Hand fallback for morphological close (3x3 dilate then 3x3 erode)
    std::vector<uint8_t> tmp(static_cast<size_t>(w) * h, 0);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            uint8_t maxV = 0;
            for (int dy = -1; dy <= 1; ++dy) {
                int ny = y + dy;
                if (ny < 0 || ny >= h) continue;
                for (int dx = -1; dx <= 1; ++dx) {
                    int nx = x + dx;
                    if (nx < 0 || nx >= w) continue;
                    maxV = std::max(maxV, selectionMatData[ny * stride + nx]);
                }
            }
            tmp[static_cast<size_t>(y) * w + x] = maxV;
        }
    }
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            uint8_t minV = 255;
            for (int dy = -1; dy <= 1; ++dy) {
                int ny = y + dy;
                if (ny < 0 || ny >= h) continue;
                for (int dx = -1; dx <= 1; ++dx) {
                    int nx = x + dx;
                    if (nx < 0 || nx >= w) continue;
                    minV = std::min(minV, tmp[static_cast<size_t>(ny) * w + nx]);
                }
            }
            selectionMatData[y * stride + x] = minV;
        }
    }

    if (featherRadius > 0.0f) {
        int r = static_cast<int>(std::ceil(featherRadius));
        if (r < 1) r = 1;
        std::vector<uint8_t> blurred(static_cast<size_t>(w) * h, 0);
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                int sum = 0, count = 0;
                int minY = std::max(0, y - r);
                int maxY = std::min(h - 1, y + r);
                int minX = std::max(0, x - r);
                int maxX = std::min(w - 1, x + r);
                for (int ny = minY; ny <= maxY; ++ny) {
                    for (int nx = minX; nx <= maxX; ++nx) {
                        sum += selectionMatData[ny * stride + nx];
                        count++;
                    }
                }
                blurred[static_cast<size_t>(y) * w + x] = static_cast<uint8_t>(sum / count);
            }
        }
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                selectionMatData[y * stride + x] = blurred[static_cast<size_t>(y) * w + x];
            }
        }
    }
}

JNIEXPORT jlong JNICALL
Java_com_mochits_core_imaging_NativeBridge_nativeMagicWandSelect(
        JNIEnv* env,
        jobject /* this */,
        jobject srcBitmap,
        jobject maskBitmap,
        jint startX,
        jint startY,
        jfloat tolerance,
        jint gapRadius,
        jboolean isGlobal,
        jint wandMode,
        jfloat featherRadius) {
    // Status (32 bit bawah): 0 ok, 1 format citra, 2 ukuran mask,
    // 3 seed di luar, 4 kunci piksel gagal, 5 alokasi/proses gagal.
    // 32 bit atas: jumlah piksel terseleksi.
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

    int status = 0;
    size_t floodCount = 0;
    try {
        const int srcStridePx = srcInfo.stride / 4;
        const int maskStride = maskInfo.stride;
        uint32_t* srcBase = static_cast<uint32_t*>(srcPixels);
        uint8_t* maskBase = static_cast<uint8_t*>(maskPixels);

        uint32_t targetColor = srcBase[startY * srcStridePx + startX];
        int targetR = (targetColor) & 0xFF;
        int targetG = (targetColor >> 8) & 0xFF;
        int targetB = (targetColor >> 16) & 0xFF;
        int targetA = (targetColor >> 24) & 0xFF;

        float tolSq = tolerance * tolerance;
        const size_t wh = static_cast<size_t>(width) * static_cast<size_t>(height);

        // Selection buffer for current wand operation
        std::vector<uint8_t> selectionMat(wh, 0);

        if (isGlobal) {
            // Global selection: compare all pixels against seed color
            for (int y = 0; y < height; ++y) {
                size_t srcOff = static_cast<size_t>(y) * static_cast<size_t>(srcStridePx);
                size_t selOff = static_cast<size_t>(y) * static_cast<size_t>(width);
                for (int x = 0; x < width; ++x) {
                    uint32_t c = srcBase[srcOff + x];
                    float dr = static_cast<float>(((int)(c & 0xFF)) - targetR);
                    float dg = static_cast<float>(((int)((c >> 8) & 0xFF)) - targetG);
                    float db = static_cast<float>(((int)((c >> 16) & 0xFF)) - targetB);
                    float da = static_cast<float>(((int)((c >> 24) & 0xFF)) - targetA);
                    if (dr * dr + dg * dg + db * db + da * da <= tolSq) {
                        selectionMat[selOff + x] = 255;
                    }
                }
            }
        } else {
            // Contiguous edge-aware flood fill
            const int g = gapRadius < 0 ? 0 : (gapRadius > 8 ? 8 : gapRadius);

#ifdef HAVE_OPENCV
            bool ocvSuccess = false;
            try {
                cv::Mat srcMat(height, width, CV_8UC4, srcPixels, srcInfo.stride);
                cv::Mat grayMat;
                cv::cvtColor(srcMat, grayMat, cv::COLOR_RGBA2GRAY);

                cv::Mat edgeMask;
                cv::Canny(grayMat, edgeMask, 50, 150);

                if (g > 0) {
                    cv::Mat element = cv::getStructuringElement(cv::MORPH_ELLIPSE, cv::Size(g * 2 + 1, g * 2 + 1));
                    cv::dilate(edgeMask, edgeMask, element);
                }

                // OpenCV floodFill requires mask size = (height + 2) x (width + 2)
                cv::Mat floodMask = cv::Mat::zeros(height + 2, width + 2, CV_8UC1);
                edgeMask.copyTo(floodMask(cv::Rect(1, 1, width, height)));

                int perChannelTol = static_cast<int>(std::min(255.0f, (tolerance / 441.673f) * 255.0f));
                cv::Scalar diff(perChannelTol, perChannelTol, perChannelTol, perChannelTol);

                // Flood fill with fixed range and mask output = 255
                cv::floodFill(srcMat, floodMask, cv::Point(startX, startY), cv::Scalar(255, 255, 255, 255),
                              nullptr, diff, diff,
                              cv::FLOODFILL_MASK_ONLY | cv::FLOODFILL_FIXED_RANGE | (255 << 8) | 4);

                // Copy flood result from floodMask to selectionMat
                for (int y = 0; y < height; ++y) {
                    const uint8_t* fRow = floodMask.ptr<uint8_t>(y + 1) + 1;
                    const uint8_t* eRow = edgeMask.ptr<uint8_t>(y);
                    uint8_t* sRow = selectionMat.data() + y * width;
                    for (int x = 0; x < width; ++x) {
                        // Include pixels filled by floodFill that were not original edge barriers
                        if (fRow[x] > 0 && eRow[x] == 0) {
                            sRow[x] = 255;
                        }
                    }
                }
                // Ensure seed pixel itself is selected
                selectionMat[startY * width + startX] = 255;
                ocvSuccess = true;
            } catch (const cv::Exception& e) {
                LOGE("OpenCV floodFill failed, falling back to C++ BFS: %s", e.what());
            } catch (...) {
                LOGE("OpenCV floodFill failed (unknown), falling back to C++ BFS");
            }

            if (!ocvSuccess)
#endif
            {
                // Fallback BFS flood fill with edge barrier detection
                std::vector<uint8_t> barDil;
                if (g > 0) {
                    std::vector<uint8_t> bar(wh, 0);
                    barDil.assign(wh, 0);
                    for (int y = 0; y < height; ++y) {
                        size_t srcOff = static_cast<size_t>(y) * static_cast<size_t>(srcStridePx);
                        for (int x = 0; x < width; ++x) {
                            uint32_t c = srcBase[srcOff + x];
                            float dr = static_cast<float>(((int)(c & 0xFF)) - targetR);
                            float dg = static_cast<float>(((int)((c >> 8) & 0xFF)) - targetG);
                            float db = static_cast<float>(((int)((c >> 16) & 0xFF)) - targetB);
                            float da = static_cast<float>(((int)((c >> 24) & 0xFF)) - targetA);
                            if (dr * dr + dg * dg + db * db + da * da > tolSq) {
                                bar[static_cast<size_t>(y) * width + x] = 255;
                            }
                        }
                    }
                    dilateMaskHand(bar.data(), barDil.data(), width, height, width, width, g);
                }

                std::vector<uint8_t> visited(wh, 0);
                std::queue<std::pair<int, int>> q;
                q.push({startX, startY});
                visited[static_cast<size_t>(startY) * width + startX] = 1;

                const int dx[4] = {0, 0, -1, 1};
                const int dy[4] = {-1, 1, 0, 0};

                while (!q.empty()) {
                    auto [cx, cy] = q.front();
                    q.pop();

                    selectionMat[static_cast<size_t>(cy) * width + cx] = 255;

                    for (int i = 0; i < 4; ++i) {
                        int nx = cx + dx[i];
                        int ny = cy + dy[i];

                        if (nx >= 0 && nx < width && ny >= 0 && ny < height) {
                            size_t nIdx = static_cast<size_t>(ny) * width + nx;
                            if (!visited[nIdx]) {
                                visited[nIdx] = 1;
                                if (g > 0 && !barDil.empty() && barDil[nIdx]) continue;

                                uint32_t c = srcBase[static_cast<size_t>(ny) * srcStridePx + nx];
                                float dr = static_cast<float>(((int)(c & 0xFF)) - targetR);
                                float dg = static_cast<float>(((int)((c >> 8) & 0xFF)) - targetG);
                                float db = static_cast<float>(((int)((c >> 16) & 0xFF)) - targetB);
                                float da = static_cast<float>(((int)((c >> 24) & 0xFF)) - targetA);
                                if (dr * dr + dg * dg + db * db + da * da <= tolSq) {
                                    q.push({nx, ny});
                                }
                            }
                        }
                    }
                }
            }
        }

        // Apply morphological close and feathering to selectionMat
        applyMorphologicalCloseAndFeather(selectionMat.data(), width, height, width, featherRadius);

        // Combine selectionMat into maskBitmap based on wandMode
        // 0 = REPLACE, 1 = ADD, 2 = SUBTRACT
        floodCount = 0;
        for (int y = 0; y < height; ++y) {
            uint8_t* mRow = maskBase + static_cast<size_t>(y) * maskStride;
            const uint8_t* sRow = selectionMat.data() + static_cast<size_t>(y) * width;
            for (int x = 0; x < width; ++x) {
                uint8_t selV = sRow[x];
                if (wandMode == 0) { // REPLACE
                    mRow[x] = selV;
                } else if (wandMode == 1) { // ADD
                    mRow[x] = std::max(mRow[x], selV);
                } else if (wandMode == 2) { // SUBTRACT
                    mRow[x] = static_cast<uint8_t>(std::max(0, static_cast<int>(mRow[x]) - static_cast<int>(selV)));
                }
                if (mRow[x] > 0) {
                    ++floodCount;
                }
            }
        }
    } catch (...) {
        LOGE("nativeMagicWandSelect failed (alloc/process)");
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
