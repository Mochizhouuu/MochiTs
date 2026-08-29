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

// Mask Selection helper functions operating on ALPHA_8 native bitmap buffer
static void drawCircleAlpha8(uint8_t* pixels, int width, int height, float cx, float cy, float radius, bool draw) {
    int minX = std::max(0, (int)std::floor(cx - radius));
    int maxX = std::min(width - 1, (int)std::ceil(cx + radius));
    int minY = std::max(0, (int)std::floor(cy - radius));
    int maxY = std::min(height - 1, (int)std::ceil(cy + radius));

    float r2 = radius * radius;
    uint8_t val = draw ? 255 : 0;

    for (int y = minY; y <= maxY; ++y) {
        float dy = (float)y - cy;
        float dy2 = dy * dy;
        int rowIdx = y * width;
        for (int x = minX; x <= maxX; ++x) {
            float dx = (float)x - cx;
            if (dx * dx + dy2 <= r2) {
                pixels[rowIdx + x] = val;
            }
        }
    }
}

static void drawLineAlpha8(uint8_t* pixels, int width, int height, float x0, float y0, float x1, float y1, float radius, bool draw) {
    float dx = x1 - x0;
    float dy = y1 - y0;
    float len = std::sqrt(dx * dx + dy * dy);
    if (len == 0.0f) {
        drawCircleAlpha8(pixels, width, height, x0, y0, radius, draw);
        return;
    }

    int steps = (int)std::ceil(len);
    float stepX = dx / steps;
    float stepY = dy / steps;

    float curX = x0;
    float curY = y0;
    for (int i = 0; i <= steps; ++i) {
        drawCircleAlpha8(pixels, width, height, curX, curY, radius, draw);
        curX += stepX;
        curY += stepY;
    }
}

static void fillPolygonAlpha8(uint8_t* pixels, int width, int height, const std::vector<PointF>& pts, uint8_t val) {
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

        int rowIdx = y * width;
        for (size_t k = 0; k < nodeX.size(); k += 2) {
            if (k + 1 >= nodeX.size()) break;
            int startX = std::max(0, (int)std::ceil(nodeX[k]));
            int endX = std::min(width - 1, (int)std::floor(nodeX[k + 1]));
            for (int x = startX; x <= endX; ++x) {
                pixels[rowIdx + x] = val;
            }
        }
    }
}

extern "C" {

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

    drawCircleAlpha8(static_cast<uint8_t*>(pixels), info.width, info.height, cx, cy, radius, draw);

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

    drawLineAlpha8(static_cast<uint8_t*>(pixels), info.width, info.height, x0, y0, x1, y1, radius, draw);

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

    fillPolygonAlpha8(static_cast<uint8_t*>(pixels), info.width, info.height, pts, draw ? 255 : 0);

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

    if (radius <= 0) {
        std::memcpy(dstPixels, srcPixels, w * h);
    } else {
#ifdef HAVE_OPENCV
        cv::Mat srcMat(h, w, CV_8UC1, srcPixels);
        cv::Mat dstMat(h, w, CV_8UC1, dstPixels);

        int kernelSize = radius * 2 + 1;
        cv::Mat element = cv::getStructuringElement(cv::MORPH_ELLIPSE, cv::Size(kernelSize, kernelSize));
        cv::dilate(srcMat, dstMat, element);
#else
        uint8_t* srcPtr = static_cast<uint8_t*>(srcPixels);
        uint8_t* dstPtr = static_cast<uint8_t*>(dstPixels);
        std::memcpy(dstPtr, srcPtr, w * h);

        int r2 = radius * radius;
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                if (srcPtr[y * w + x] > 0) {
                    int minY = std::max(0, y - radius);
                    int maxY = std::min(h - 1, y + radius);
                    int minX = std::max(0, x - radius);
                    int maxX = std::min(w - 1, x + radius);
                    for (int ny = minY; ny <= maxY; ++ny) {
                        int dy = ny - y;
                        int dy2 = dy * dy;
                        int rowIdx = ny * w;
                        for (int nx = minX; nx <= maxX; ++nx) {
                            int dx = nx - x;
                            if (dx * dx + dy2 <= r2) {
                                dstPtr[rowIdx + nx] = 255;
                            }
                        }
                    }
                }
            }
        }
#endif
    }

    AndroidBitmap_unlockPixels(env, dstMaskBitmap);
    AndroidBitmap_unlockPixels(env, srcMaskBitmap);
}

JNIEXPORT void JNICALL
Java_com_mochits_core_imaging_NativeBridge_nativeMagicWandSelect(
        JNIEnv* env,
        jobject /* this */,
        jobject srcBitmap,
        jobject maskBitmap,
        jint startX,
        jint startY,
        jfloat tolerance) {
    AndroidBitmapInfo srcInfo, maskInfo;
    void* srcPixels = nullptr;
    void* maskPixels = nullptr;

    if (AndroidBitmap_getInfo(env, srcBitmap, &srcInfo) < 0 || srcInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return;
    if (AndroidBitmap_getInfo(env, maskBitmap, &maskInfo) < 0 || maskInfo.format != ANDROID_BITMAP_FORMAT_A_8) return;

    int width = srcInfo.width;
    int height = srcInfo.height;
    if (maskInfo.width != width || maskInfo.height != height) return;
    if (startX < 0 || startX >= width || startY < 0 || startY >= height) return;

    if (AndroidBitmap_lockPixels(env, srcBitmap, &srcPixels) < 0 || !srcPixels) return;
    if (AndroidBitmap_lockPixels(env, maskBitmap, &maskPixels) < 0 || !maskPixels) {
        AndroidBitmap_unlockPixels(env, srcBitmap);
        return;
    }

    uint32_t* srcPtr = static_cast<uint32_t*>(srcPixels);
    uint8_t* maskPtr = static_cast<uint8_t*>(maskPixels);

    uint32_t targetColor = srcPtr[startY * width + startX];
    uint8_t targetR = (targetColor) & 0xFF;
    uint8_t targetG = (targetColor >> 8) & 0xFF;
    uint8_t targetB = (targetColor >> 16) & 0xFF;

    float tolSq = tolerance * tolerance;

    // Visited map / array to avoid re-queuing
    std::vector<uint8_t> visited(width * height, 0);

    std::queue<std::pair<int, int>> q;
    q.push({startX, startY});
    visited[startY * width + startX] = 1;

    const int dx[4] = {0, 0, -1, 1};
    const int dy[4] = {-1, 1, 0, 0};

    while (!q.empty()) {
        auto [cx, cy] = q.front();
        q.pop();

        int currIdx = cy * width + cx;
        maskPtr[currIdx] = 255; // Union with existing mask

        for (int i = 0; i < 4; ++i) {
            int nx = cx + dx[i];
            int ny = cy + dy[i];

            if (nx >= 0 && nx < width && ny >= 0 && ny < height) {
                int nIdx = ny * width + nx;
                if (!visited[nIdx]) {
                    visited[nIdx] = 1;
                    uint32_t c = srcPtr[nIdx];
                    int r = (c) & 0xFF;
                    int g = (c >> 8) & 0xFF;
                    int b = (c >> 16) & 0xFF;

                    float dr = static_cast<float>(r - targetR);
                    float dg = static_cast<float>(g - targetG);
                    float db = static_cast<float>(b - targetB);
                    float distSq = dr * dr + dg * dg + db * db;

                    if (distSq <= tolSq) {
                        q.push({nx, ny});
                    }
                }
            }
        }
    }

    AndroidBitmap_unlockPixels(env, maskBitmap);
    AndroidBitmap_unlockPixels(env, srcBitmap);
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

    std::memset(pixels, 0, info.width * info.height);

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
    int total = info.width * info.height;
    for (int i = 0; i < total; ++i) {
        ptr[i] = 255 - ptr[i];
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
    int total = info.width * info.height;
    bool has = false;

    for (int i = 0; i < total; i += 16) {
        if (ptr[i] > 0) {
            has = true;
            break;
        }
    }
    if (!has) {
        for (int i = 0; i < total; ++i) {
            if (ptr[i] > 0) {
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

    cv::Mat srcMat(h, w, CV_8UC4, srcPixels);
    cv::Mat bgrMat;
    cv::cvtColor(srcMat, bgrMat, cv::COLOR_RGBA2BGR);

    cv::Mat maskMat(h, w, CV_8UC1, maskPixels);

    cv::Mat inpaintedBgr;
    cv::inpaint(bgrMat, maskMat, inpaintedBgr, static_cast<double>(radius), cv::INPAINT_TELEA);

    cv::Mat dstMat(h, w, CV_8UC4, dstPixels);
    cv::cvtColor(inpaintedBgr, dstMat, cv::COLOR_BGR2RGBA);

    AndroidBitmap_unlockPixels(env, dstBitmap);
    AndroidBitmap_unlockPixels(env, maskBitmap);
    AndroidBitmap_unlockPixels(env, srcBitmap);
    return JNI_TRUE;
#else
    // Fallback if compiled without OpenCV (e.g. initial test phase before OpenCV CMake linking)
    std::memcpy(dstPixels, srcPixels, srcInfo.height * srcInfo.stride);
    AndroidBitmap_unlockPixels(env, dstBitmap);
    AndroidBitmap_unlockPixels(env, maskBitmap);
    AndroidBitmap_unlockPixels(env, srcBitmap);
    return JNI_TRUE;
#endif
}

}
